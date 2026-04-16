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
import android.app.ActivityManager.RunningTaskInfo;
import android.app.TaskStackListener;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCommand;
import android.util.Slog;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.ScreenCapture;
import android.window.ScreenCapture.LayerCaptureArgs;
import android.window.ScreenCapture.ScreenshotHardwareBuffer;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 2 implementation of the Wafer backdrop capture service.
 *
 * <p>The service creates an offscreen {@link VirtualDisplay} whose output
 * surface is an {@link ImageReader}, then mirrors the foreground task (or
 * the wallpaper as a fallback) via {@link SurfaceControl#mirrorSurface} and
 * reparents the mirror under the VirtualDisplay's root SurfaceControl.
 * Crucially the VD's own windowing and overlay layers are detached once
 * (same trick {@code ContentRecorder} uses for MediaProjection) so SF has
 * only our mirrored content to composite into the VD's output surface.
 * Each new composed frame arrives as a {@link HardwareBuffer} on the
 * ImageReader's callback and is fanned out to clients. No CPU readback, no
 * rate limit — capture runs at the display's native refresh rate.
 *
 * <p>Target resolution: top non-SystemUI task first, then the wallpaper
 * layer as a fallback (so lockscreen / empty-home states still get live
 * frames). Only if neither is available do we deliver
 * {@code onBackdropUnavailable}.
 *
 * @hide
 */
public final class WaferBackdropCaptureService extends SystemService {

    private static final String TAG = "WaferBackdrop";

    private static final String PERMISSION_CAPTURE_BACKDROP =
            "com.wafer.permission.CAPTURE_BACKDROP";

    /** Max in-flight buffers in the ImageReader: 1 compositing + 1 delivered + 1 previous. */
    private static final int IMAGE_READER_MAX_IMAGES = 3;

    /**
     * Max attempts to finish VD setup (detach VD layers + reparent mirror).
     * WMS may briefly not have a {@code DisplayContent} registered for a
     * freshly-created VD; we back off and retry.
     */
    private static final int MAX_SETUP_ATTEMPTS = 10;

    /** Delay between setup retries. */
    private static final long SETUP_RETRY_DELAY_MS = 50L;

    private final Context mContext;
    private final BinderService mBinder = new BinderService();

    /** Guards {@link #mSessions}, {@link #mCaptureThread}, {@link #mMirrorPipeline},
     *  {@link #mTaskStackListener}. */
    private final Object mLock = new Object();

    private final Map<IBinder, Session> mSessions = new HashMap<>();

    @Nullable private CaptureThread mCaptureThread;
    @Nullable private MirrorPipeline mMirrorPipeline;
    @Nullable private TaskStackListenerImpl mTaskStackListener;

    @Nullable private WindowManagerInternal mWmInternal;
    @Nullable private ActivityTaskManagerInternal mAtmInternal;

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
            mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
            if (mAtmInternal == null) {
                Slog.e(TAG, "ActivityTaskManagerInternal not available");
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
                Slog.w(TAG, "callback binder already dead at startSession", e);
                return token;
            }
            synchronized (mLock) {
                mSessions.put(token, session);
                ensureCaptureThreadLocked();
                ensureMirrorPipelineLocked(displayId);
                registerTaskStackListenerLocked();
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
                if (s != null) s.paused = false;
            }
            // No explicit frame scheduling: the ImageReader callback fires
            // continuously while the mirror pipeline is active.
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

        MirrorPipeline pipelineToDestroy = null;
        CaptureThread threadToQuit = null;
        synchronized (mLock) {
            if (mSessions.isEmpty()) {
                unregisterTaskStackListenerLocked();
                pipelineToDestroy = mMirrorPipeline;
                mMirrorPipeline = null;
                threadToQuit = mCaptureThread;
                mCaptureThread = null;
            }
        }
        if (pipelineToDestroy != null && threadToQuit != null) {
            final MirrorPipeline p = pipelineToDestroy;
            threadToQuit.post(p::destroy);
            threadToQuit.quit();
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
            // No per-session buffer ref to release — the AIDL oneway dup
            // gives each client its own HardwareBuffer ref, and the service
            // keeps the backing Image alive in MirrorPipeline's rotation.
        }
    }

    // ---------------- Capture thread ----------------

    private void ensureCaptureThreadLocked() {
        if (mCaptureThread == null) {
            mCaptureThread = new CaptureThread();
            mCaptureThread.startUp();
        }
    }

    /**
     * Dedicated {@link HandlerThread} that owns all pipeline I/O:
     * {@link MirrorPipeline} construction, {@link ImageReader}
     * {@link ImageReader.OnImageAvailableListener} dispatch, and task-switch
     * handling. Serializing on a single handler avoids any locking around
     * {@link MirrorPipeline} internals.
     */
    private static final class CaptureThread {
        private final HandlerThread mThread = new HandlerThread("WaferBackdropCapture");
        private Handler mHandler;

        void startUp() {
            mThread.start();
            mHandler = new Handler(mThread.getLooper());
        }

        void quit() {
            mThread.quitSafely();
        }

        Handler getHandler() {
            return mHandler;
        }

        void post(Runnable r) {
            if (mHandler != null) mHandler.post(r);
        }
    }

    // ---------------- Mirror pipeline ----------------

    private void ensureMirrorPipelineLocked(int displayId) {
        if (mMirrorPipeline != null) return;
        final Rect bounds = getDisplayBounds(displayId);
        if (bounds == null) {
            Slog.e(TAG, "Cannot resolve display bounds for mirror pipeline on display "
                    + displayId);
            return;
        }
        if (mCaptureThread == null) {
            Slog.e(TAG, "Capture thread not started before pipeline creation");
            return;
        }
        final Handler handler = mCaptureThread.getHandler();
        final int w = bounds.width();
        final int h = bounds.height();
        final MirrorPipeline pipeline = new MirrorPipeline(displayId, w, h, handler);
        mMirrorPipeline = pipeline;
        // Actual SF / GPU object construction happens on the handler thread
        // (ImageReader's listener needs a Looper anyway).
        handler.post(pipeline::initOnHandler);
    }

    /**
     * Owns the offscreen capture plumbing for a single display. Built lazily
     * when the first session on that display starts; destroyed when the last
     * session ends.
     *
     * <p>All state here is touched only on the {@link CaptureThread} handler
     * except for {@link #destroyed}, which is read from binder threads.
     */
    private final class MirrorPipeline implements ImageReader.OnImageAvailableListener {

        final int displayId;
        final int width;
        final int height;
        final Handler handler;

        @Nullable ImageReader imageReader;
        @Nullable VirtualDisplay virtualDisplay;
        int virtualDisplayId = -1;
        /** True once the VD's own windowing + overlay layers have been detached. */
        boolean vdLayersDetached;

        /** Currently-attached mirror SurfaceControl; owner of the layer, we release it. */
        @Nullable SurfaceControl mirrorSc;
        /** True when the current mirror points at the wallpaper (fallback). */
        boolean mirrorIsWallpaper;

        /** Most recently acquired image; kept alive so its HardwareBuffer remains valid. */
        @Nullable Image currentImage;
        /** Prior image; released on the next callback after the N+1 image arrives. */
        @Nullable Image previousImage;

        volatile boolean destroyed;

        MirrorPipeline(int displayId, int width, int height, Handler handler) {
            this.displayId = displayId;
            this.width = width;
            this.height = height;
            this.handler = handler;
        }

        void initOnHandler() {
            if (destroyed) return;
            try {
                imageReader = ImageReader.newInstance(
                        width, height,
                        PixelFormat.RGBA_8888,
                        IMAGE_READER_MAX_IMAGES,
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                                | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            } catch (Throwable t) {
                Slog.e(TAG, "ImageReader creation failed", t);
                destroy();
                return;
            }
            imageReader.setOnImageAvailableListener(this, handler);

            final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            if (dm == null) {
                Slog.e(TAG, "DisplayManager unavailable; cannot create VirtualDisplay");
                destroy();
                return;
            }
            final Surface outputSurface = imageReader.getSurface();
            try {
                virtualDisplay = dm.createVirtualDisplay(
                        "WaferBackdropMirror:" + displayId,
                        width, height,
                        160 /* densityDpi — offscreen, value is cosmetic */,
                        outputSurface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            } catch (Throwable t) {
                Slog.e(TAG, "createVirtualDisplay threw", t);
                destroy();
                return;
            }
            if (virtualDisplay == null) {
                Slog.e(TAG, "createVirtualDisplay returned null for backdrop mirror");
                destroy();
                return;
            }
            virtualDisplayId = virtualDisplay.getDisplay().getDisplayId();
            Slog.i(TAG, "MirrorPipeline up: displayId=" + displayId
                    + " vdId=" + virtualDisplayId
                    + " size=" + width + "x" + height);

            pointMirrorAtBestTarget(0 /* attempt */);
        }

        /**
         * Resolve the best mirror target for this display and attach it under
         * the VirtualDisplay's root, detaching the VD's own windowing and
         * overlay layers first so SurfaceFlinger only composites the mirror
         * into the output surface (matches what {@code ContentRecorder} does
         * internally for MediaProjection).
         *
         * <p>Target priority:
         * <ol>
         *   <li>Topmost non-SystemUI task.</li>
         *   <li>Wallpaper layer (so lockscreen / empty-home still get live
         *       frames and clients never need their own wallpaper path).</li>
         *   <li>Neither → deliver {@code onBackdropUnavailable}.</li>
         * </ol>
         *
         * <p>Race handling: right after {@link DisplayManager#createVirtualDisplay}
         * WMS may not yet have a {@code DisplayContent} registered for the VD.
         * {@link WindowManagerInternal#getDisplaySurfaceControl} uses
         * {@code getDisplayContentOrCreate} to cover that, but we still retry
         * up to {@link #MAX_SETUP_ATTEMPTS} times with
         * {@link #SETUP_RETRY_DELAY_MS} backoff if setup fails.
         */
        void pointMirrorAtBestTarget(int attempt) {
            if (destroyed) return;
            final WindowManagerInternal wm = mWmInternal;
            if (wm == null) {
                deliverUnavailableToAll(WaferBackdropManager.REASON_NO_TARGET);
                return;
            }

            // One-time: strip the VD's own windowing/overlay layers so SF only
            // sees what we reparent in.
            if (!vdLayersDetached) {
                if (!wm.detachVirtualDisplayContentLayers(virtualDisplayId)) {
                    scheduleRetry(attempt, "detachVirtualDisplayContentLayers");
                    return;
                }
                vdLayersDetached = true;
            }

            // Try the foreground task first.
            SurfaceControl newMirror = null;
            boolean isWallpaper = false;
            final SurfaceControl taskSc = wm.getTopTaskSurfaceControl(displayId);
            if (taskSc != null) {
                try {
                    newMirror = SurfaceControl.mirrorSurface(taskSc);
                } catch (Throwable t) {
                    Slog.w(TAG, "mirrorSurface(task) threw", t);
                } finally {
                    taskSc.release();
                }
                if (newMirror != null && !newMirror.isValid()) {
                    newMirror.release();
                    newMirror = null;
                }
            }

            // Fall back to the wallpaper layer. WMS creates the mirror at the
            // WallpaperWindowToken level to preserve scale/translation.
            if (newMirror == null) {
                final SurfaceControl wallpaperMirror = wm.mirrorWallpaperSurface(displayId);
                if (wallpaperMirror != null && wallpaperMirror.isValid()) {
                    newMirror = wallpaperMirror;
                    isWallpaper = true;
                } else if (wallpaperMirror != null) {
                    wallpaperMirror.release();
                }
            }

            if (newMirror == null) {
                // Neither target available — tear down and signal.
                tearDownMirror();
                deliverUnavailableToAll(WaferBackdropManager.REASON_NO_TARGET);
                return;
            }

            // Reparent the new mirror under the VD root, replacing any previous one.
            final SurfaceControl vdRoot = wm.getDisplaySurfaceControl(virtualDisplayId);
            if (vdRoot == null) {
                newMirror.release();
                scheduleRetry(attempt, "getDisplaySurfaceControl");
                return;
            }
            final SurfaceControl previousMirror = mirrorSc;
            mirrorSc = newMirror;
            mirrorIsWallpaper = isWallpaper;
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                if (previousMirror != null) {
                    t.reparent(previousMirror, null);
                }
                t.reparent(newMirror, vdRoot)
                        .setLayer(newMirror, Integer.MAX_VALUE)
                        .setPosition(newMirror, 0f, 0f)
                        .show(newMirror)
                        .apply();
            } finally {
                vdRoot.release();
            }
            if (previousMirror != null) {
                try { previousMirror.release(); } catch (Throwable ignored) {}
            }
            Slog.d(TAG, "mirror attached: isWallpaper=" + isWallpaper
                    + " attempt=" + attempt + " vdId=" + virtualDisplayId);
        }

        private void scheduleRetry(int attempt, String what) {
            if (attempt < MAX_SETUP_ATTEMPTS) {
                Slog.w(TAG, what + " failed on attempt " + attempt + "; retrying");
                final int next = attempt + 1;
                handler.postDelayed(() -> pointMirrorAtBestTarget(next),
                        SETUP_RETRY_DELAY_MS);
                return;
            }
            Slog.e(TAG, what + " failed after " + MAX_SETUP_ATTEMPTS + " attempts");
            deliverUnavailableToAll(WaferBackdropManager.REASON_CAPTURE_FAILED);
        }

        private void tearDownMirror() {
            if (mirrorSc != null) {
                try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                    t.reparent(mirrorSc, null).apply();
                }
                try { mirrorSc.release(); } catch (Throwable ignored) {}
                mirrorSc = null;
            }
            mirrorIsWallpaper = false;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            if (destroyed) return;

            final Image image;
            try {
                image = reader.acquireLatestImage();
            } catch (Throwable t) {
                Slog.w(TAG, "acquireLatestImage threw", t);
                return;
            }
            if (image == null) return;

            final HardwareBuffer buffer;
            try {
                buffer = image.getHardwareBuffer();
            } catch (Throwable t) {
                Slog.w(TAG, "getHardwareBuffer threw", t);
                image.close();
                return;
            }
            if (buffer == null) {
                image.close();
                return;
            }

            // Rotate: the image we just acquired becomes currentImage; the old
            // currentImage becomes previousImage; the old previousImage (two
            // frames ago) is closed so its slot returns to the ImageReader.
            // Only advance the rotation when we actually got a new frame, so a
            // spurious callback or a drop doesn't leak the in-flight Image.
            if (previousImage != null) {
                try { previousImage.close(); } catch (Throwable ignored) {}
            }
            previousImage = currentImage;
            currentImage = image;

            final int w = image.getWidth();
            final int h = image.getHeight();
            final long presentTimeNs = image.getTimestamp();

            final Session[] active;
            synchronized (mLock) {
                if (mSessions.isEmpty()) {
                    // No listeners; just keep currentImage alive for the next
                    // rotation so the ImageReader doesn't starve.
                    return;
                }
                active = mSessions.values().stream()
                        .filter(s -> !s.paused && s.displayId == displayId)
                        .toArray(Session[]::new);
            }

            // Fan out to every session. The oneway AIDL marshalling dups the
            // HardwareBuffer into each client process, so each client holds
            // its own independent ref. On the service side, the backing Image
            // stays alive in the currentImage/previousImage rotation, which
            // guarantees the underlying AHardwareBuffer isn't recycled while
            // clients are still sampling — satisfying the "valid until next
            // onBufferAvailable" contract.
            for (Session s : active) {
                final long seq = ++s.frameSeq;
                try {
                    s.callback.onBufferAvailable(buffer, seq, presentTimeNs, w, h);
                } catch (RemoteException e) {
                    Slog.w(TAG, "onBufferAvailable failed; tearing down session", e);
                    stopSessionInternal(s.token);
                }
            }
        }

        void destroy() {
            if (destroyed) return;
            destroyed = true;
            tearDownMirror();
            if (virtualDisplay != null) {
                try { virtualDisplay.release(); } catch (Throwable ignored) {}
                virtualDisplay = null;
            }
            if (imageReader != null) {
                try { imageReader.close(); } catch (Throwable ignored) {}
                imageReader = null;
            }
            if (currentImage != null) {
                try { currentImage.close(); } catch (Throwable ignored) {}
                currentImage = null;
            }
            if (previousImage != null) {
                try { previousImage.close(); } catch (Throwable ignored) {}
                previousImage = null;
            }
            Slog.i(TAG, "MirrorPipeline destroyed: displayId=" + displayId);
        }
    }

    // ---------------- Task stack listener ----------------

    private void registerTaskStackListenerLocked() {
        if (mTaskStackListener != null) return;
        if (mAtmInternal == null) {
            Slog.w(TAG, "ATM unavailable; TaskStackListener not registered");
            return;
        }
        mTaskStackListener = new TaskStackListenerImpl();
        mAtmInternal.registerTaskStackListener(mTaskStackListener);
    }

    private void unregisterTaskStackListenerLocked() {
        if (mTaskStackListener == null) return;
        if (mAtmInternal != null) {
            try {
                mAtmInternal.unregisterTaskStackListener(mTaskStackListener);
            } catch (Throwable ignored) {
            }
        }
        mTaskStackListener = null;
    }

    private final class TaskStackListenerImpl extends TaskStackListener {
        @Override
        public void onTaskMovedToFront(RunningTaskInfo taskInfo) {
            repointCurrentMirror();
        }

        @Override
        public void onTaskStackChanged() {
            // Task removed / keyguard transition etc. — re-evaluate target.
            repointCurrentMirror();
        }

        private void repointCurrentMirror() {
            final MirrorPipeline pipeline;
            synchronized (mLock) {
                pipeline = mMirrorPipeline;
            }
            if (pipeline != null && !pipeline.destroyed) {
                pipeline.handler.post(() -> pipeline.pointMirrorAtBestTarget(0));
            }
        }
    }

    // ---------------- Helpers ----------------

    private void deliverUnavailableToAll(int reason) {
        final Session[] active;
        synchronized (mLock) {
            active = mSessions.values().stream()
                    .filter(s -> !s.paused)
                    .toArray(Session[]::new);
        }
        for (Session s : active) {
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
            pw.println("WaferBackdropCaptureService (Phase 2 — mirror pipeline)");
            pw.println("  active sessions: " + mSessions.size());
            for (Session s : mSessions.values()) {
                pw.println("    token=" + s.token
                        + " displayId=" + s.displayId
                        + " frameSeq=" + s.frameSeq
                        + " paused=" + s.paused);
            }
            pw.println("  captureThread: "
                    + (mCaptureThread != null ? "running" : "stopped"));
            pw.println("  taskStackListener: "
                    + (mTaskStackListener != null ? "registered" : "unregistered"));
            final MirrorPipeline p = mMirrorPipeline;
            if (p != null) {
                pw.println("  mirrorPipeline:");
                pw.println("    displayId=" + p.displayId
                        + " vdId=" + p.virtualDisplayId
                        + " size=" + p.width + "x" + p.height
                        + " vdLayersDetached=" + p.vdLayersDetached
                        + " mirrorAttached=" + (p.mirrorSc != null)
                        + " target=" + (p.mirrorSc == null ? "none"
                                : (p.mirrorIsWallpaper ? "wallpaper" : "task"))
                        + " destroyed=" + p.destroyed);
            } else {
                pw.println("  mirrorPipeline: inactive");
            }
        }
    }

    /**
     * {@code adb shell cmd wafer_backdrop ...}
     *
     * Subcommands:
     *   dump-frame &lt;path&gt; [displayId]   synchronously captures the topmost Task
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
