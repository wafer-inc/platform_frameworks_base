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

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Owns a {@link WaferGlassController} for the notification shade window and feeds it the
 * current static wallpaper bitmap as the blur source.
 *
 * <p>SystemUI doesn't host its own wallpaper view — the wallpaper is rendered by a
 * separate {@code WallpaperWindowToken} and shown through the shade window via
 * {@code FLAG_SHOW_WALLPAPER}. The wafer-glass library's per-element blur pipeline
 * needs an actual {@link RenderNode} of the source content, so we load the wallpaper
 * bitmap via {@link WallpaperManager} (which the launcher does too) and bake it into a
 * screen-sized {@link RenderNode}. Notification rows' {@link com.wafer.glass.WaferGlassDelegate}
 * instances discover the controller via {@code WaferGlass.find()} and start drawing real
 * per-row backdrop blur sampled from the bitmap at their window position.
 *
 * <p><b>Live wallpapers:</b> {@link WallpaperManager#getDrawable()} returns the
 * <em>static thumbnail</em> for a true animated live wallpaper. That's slightly stale
 * compared to the actual animation but it's still better than nothing — we'd rather
 * show a real (if frozen) sample of the wallpaper than the flat tint fallback. We
 * deliberately do NOT use {@code WallpaperManager.getWallpaperInfo() != null} as a
 * live-wallpaper check because Pixel's static wallpaper is served via
 * {@code com.android.systemui.wallpapers.ImageWallpaper} (a {@link android.service.wallpaper.WallpaperService})
 * and would get incorrectly flagged. Instead we just attempt the load; if anything
 * goes wrong (no permission, no bitmap, OOM, …) the controller's source stays unset
 * and the row delegates use their flat-glass painters automatically.
 *
 * <p><b>Threading:</b> bitmap loads happen on a background executor; controller
 * publishing happens on the main thread.
 *
 * <p><b>Lifecycle:</b> tied to {@link NotificationShadeWindowView#onAttachedToWindow()}
 * and {@link NotificationShadeWindowView#onDetachedFromWindow()}.
 */
public final class WaferShadeGlassSource implements WaferGlassController.SourceRefresher {

    private static final String TAG = "WaferShadeGlassSource";

    @NonNull private final View mHost;
    @NonNull private final Context mAppContext;
    @NonNull private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    @NonNull private final Executor mBackgroundExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "WaferShadeGlassLoader");
                t.setDaemon(true);
                return t;
            });

    @NonNull private final WaferGlassController mController;

    @Nullable private WallpaperManager mWallpaperManager;
    @Nullable private WallpaperManager.OnColorsChangedListener mWallpaperListener;
    @Nullable private RenderNode mSourceNode;
    @Nullable private Bitmap mWallpaperBitmap;
    private int mSourceWidth;
    private int mSourceHeight;
    private boolean mAttached;
    private final int[] mZeroLoc = new int[] {0, 0};

    // Wafer backdrop live-source path. When a frame arrives we record it into
    // mLiveNode and publish it as the glass source. On onBackdropUnavailable
    // we drop back to the wallpaper path (by re-publishing mSourceNode).
    @Nullable private WaferBackdropManager mBackdropManager;
    @Nullable private WaferBackdropManager.Session mBackdropSession;
    @Nullable private RenderNode mLiveNode;
    private boolean mLiveSourceActive;

    public WaferShadeGlassSource(@NonNull View host) {
        mHost = host;
        mAppContext = host.getContext().getApplicationContext();
        mController = new WaferGlassController(mAppContext);
        // Standalone RenderNodes can have their native display list dropped by HWUI;
        // when that happens the controller asks us to re-record from the cached bitmap.
        mController.setSourceRefresher(this);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called from {@link WaferGlassController#ensureSourceReady} on the main thread
     * when a draw notices the source RenderNode has lost its display list. Cheap if
     * still-valid (single check), re-records from the cached wallpaper bitmap if not.
     */
    @MainThread
    @Override
    public void refreshSourceIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (mSourceNode == null || mWallpaperBitmap == null) return;
        if (mSourceNode.hasDisplayList()) return;
        Log.i(TAG, "refreshSourceIfNeeded: re-recording dropped source display list");
        recordSource();
    }

    @MainThread
    public void onAttachedToWindow() {
        if (mAttached) return;
        mAttached = true;
        // Tag the controller on the host so descendant WaferGlassDelegates can find it
        // before the wallpaper bitmap finishes loading. They'll fall back to flat-glass
        // painting until isReady() flips true on the first source publish.
        WaferGlass.attachController(mHost, mController);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.i(TAG, "onAttachedToWindow: SDK<S, glass disabled");
            return;
        }

        mWallpaperManager = mAppContext.getSystemService(WallpaperManager.class);
        if (mWallpaperManager == null) {
            Log.w(TAG, "onAttachedToWindow: no WallpaperManager service");
            return;
        }

        // Listen for wallpaper changes (theme switch, user rotates wallpaper, etc.)
        // and re-load the bitmap. The Handler dispatches the callback to the main
        // thread; the actual reload kicks off on the background executor.
        mWallpaperListener = (colors, which) -> {
            Log.i(TAG, "wallpaper colors changed (which=" + which + "); reloading");
            mBackgroundExecutor.execute(this::loadWallpaperOnBackground);
        };
        mWallpaperManager.addOnColorsChangedListener(mWallpaperListener, mMainHandler);

        Log.i(TAG, "onAttachedToWindow: kicking off initial wallpaper load");
        mBackgroundExecutor.execute(this::loadWallpaperOnBackground);

        // Open a backdrop capture session. Frames replace the wallpaper source
        // while delivery succeeds; on unavailable we fall back to wallpaper.
        mBackdropManager = mAppContext.getSystemService(WaferBackdropManager.class);
        if (mBackdropManager != null) {
            final Display display = mHost.getDisplay();
            final int displayId = display != null ? display.getDisplayId() : 0;
            try {
                mBackdropSession = mBackdropManager.startSession(displayId, mMainHandler,
                        mBackdropListener);
                Log.i(TAG, "onAttachedToWindow: backdrop session started on display "
                        + displayId);
            } catch (Throwable t) {
                Log.w(TAG, "failed to start backdrop session; staying on wallpaper path", t);
                mBackdropSession = null;
            }
        } else {
            Log.w(TAG, "WaferBackdropManager unavailable; staying on wallpaper path");
        }
    }

    @MainThread
    public void onDetachedFromWindow() {
        if (!mAttached) return;
        mAttached = false;
        if (mWallpaperManager != null && mWallpaperListener != null) {
            mWallpaperManager.removeOnColorsChangedListener(mWallpaperListener);
        }
        mWallpaperListener = null;
        mWallpaperManager = null;
        if (mBackdropSession != null) {
            try { mBackdropSession.close(); } catch (Throwable ignored) {}
            mBackdropSession = null;
        }
        mBackdropManager = null;
        mLiveSourceActive = false;
        if (mLiveNode != null) {
            mLiveNode.discardDisplayList();
            mLiveNode = null;
        }
        WaferGlass.detachController(mHost, mController);
        mController.release();
        if (mSourceNode != null) {
            mSourceNode.discardDisplayList();
            mSourceNode = null;
        }
        if (mWallpaperBitmap != null && !mWallpaperBitmap.isRecycled()) {
            mWallpaperBitmap.recycle();
        }
        mWallpaperBitmap = null;
    }

    @MainThread
    public void onSizeChanged(int w, int h) {
        if (!mAttached) return;
        if (w == mSourceWidth && h == mSourceHeight) return;
        mSourceWidth = w;
        mSourceHeight = h;
        // Re-record at the new size so the bitmap stays scaled to the window.
        if (mWallpaperBitmap != null) {
            recordSource();
        }
    }

    /** Returns the controller. Mostly for tests / debug. */
    @NonNull
    public WaferGlassController getController() {
        return mController;
    }

    private void loadWallpaperOnBackground() {
        WallpaperManager wm = mWallpaperManager;
        if (wm == null) return;
        Bitmap owned = null;
        Drawable d = null;
        try {
            d = wm.getDrawable();
            if (d instanceof BitmapDrawable) {
                Bitmap shared = ((BitmapDrawable) d).getBitmap();
                if (shared != null && !shared.isRecycled()) {
                    // CRITICAL: WallpaperManager hands out a shared/cached Bitmap and
                    // recycles it on its own schedule (theme/colors change, memory
                    // pressure). Holding the shared reference is unsafe — we must take
                    // a private copy that we own end-to-end. Without this, the source
                    // RenderNode's recorded drawBitmap() either silently produces an
                    // empty draw (causing the "flat black tint" bug) or throws
                    // "trying to use a recycled bitmap" inside HWUI on the next frame.
                    Bitmap.Config cfg = shared.getConfig() != null
                            ? shared.getConfig() : Bitmap.Config.ARGB_8888;
                    owned = shared.copy(cfg, false /* mutable */);
                }
            } else if (d != null && d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0) {
                // Drawable wasn't a BitmapDrawable; rasterize it ourselves. The output
                // bitmap is already privately owned by us — no copy needed.
                owned = Bitmap.createBitmap(
                        d.getIntrinsicWidth(),
                        d.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(owned);
                d.setBounds(0, 0, owned.getWidth(), owned.getHeight());
                d.draw(c);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to load wallpaper bitmap; falling back to flat glass", e);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Out of memory loading wallpaper; falling back to flat glass", e);
        }
        if (owned != null) {
            Log.i(TAG, "wallpaper bitmap loaded (owned copy): "
                    + owned.getWidth() + "x" + owned.getHeight()
                    + " drawableClass=" + (d != null ? d.getClass().getSimpleName() : "null"));
        } else {
            Log.w(TAG, "wallpaper load returned no bitmap; drawable="
                    + (d != null ? d.getClass().getSimpleName() : "null")
                    + " — falling back to flat glass");
        }
        final Bitmap finalBitmap = owned;
        mMainHandler.post(() -> onBitmapLoaded(finalBitmap));
    }

    @MainThread
    private void onBitmapLoaded(@Nullable Bitmap bitmap) {
        if (!mAttached || bitmap == null) {
            // Either we detached during the background load, or the load failed.
            // Either way, recycle the orphaned copy so we don't leak it.
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            return;
        }
        // Replacing an existing owned copy (e.g. wallpaper changed). Recycle the old
        // one before dropping the reference — we own it, nobody else will.
        Bitmap previous = mWallpaperBitmap;
        mWallpaperBitmap = bitmap;
        if (previous != null && previous != bitmap && !previous.isRecycled()) {
            previous.recycle();
        }
        if (mSourceWidth <= 0 || mSourceHeight <= 0) {
            // Size not known yet — wait for onSizeChanged.
            return;
        }
        recordSource();
    }

    @MainThread
    private void recordSource() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Bitmap bitmap = mWallpaperBitmap;
        if (bitmap == null || mSourceWidth <= 0 || mSourceHeight <= 0) return;
        if (bitmap.isRecycled()) {
            // Defence in depth: we should own this bitmap and never recycle it
            // ourselves, but if anything ever slips through don't crash the draw
            // thread — drop the reference and let the next reload re-fetch.
            Log.w(TAG, "recordSource: wallpaper bitmap was recycled; clearing & "
                    + "scheduling reload");
            mWallpaperBitmap = null;
            mBackgroundExecutor.execute(this::loadWallpaperOnBackground);
            return;
        }
        Log.i(TAG, "recordSource: window=" + mSourceWidth + "x" + mSourceHeight
                + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight());

        if (mSourceNode == null) {
            mSourceNode = new RenderNode("WaferShadeGlassSource");
        }
        mSourceNode.setPosition(0, 0, mSourceWidth, mSourceHeight);

        // Center-crop the bitmap into the screen rect (matches the typical static
        // wallpaper rendering on phones — fill the screen, no letterboxing).
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        float scale = Math.max(mSourceWidth / bw, mSourceHeight / bh);
        float drawW = bw * scale;
        float drawH = bh * scale;
        float dx = (mSourceWidth - drawW) * 0.5f;
        float dy = (mSourceHeight - drawH) * 0.5f;

        RecordingCanvas rc = mSourceNode.beginRecording();
        try {
            rc.save();
            rc.translate(dx, dy);
            rc.scale(scale, scale);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            rc.drawBitmap(bitmap, 0f, 0f, paint);
            rc.restore();
        } finally {
            mSourceNode.endRecording();
        }

        if (mLiveSourceActive) {
            // Live backdrop is driving the controller — don't overwrite it with
            // the wallpaper source. We still keep mSourceNode recorded so that
            // onBackdropUnavailable has something to fall back to immediately.
            return;
        }
        mController.setSource(mSourceNode, mSourceWidth, mSourceHeight);
        // The shade window is full-screen and the wallpaper is anchored to the window
        // origin, so the source location in window is (0, 0). This matches the math
        // notification rows do in WaferGlassDelegate.drawBlurredBackdrop().
        mController.setSourceLocationInWindow(mZeroLoc);
        // Force the next frame to re-blur from the new source.
        mController.invalidateSource();
        Log.i(TAG, "recordSource[done]: sourceNode hasDisplayList="
                + mSourceNode.hasDisplayList()
                + " bitmap recycled=" + bitmap.isRecycled()
                + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " window=" + mSourceWidth + "x" + mSourceHeight
                + " scale=" + scale + " dx=" + dx + " dy=" + dy);
    }

    private final WaferBackdropManager.Listener mBackdropListener =
            new WaferBackdropManager.Listener() {
        @Override
        public void onBackdropFrame(@NonNull HardwareBuffer buffer, long frameSeq,
                                    long presentTimeNs, int srcWidthPx, int srcHeightPx) {
            if (!mAttached) return;
            publishLiveFrame(buffer, srcWidthPx, srcHeightPx);
        }

        @Override
        public void onBackdropUnavailable(int reason) {
            if (!mAttached) return;
            Log.i(TAG, "backdrop unavailable reason=" + reason
                    + "; falling back to wallpaper");
            mLiveSourceActive = false;
            // Re-publish the wallpaper source if we have one cached.
            if (mSourceNode != null && mSourceWidth > 0 && mSourceHeight > 0) {
                mController.setSource(mSourceNode, mSourceWidth, mSourceHeight);
                mController.setSourceLocationInWindow(mZeroLoc);
                mController.invalidateSource();
            }
        }
    };

    /**
     * Wraps the incoming backdrop {@link HardwareBuffer} in a reused {@link RenderNode}
     * and publishes it as the glass source. This is the Phase 1 live path; Phase 3
     * will swap in a half-res downsampled buffer with the same shape.
     */
    @MainThread
    private void publishLiveFrame(@NonNull HardwareBuffer buffer, int srcW, int srcH) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (srcW <= 0 || srcH <= 0) return;

        // wrapHardwareBuffer is zero-copy; the returned Bitmap shares pixels with
        // the buffer. The service guarantees it's valid until the next frame.
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
        // Size the RenderNode to the shade window — existing per-row sampling
        // math uses window coordinates against the source rect, so keeping it at
        // window size means rows don't need to change. Scale the hw bitmap into
        // that rect (centre-crop to match the wallpaper path's feel).
        final int winW = mSourceWidth > 0 ? mSourceWidth : srcW;
        final int winH = mSourceHeight > 0 ? mSourceHeight : srcH;
        mLiveNode.setPosition(0, 0, winW, winH);

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

        mLiveSourceActive = true;
        mController.setSource(mLiveNode, winW, winH);
        mController.setSourceLocationInWindow(mZeroLoc);
        mController.invalidateSource();
    }
}
