/*
 * Copyright (C) 2026 The Wafer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.shade;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wafer.backdrop.WaferBackdropManager;
import com.wafer.glass.WaferGlass;
import com.wafer.glass.WaferGlassController;

/**
 * Owns a {@link WaferGlassController} for the notification shade window and feeds it a live
 * backdrop source via {@link WaferBackdropManager}.
 *
 * <p>The backdrop service delivers {@link HardwareBuffer} frames of whatever is composed
 * below the shade — the foreground app, or the wallpaper when no app is suitable
 * (lockscreen, DRM content, etc.). The service handles the task-vs-wallpaper target
 * resolution internally, so this class doesn't need a wallpaper-loading fallback of
 * its own: clients always receive frames as long as the service is healthy.
 *
 * <p>{@link WaferGlassController.SourceRefresher} is still implemented because HWUI
 * may drop the standalone {@link RenderNode}'s display list under memory pressure; the
 * refresher re-records the last delivered buffer on demand.
 *
 * <p><b>Threading:</b> all controller publishing happens on the main thread — the
 * backdrop listener is bound to the main handler.
 *
 * <p><b>Lifecycle:</b> tied to {@link NotificationShadeWindowView#onAttachedToWindow()}
 * and {@link NotificationShadeWindowView#onDetachedFromWindow()}.
 */
public final class WaferShadeGlassSource implements WaferGlassController.SourceRefresher {

    private static final String TAG = "WaferShadeGlassSource";

    @NonNull private final View mHost;
    @NonNull private final Context mAppContext;
    @NonNull private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @NonNull private final WaferGlassController mController;

    private int mSourceWidth;
    private int mSourceHeight;
    private boolean mAttached;
    private final int[] mZeroLoc = new int[] {0, 0};

    @Nullable private WaferBackdropManager mBackdropManager;
    @Nullable private WaferBackdropManager.Session mBackdropSession;
    @Nullable private RenderNode mLiveNode;
    /** Last delivered buffer; retained so {@link #refreshSourceIfNeeded} can re-record. */
    @Nullable private HardwareBuffer mLastBuffer;
    private int mLastSrcW;
    private int mLastSrcH;

    public WaferShadeGlassSource(@NonNull View host) {
        mHost = host;
        mAppContext = host.getContext().getApplicationContext();
        mController = new WaferGlassController(mAppContext);
        mController.setSourceRefresher(this);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called from {@link WaferGlassController#ensureSourceReady} on the main thread
     * when a draw notices the source RenderNode has lost its display list. Cheap if
     * still-valid (single check); re-records from the cached HardwareBuffer if not.
     */
    @MainThread
    @Override
    public void refreshSourceIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (mLiveNode == null || mLastBuffer == null) return;
        if (mLiveNode.hasDisplayList()) return;
        Log.i(TAG, "refreshSourceIfNeeded: re-recording dropped live display list");
        recordLive(mLastBuffer, mLastSrcW, mLastSrcH);
    }

    @MainThread
    public void onAttachedToWindow() {
        if (mAttached) return;
        mAttached = true;
        WaferGlass.attachController(mHost, mController);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.i(TAG, "onAttachedToWindow: SDK<S, glass disabled");
            return;
        }

        mBackdropManager = mAppContext.getSystemService(WaferBackdropManager.class);
        if (mBackdropManager == null) {
            Log.w(TAG, "WaferBackdropManager unavailable; glass will show flat tint only");
            return;
        }
        final Display display = mHost.getDisplay();
        final int displayId = display != null ? display.getDisplayId() : 0;
        try {
            mBackdropSession = mBackdropManager.startSession(displayId, mMainHandler,
                    mBackdropListener);
            Log.i(TAG, "onAttachedToWindow: backdrop session started on display "
                    + displayId);
        } catch (Throwable t) {
            Log.w(TAG, "failed to start backdrop session; glass will show flat tint", t);
            mBackdropSession = null;
        }
    }

