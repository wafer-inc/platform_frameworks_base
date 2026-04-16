/*
 * Copyright (C) 2026 The Wafer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.wafer.backdrop;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.util.Slog;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.window.ScreenCapture;
import android.window.ScreenCapture.LayerCaptureArgs;
import android.window.ScreenCapture.ScreenshotHardwareBuffer;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.WindowManagerInternal;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 0 + Phase 1 implementation of the Wafer backdrop capture service.
 *
 * <p>Publishes the {@code wafer_backdrop} system service. Phase 1 uses the
 * synchronous {@link ScreenCapture#captureLayers(LayerCaptureArgs)} path against
 * the topmost Task on the target display, driven by a Choreographer on a
 * dedicated {@link HandlerThread} and rate-limited to ~10 fps. Frames are
 * delivered oneway via {@link IWaferBackdropCallback#onBufferAvailable}.
 *
 * <p>Phase 2 will replace the body of {@link CaptureThread#runFrameLocked} with
 * a {@link SurfaceControl#mirrorSurface} + {@code BLASTBufferQueue} path.
 *
 * @hide
 */
public final class WaferBackdropCaptureService extends SystemService {

    private static final String TAG = "WaferBackdrop";

    private static final String PERMISSION_CAPTURE_BACKDROP =
            "com.wafer.permission.CAPTURE_BACKDROP";

    /** Target frame interval for the Phase 1 slow path (~10 fps). */
    private static final long PHASE1_MIN_FRAME_INTERVAL_NS = 90_000_000L; // 90 ms

    private final Context mContext;
    private final BinderService mBinder = new BinderService();

    /** Guards {@link #mSessions} + {@link #mCaptureThread}. */
    private final Object mLock = new Object();

    private final Map<IBinder, Session> mSessions = new HashMap<>();

    @Nullable private CaptureThread mCaptureThread;
    @Nullable private WindowManagerInternal mWmInternal;

