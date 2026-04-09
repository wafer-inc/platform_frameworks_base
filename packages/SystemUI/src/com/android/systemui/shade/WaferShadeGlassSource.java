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
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
public final class WaferShadeGlassSource {

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

    public WaferShadeGlassSource(@NonNull View host) {
        mHost = host;
        mAppContext = host.getContext().getApplicationContext();
        mController = new WaferGlassController(mAppContext);
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
        WaferGlass.detachController(mHost, mController);
        mController.release();
        if (mSourceNode != null) {
            mSourceNode.discardDisplayList();
            mSourceNode = null;
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
        Bitmap bitmap = null;
        Drawable d = null;
        try {
            d = wm.getDrawable();
            if (d instanceof BitmapDrawable) {
                bitmap = ((BitmapDrawable) d).getBitmap();
            } else if (d != null && d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0) {
                bitmap = Bitmap.createBitmap(
                        d.getIntrinsicWidth(),
                        d.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(bitmap);
                d.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
                d.draw(c);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to load wallpaper bitmap; falling back to flat glass", e);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Out of memory loading wallpaper; falling back to flat glass", e);
        }
        if (bitmap != null) {
            Log.i(TAG, "wallpaper bitmap loaded: " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " drawableClass=" + (d != null ? d.getClass().getSimpleName() : "null"));
        } else {
            Log.w(TAG, "wallpaper load returned no bitmap; drawable="
                    + (d != null ? d.getClass().getSimpleName() : "null")
                    + " — falling back to flat glass");
        }
        final Bitmap finalBitmap = bitmap;
        mMainHandler.post(() -> onBitmapLoaded(finalBitmap));
    }

    @MainThread
    private void onBitmapLoaded(@Nullable Bitmap bitmap) {
        if (!mAttached || bitmap == null) return;
        mWallpaperBitmap = bitmap;
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
}
