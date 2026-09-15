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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * Client-side facade for the Wafer backdrop capture service. SystemUI uses this to
 * receive live {@link HardwareBuffer} frames of the display contents below the
 * shade (currently Phase 1: topmost non-SystemUI Task at ~10 fps).
 *
 * <p>Acquire via {@code context.getSystemService(WaferBackdropManager.class)}. Must
 * hold {@code com.wafer.permission.CAPTURE_BACKDROP} (signature|privileged).
 *
 * @hide
 */
@SystemService(Context.WAFER_BACKDROP_CAPTURE_SERVICE)
public final class WaferBackdropManager {

    private static final String TAG = "WaferBackdropManager";

    /** Secure (DRM) layer on screen — no backdrop available until it leaves. */
    public static final int REASON_SECURE_LAYER = 1;
    /** Display is off. */
    public static final int REASON_DISPLAY_OFF = 2;
    /** No target task on the display (home-screen idle, etc.). */
    public static final int REASON_NO_TARGET = 3;
    /** ScreenCapture returned null or otherwise failed. */
    public static final int REASON_CAPTURE_FAILED = 4;
    /** Service tore down the session (e.g. client binder died). */
    public static final int REASON_SESSION_ENDED = 5;

    /** Callback for live backdrop frames. */
    public interface Listener {
        /**
         * A new backdrop buffer is ready. Valid until the next callback for this
         * session; do NOT close it.
         */
        void onBackdropFrame(@NonNull HardwareBuffer buffer, long frameSeq,
                             long presentTimeNs, int sourceWidthPx, int sourceHeightPx);

        /** No backdrop available. See {@code REASON_*}. */
        void onBackdropUnavailable(int reason);
    }

    private final Context mContext;
    private final IWaferBackdropCaptureService mService;

    /** @hide */
    public WaferBackdropManager(@NonNull Context context,
                                @NonNull IWaferBackdropCaptureService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Start a capture session on the given display. Frames are delivered on
     * {@code handler}. Close the returned {@link Session} to stop.
     */
    @NonNull
    public Session startSession(int displayId, @NonNull Handler handler,
                                @NonNull Listener listener) {
        CallbackStub cb = new CallbackStub(handler, listener);
        try {
            IBinder token = mService.startSession(displayId, cb);
            return new Session(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Active capture session. */
    public final class Session implements AutoCloseable {
        private final IBinder mToken;
        private boolean mClosed;

        private Session(IBinder token) {
            mToken = token;
        }

        public void pause() {
            if (mClosed) return;
            try {
                mService.pauseSession(mToken);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        public void resume() {
            if (mClosed) return;
            try {
                mService.resumeSession(mToken);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        @Override
        public void close() {
            if (mClosed) return;
            mClosed = true;
            try {
                mService.stopSession(mToken);
            } catch (RemoteException e) {
                // Service died — session is already gone, nothing to do.
                Log.w(TAG, "stopSession failed: " + e);
            }
        }
    }

    private static final class CallbackStub extends IWaferBackdropCallback.Stub {
        private final Handler mHandler;
        private final Listener mListener;

        CallbackStub(Handler handler, Listener listener) {
            mHandler = handler;
            mListener = listener;
        }

        @Override
        public void onBufferAvailable(HardwareBuffer buffer, long frameSeq,
                                      long presentTimeNs,
                                      int sourceWidthPx, int sourceHeightPx) {
            mHandler.post(() -> {
                try {
                    mListener.onBackdropFrame(buffer, frameSeq, presentTimeNs,
                            sourceWidthPx, sourceHeightPx);
                } catch (Throwable t) {
                    Log.e(TAG, "listener threw in onBackdropFrame", t);
                }
            });
        }

        @Override
        public void onBackdropUnavailable(int reason) {
            mHandler.post(() -> {
                try {
                    mListener.onBackdropUnavailable(reason);
                } catch (Throwable t) {
                    Log.e(TAG, "listener threw in onBackdropUnavailable", t);
                }
            });
        }
    }
}