    @MainThread
    public void onDetachedFromWindow() {
        if (!mAttached) return;
        mAttached = false;
        if (mBackdropSession != null) {
            try { mBackdropSession.close(); } catch (Throwable ignored) {}
            mBackdropSession = null;
        }
        mBackdropManager = null;
        if (mLiveNode != null) {
            mLiveNode.discardDisplayList();
            mLiveNode = null;
        }
        mLastBuffer = null;
        WaferGlass.detachController(mHost, mController);
        mController.release();
    }

    @MainThread
    public void onSizeChanged(int w, int h) {
        if (!mAttached) return;
        mSourceWidth = w;
        mSourceHeight = h;
    }

    /** Returns the controller. Mostly for tests / debug. */
    @NonNull
    public WaferGlassController getController() {
        return mController;
    }

    private final WaferBackdropManager.Listener mBackdropListener =
            new WaferBackdropManager.Listener() {
        @Override
        public void onBackdropFrame(@NonNull HardwareBuffer buffer, long frameSeq,
                                    long presentTimeNs, int srcWidthPx, int srcHeightPx) {
            if (!mAttached) return;
            recordLive(buffer, srcWidthPx, srcHeightPx);
        }

        @Override
        public void onBackdropUnavailable(int reason) {
            if (!mAttached) return;
            // The service normally falls back to wallpaper internally, so this path
            // only fires in degenerate cases (no task AND no wallpaper, or pipeline
            // setup failure). Drop the live source so delegates fall back to their
            // flat-tint painters — the simplest visually-acceptable fallback.
            Log.i(TAG, "backdrop unavailable reason=" + reason + "; flat-tint fallback");
            mLastBuffer = null;
            if (mLiveNode != null) {
                mLiveNode.discardDisplayList();
            }
            mController.invalidateSource();
        }
    };

    /**
     * Wraps the incoming backdrop {@link HardwareBuffer} in a reused {@link RenderNode}
     * and publishes it as the glass source.
     */
    @MainThread
    private void recordLive(@NonNull HardwareBuffer buffer, int srcW, int srcH) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (srcW <= 0 || srcH <= 0) return;

        final Bitmap hwBitmap;
        try {
            hwBitmap = Bitmap.wrapHardwareBuffer(buffer,
                    ColorSpace.get(ColorSpace.Named.SRGB));
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "wrapHardwareBuffer failed", e);
            return;
        }
        if (hwBitmap == null) return;

        if (mLiveNode == null) {
            mLiveNode = new RenderNode("WaferShadeGlassLive");
        }
        final int winW = mSourceWidth > 0 ? mSourceWidth : srcW;
        final int winH = mSourceHeight > 0 ? mSourceHeight : srcH;
        mLiveNode.setPosition(0, 0, winW, winH);

        // Center-crop the full-res buffer into the shade window rect. Per-row
        // sampling math in WaferGlassDelegate uses window coordinates against the
        // source's full-res extent, so keeping the node at window size means rows
        // need no changes to track their position behind the content.
        final float scale = Math.max((float) winW / srcW, (float) winH / srcH);
        final float drawW = srcW * scale;
        final float drawH = srcH * scale;
        final float dx = (winW - drawW) * 0.5f;
        final float dy = (winH - drawH) * 0.5f;

        final RecordingCanvas rc = mLiveNode.beginRecording();
        try {
            rc.save();
            rc.translate(dx, dy);
            rc.scale(scale, scale);
            rc.drawBitmap(hwBitmap, 0f, 0f, null);
            rc.restore();
        } finally {
            mLiveNode.endRecording();
        }

        mLastBuffer = buffer;
        mLastSrcW = srcW;
        mLastSrcH = srcH;

        mController.setSource(mLiveNode, winW, winH);
        mController.setSourceLocationInWindow(mZeroLoc);
        mController.invalidateSource();
    }
}