    public WaferBackdropCaptureService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.WAFER_BACKDROP_CAPTURE_SERVICE, mBinder);
        Slog.i(TAG, "WaferBackdropCaptureService published as "
                + Context.WAFER_BACKDROP_CAPTURE_SERVICE);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            mWmInternal = LocalServices.getService(WindowManagerInternal.class);
            if (mWmInternal == null) {
                Slog.e(TAG, "WindowManagerInternal not available");
            }
        }
    }

    // ---------------- Binder surface ----------------

    private final class BinderService extends IWaferBackdropCaptureService.Stub {

        @Override
        public IBinder startSession(int displayId, IWaferBackdropCallback cb) {
            enforcePermission("startSession");
            if (cb == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            final IBinder token = new Binder("WaferBackdropSession");
            final Session session = new Session(token, displayId, cb);
            try {
                cb.asBinder().linkToDeath(session, 0);
            } catch (RemoteException e) {
                // Callback binder already dead — nothing to capture for.
                Slog.w(TAG, "callback binder already dead at startSession", e);
                return token;
            }
            synchronized (mLock) {
                mSessions.put(token, session);
                ensureCaptureThreadLocked();
                mCaptureThread.scheduleFrame();
            }
            Slog.i(TAG, "startSession: displayId=" + displayId + " token=" + token);
            return token;
        }

        @Override
        public void stopSession(IBinder token) {
            enforcePermission("stopSession");
            stopSessionInternal(token);
        }

        @Override
        public void pauseSession(IBinder token) {
            enforcePermission("pauseSession");
            synchronized (mLock) {
                final Session s = mSessions.get(token);
                if (s != null) s.paused = true;
            }
        }

        @Override
        public void resumeSession(IBinder token) {
            enforcePermission("resumeSession");
            synchronized (mLock) {
                final Session s = mSessions.get(token);
                if (s != null) {
                    s.paused = false;
                    if (mCaptureThread != null) mCaptureThread.scheduleFrame();
                }
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                                   String[] args, android.os.ShellCallback shellCallback,
                                   ResultReceiver resultReceiver) {
            new CaptureShellCommand()
                    .exec(this, in, out, err, args, shellCallback, resultReceiver);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump WaferBackdrop");
                return;
            }
            WaferBackdropCaptureService.this.dumpInternal(pw);
        }

        private void enforcePermission(String op) {
            mContext.enforceCallingOrSelfPermission(PERMISSION_CAPTURE_BACKDROP, op);
        }
    }

    private void stopSessionInternal(IBinder token) {
        Session removed;
        synchronized (mLock) {
            removed = mSessions.remove(token);
        }
        if (removed == null) return;
        try {
            removed.callback.asBinder().unlinkToDeath(removed, 0);
        } catch (Throwable ignored) {
        }
        removed.close();
        synchronized (mLock) {
            if (mSessions.isEmpty() && mCaptureThread != null) {
                mCaptureThread.quit();
                mCaptureThread = null;
            }
        }
        Slog.i(TAG, "stopSession: token=" + token);
    }

    // ---------------- Session ----------------

    private final class Session implements IBinder.DeathRecipient {
        final IBinder token;
        final int displayId;
        final IWaferBackdropCallback callback;
        volatile boolean paused;
        volatile long frameSeq;
        // Held until the next frame is delivered, per the "valid until next callback" contract.
        @Nullable HardwareBuffer lastDeliveredBuffer;

        Session(IBinder token, int displayId, IWaferBackdropCallback cb) {
            this.token = token;
            this.displayId = displayId;
            this.callback = cb;
        }

        @Override
        public void binderDied() {
            Slog.i(TAG, "callback binder died; ending session");
            stopSessionInternal(token);
        }

        void close() {
            if (lastDeliveredBuffer != null) {
                try { lastDeliveredBuffer.close(); } catch (Throwable ignored) {}
                lastDeliveredBuffer = null;
            }
        }
    }

    // ---------------- Capture thread ----------------

    private void ensureCaptureThreadLocked() {
        if (mCaptureThread == null) {
            mCaptureThread = new CaptureThread();
            mCaptureThread.startUp();
        }
    }

    private final class CaptureThread {
        private final HandlerThread mThread = new HandlerThread("WaferBackdropCapture");
        private Handler mHandler;
        private Choreographer mChoreographer;
        private long mLastCaptureStartNs;
        private boolean mScheduled;

        void startUp() {
            mThread.start();
            mHandler = new Handler(mThread.getLooper());
            mHandler.post(() -> mChoreographer = Choreographer.getInstance());
        }

        void quit() {
            mThread.quitSafely();
        }

        void scheduleFrame() {
            if (mHandler == null) return;
            mHandler.post(() -> {
                if (mScheduled || mChoreographer == null) return;
                mScheduled = true;
                mChoreographer.postFrameCallback(mFrameCallback);
            });
        }

        private final Choreographer.FrameCallback mFrameCallback = frameTimeNs -> {
            mScheduled = false;
            runFrame(frameTimeNs);
        };

        private void runFrame(long frameTimeNs) {
            final long now = SystemClock.elapsedRealtimeNanos();
            if (now - mLastCaptureStartNs < PHASE1_MIN_FRAME_INTERVAL_NS) {
                // Rate limit — reschedule without capturing.
                rescheduleIfAnyActive();
                return;
            }
            mLastCaptureStartNs = now;

            // Snapshot the active sessions under the lock, then release it before
            // hitting WMS / SurfaceFlinger.
            final Session[] active;
            synchronized (mLock) {
                if (mSessions.isEmpty()) return;
                active = mSessions.values().stream()
                        .filter(s -> !s.paused)
                        .toArray(Session[]::new);
            }

            for (Session s : active) {
                captureAndDeliver(s, frameTimeNs);
            }
            rescheduleIfAnyActive();
        }

        private void rescheduleIfAnyActive() {
            synchronized (mLock) {
                if (mSessions.isEmpty()) return;
            }
            if (mChoreographer != null && !mScheduled) {
                mScheduled = true;
                mChoreographer.postFrameCallback(mFrameCallback);
            }
        }

        private void captureAndDeliver(Session s, long frameTimeNs) {
            final WindowManagerInternal wm = mWmInternal;
            if (wm == null) {
                deliverUnavailable(s, WaferBackdropManager.REASON_NO_TARGET);
                return;
            }
            final SurfaceControl taskSc = wm.getTopTaskSurfaceControl(s.displayId);
            if (taskSc == null) {
                deliverUnavailable(s, WaferBackdropManager.REASON_NO_TARGET);
                return;
            }
            final Rect crop = getDisplayBounds(s.displayId);
            if (crop == null) {
                deliverUnavailable(s, WaferBackdropManager.REASON_CAPTURE_FAILED);
                return;
            }
            ScreenshotHardwareBuffer shb = null;
            try {
                final LayerCaptureArgs args = new LayerCaptureArgs.Builder(taskSc)
                        .setSourceCrop(crop)
                        .setChildrenOnly(true)
                        .setFrameScale(1.0f)
                        .build();
                shb = ScreenCapture.captureLayers(args);
            } catch (Throwable t) {
                Slog.w(TAG, "captureLayers threw", t);
            } finally {
                // Release our standalone SurfaceControl ref.
                taskSc.release();
            }

            if (shb == null || shb.getHardwareBuffer() == null) {
                Slog.w(TAG, "captureLayers returned null for displayId=" + s.displayId);
                deliverUnavailable(s, WaferBackdropManager.REASON_CAPTURE_FAILED);
                return;
            }
            if (shb.containsSecureLayers()) {
                // Never leak DRM content; also release the buffer.
                shb.getHardwareBuffer().close();
                deliverUnavailable(s, WaferBackdropManager.REASON_SECURE_LAYER);
                return;
            }

            final HardwareBuffer buf = shb.getHardwareBuffer();
            final int w = buf.getWidth();
            final int h = buf.getHeight();
            final long seq = ++s.frameSeq;

            // Ownership contract: the previous buffer is valid on the client until
            // the next onBufferAvailable. Deliver first, then close the previous.
            try {
                s.callback.onBufferAvailable(buf, seq, frameTimeNs, w, h);
            } catch (RemoteException e) {
                Slog.w(TAG, "onBufferAvailable failed; tearing down session", e);
                stopSessionInternal(s.token);
                buf.close();
                return;
            }

            if (s.lastDeliveredBuffer != null) {
                try { s.lastDeliveredBuffer.close(); } catch (Throwable ignored) {}
            }
            s.lastDeliveredBuffer = buf;
        }

        private void deliverUnavailable(Session s, int reason) {
            try {
                s.callback.onBackdropUnavailable(reason);
            } catch (RemoteException e) {
                stopSessionInternal(s.token);
            }
        }
    }

    @Nullable
    private Rect getDisplayBounds(int displayId) {
        final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        if (dm == null) {
            Slog.w(TAG, "DisplayManager unavailable; cannot resolve display bounds");
            return null;
        }
        final android.view.Display display = dm.getDisplay(displayId);
        if (display == null) {
            Slog.w(TAG, "No display found for displayId=" + displayId);
            return null;
        }
        final Point size = new Point();
        display.getRealSize(size);
        return new Rect(0, 0, size.x, size.y);
    }

    // ---------------- Shell + dumpsys ----------------

    private void dumpInternal(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("WaferBackdropCaptureService");
            pw.println("  active sessions: " + mSessions.size());
            for (Session s : mSessions.values()) {
                pw.println("    token=" + s.token
                        + " displayId=" + s.displayId
                        + " frameSeq=" + s.frameSeq
                        + " paused=" + s.paused);
            }
            pw.println("  captureThread: "
                    + (mCaptureThread != null ? "running" : "stopped"));
        }
    }

    /**
     * {@code adb shell cmd wafer_backdrop ...}
     *
     * Subcommands (P1):
     *   dump-frame <path> [displayId]   synchronously captures the topmost Task
     *                                   on displayId (default 0) and writes it
     *                                   as a PNG. No session required.
     */
    private final class CaptureShellCommand extends ShellCommand {

        @Override
        public int onCommand(String cmd) {
            if (cmd == null) return handleDefaultCommands(cmd);
            final PrintWriter pw = getOutPrintWriter();
            switch (cmd) {
                case "dump-frame":
                    return runDumpFrame(pw);
                default:
                    return handleDefaultCommands(cmd);
            }
        }

        private int runDumpFrame(PrintWriter pw) {
            final String path = getNextArg();
            if (path == null) {
                pw.println("usage: dump-frame <path> [displayId]");
                return 1;
            }
            final String displayIdArg = getNextArg();
            final int displayId = displayIdArg != null ? Integer.parseInt(displayIdArg) : 0;

            final WindowManagerInternal wm = mWmInternal;
            if (wm == null) {
                pw.println("WindowManagerInternal unavailable");
                return 1;
            }
            final SurfaceControl taskSc = wm.getTopTaskSurfaceControl(displayId);
            if (taskSc == null) {
                pw.println("no top task on display " + displayId);
                return 1;
            }
            final Rect crop = getDisplayBounds(displayId);
            if (crop == null) {
                pw.println("could not resolve display bounds for display " + displayId);
                taskSc.release();
                return 1;
            }
            ScreenshotHardwareBuffer shb;
            try {
                shb = ScreenCapture.captureLayers(new LayerCaptureArgs.Builder(taskSc)
                        .setSourceCrop(crop)
                        .setChildrenOnly(true)
                        .setFrameScale(1.0f)
                        .build());
            } finally {
                taskSc.release();
            }
            if (shb == null || shb.getHardwareBuffer() == null) {
                pw.println("captureLayers returned null");
                return 1;
            }
            final Bitmap hw = Bitmap.wrapHardwareBuffer(
                    shb.getHardwareBuffer(), shb.getColorSpace());
            if (hw == null) {
                pw.println("wrapHardwareBuffer failed");
                return 1;
            }
            // Copy to software so we can PNG-encode (HW bitmaps aren't compressible
            // directly).
            final Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
            try (BufferedOutputStream out = new BufferedOutputStream(
                    new FileOutputStream(path))) {
                sw.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (IOException e) {
                pw.println("write failed: " + e);
                return 1;
            } finally {
                sw.recycle();
                shb.getHardwareBuffer().close();
            }
            pw.println("wrote " + path + " (" + hw.getWidth() + "x" + hw.getHeight() + ")");
            return 0;
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("Wafer backdrop capture shell commands:");
            pw.println("  dump-frame <path> [displayId]");
            pw.println("      Capture the topmost Task on <displayId> (default 0) and");
            pw.println("      write it as a PNG.");
        }
    }

}
