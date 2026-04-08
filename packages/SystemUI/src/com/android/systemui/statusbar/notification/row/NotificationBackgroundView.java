/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.util.ColorUtilKt.hexColorString;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.util.ContrastColorUtil;
import com.android.settingslib.Utils;
import com.android.systemui.Dumpable;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.shared.NotificationAddXOnHoverToDismiss;
import com.android.systemui.util.DrawableDumpKt;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * A view that can be used for both the dimmed and normal background of an notification.
 */
public class NotificationBackgroundView extends View implements Dumpable,
        ExpandableNotificationRow.DismissButtonTargetVisibilityListener {

    private final boolean mDontModifyCorners;
    private Drawable mBackground;
    private int mClipTopAmount;
    private int mClipBottomAmount;
    private int mTintColor;
    @Nullable private Integer mRippleColor;
    private final float[] mCornerRadii = new float[8];
    private final float[] mFocusOverlayCornerRadii = new float[8];
    private float mFocusOverlayStroke = 0;
    private boolean mBottomIsRounded;
    private boolean mBottomAmountClips = true;
    private int mActualHeight = -1;
    private int mActualWidth = -1;
    private boolean mExpandAnimationRunning;
    private int mExpandAnimationWidth = -1;
    private int mExpandAnimationHeight = -1;
    private int mDrawableAlpha = 255;
    private final ColorStateList mLightColoredStatefulColors;
    private final ColorStateList mDarkColoredStatefulColors;
    private final int mNormalColor;
    private final int convexR = 9;
    private final int concaveR = 22;

    // True only if the dismiss button is visible.
    private boolean mDrawDismissButtonCutout = false;

    // Wafer glass surface (drawn directly in onDraw to mimic WaferLauncher's
    // GlassCard recipe over the already-blurred shade backdrop from Phase 02).
    private final Paint mWaferShadowPaint;
    private final Paint mWaferFillPaint;
    private final Paint mWaferHighlightPaint;
    private final Paint mWaferBorderPaint;
    private final RectF mWaferShadowRect = new RectF();
    private final RectF mWaferCardRect = new RectF();
    private final RectF mWaferBorderRect = new RectF();
    private final float mWaferShadowOffsetY;
    private final float mWaferBorderWidth;
    private final int mWaferGlassTint;
    // Cached top-highlight gradient. Rebuilt only when card height changes.
    private LinearGradient mWaferHighlightShader;
    private float mWaferHighlightShaderHeight = -1f;
    private final Matrix mWaferHighlightMatrix = new Matrix();

    public NotificationBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDontModifyCorners = getResources().getBoolean(R.bool.config_clipNotificationsToOutline);
        mLightColoredStatefulColors = getResources().getColorStateList(
                R.color.notification_state_color_light);
        mDarkColoredStatefulColors = getResources().getColorStateList(
                R.color.notification_state_color_dark);
        mNormalColor = Utils.getColorAttrDefaultColor(mContext,
                com.android.internal.R.attr.materialColorSurfaceContainerHigh);
        mFocusOverlayStroke = getResources().getDimension(R.dimen.notification_focus_stroke_width);

        // Wafer glass: pre-build all paints once; never allocate in onDraw.
        final float shadowBlur = getResources().getDimension(R.dimen.wafer_shadow_blur);
        mWaferShadowOffsetY = getResources().getDimension(R.dimen.wafer_shadow_offset_y);
        mWaferBorderWidth = getResources().getDimension(R.dimen.wafer_glass_border_width);
        mWaferGlassTint = getResources().getColor(R.color.wafer_glass_tint_dark, null);
        final int waferBorderColor = getResources().getColor(R.color.wafer_glass_border, null);
        final int waferShadowColor = getResources().getColor(R.color.wafer_shadow, null);

        mWaferShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mWaferShadowPaint.setColor(waferShadowColor);
        mWaferShadowPaint.setMaskFilter(new BlurMaskFilter(shadowBlur, BlurMaskFilter.Blur.NORMAL));

        mWaferFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mWaferFillPaint.setStyle(Paint.Style.FILL);
        mWaferFillPaint.setColor(mWaferGlassTint);

        mWaferHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mWaferHighlightPaint.setStyle(Paint.Style.FILL);

        mWaferBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mWaferBorderPaint.setStyle(Paint.Style.STROKE);
        mWaferBorderPaint.setStrokeWidth(mWaferBorderWidth);
        mWaferBorderPaint.setColor(waferBorderColor);
    }

    @Override
    public void onTargetVisibilityChanged(boolean targetVisible) {
        if (NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode()) {
            return;
        }

        if (mDrawDismissButtonCutout != targetVisible) {
            mDrawDismissButtonCutout = targetVisible;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mClipTopAmount + mClipBottomAmount < getActualHeight() || mExpandAnimationRunning) {
            drawWaferShadow(canvas);
            drawWaferGlassSurface(canvas);
            canvas.save();
            if (!mExpandAnimationRunning) {
                canvas.clipRect(0, mClipTopAmount, getWidth(),
                        getActualHeight() - mClipBottomAmount);
            }

            if (!NotificationAddXOnHoverToDismiss.isEnabled()) {
                draw(canvas, mBackground);
                canvas.restore();
                return;
            }

            Rect backgroundBounds = null;
            if (mBackground != null || mDrawDismissButtonCutout) {
                backgroundBounds = calculateBackgroundBounds();
            }

            if (mDrawDismissButtonCutout) {
                canvas.clipPath(calculateDismissButtonCutoutPath(backgroundBounds));
            }

            if (mBackground != null) {
                mBackground.setBounds(backgroundBounds);
                mBackground.draw(canvas);
            }

            canvas.restore();
        }
    }

    /**
     * Wafer glass: draw a soft drop shadow under the notification card before the
     * background draws over it. Uses the live programmatic corner radii so the shadow
     * always matches the row's current rounding (expand/collapse, group children, etc).
     * Allocations are kept out of this path — the paint and rect are pre-built.
     */
    private void drawWaferShadow(Canvas canvas) {
        if (mBackground == null) {
            return;
        }
        final int top = mClipTopAmount;
        final int bottom = getActualHeight() - mClipBottomAmount;
        if (bottom <= top) {
            return;
        }
        mWaferShadowRect.set(0f, top + mWaferShadowOffsetY,
                getWidth(), bottom + mWaferShadowOffsetY);
        final float topR = mCornerRadii[0];
        final float bottomR = mCornerRadii[4];
        // GradientDrawable supports per-corner radii but Canvas.drawRoundRect only
        // takes a single rx/ry, so use the larger of the two — the shadow's blur
        // hides the small mismatch on rows whose corners differ top vs. bottom.
        final float radius = Math.max(topR, bottomR);
        canvas.drawRoundRect(mWaferShadowRect, radius, radius, mWaferShadowPaint);
    }

    /**
     * Wafer glass: paint the layered glass surface (base tint + top highlight
     * gradient + hairline border) directly on the canvas. This mirrors the
     * WaferLauncher GlassCard recipe (see GlassCard.kt:64-126 and
     * GlassMorphism.kt) using only platform Canvas primitives — the launcher's
     * BlurState/RenderNode pipeline is Compose-only and not reachable from
     * SystemUI Views, but Phase 02 already blurs the wallpaper at the shade
     * window level, so the rows sit over a pre-blurred backdrop and only need
     * the surface treatment to read as glass plates.
     *
     * Drawn before the LayerDrawable so the existing state-color overlay
     * (layer 1) and focus-overlay (layer 2) draw on top of the glass surface,
     * preserving press / hover / keyboard-focus feedback.
     */
    private void drawWaferGlassSurface(Canvas canvas) {
        if (mBackground == null) {
            return;
        }
        final int top = mClipTopAmount;
        final int bottom = getActualHeight() - mClipBottomAmount;
        if (bottom <= top) {
            return;
        }
        final float left = 0f;
        final float right = getWidth();
        mWaferCardRect.set(left, top, right, bottom);

        final float topR = mCornerRadii[0];
        final float bottomR = mCornerRadii[4];
        final float radius = Math.max(topR, bottomR);

        // 1. Base tint fill — wafer_glass_tint_dark over the (already-blurred)
        //    shade backdrop. This is what gives the row its "glass plate" body.
        canvas.drawRoundRect(mWaferCardRect, radius, radius, mWaferFillPaint);

        // 2. Top highlight gradient — fakes light catching the upper edge of the
        //    glass plate. Vertical: ~25% white at the very top, fading to fully
        //    transparent by the midpoint of the card. Subtle but enough to make
        //    the card read as a layered surface rather than a flat tint.
        final float cardHeight = mWaferCardRect.height();
        if (cardHeight > 0f) {
            if (mWaferHighlightShader == null || mWaferHighlightShaderHeight != cardHeight) {
                mWaferHighlightShader = new LinearGradient(
                        0f, mWaferCardRect.top,
                        0f, mWaferCardRect.top + cardHeight * 0.5f,
                        0x40FFFFFF, // white @ ~25%
                        0x00FFFFFF, // transparent
                        Shader.TileMode.CLAMP);
                mWaferHighlightShaderHeight = cardHeight;
                mWaferHighlightPaint.setShader(mWaferHighlightShader);
            } else {
                // Same height, but the rect's vertical position may have shifted
                // (clipping changed). Re-translate the shader to match.
                mWaferHighlightMatrix.setTranslate(0f, mWaferCardRect.top);
                mWaferHighlightShader.setLocalMatrix(mWaferHighlightMatrix);
            }
            canvas.drawRoundRect(mWaferCardRect, radius, radius, mWaferHighlightPaint);
        }

        // 3. Hairline border — wafer_glass_border. Inset by half the stroke width
        //    so the stroke sits inside the rounded rect and doesn't get clipped.
        final float inset = mWaferBorderWidth * 0.5f;
        mWaferBorderRect.set(left + inset, top + inset, right - inset, bottom - inset);
        final float borderRadius = Math.max(0f, radius - inset);
        canvas.drawRoundRect(mWaferBorderRect, borderRadius, borderRadius, mWaferBorderPaint);
    }

    private Path calculateDismissButtonCutoutPath(Rect backgroundBounds) {
        // TODO(b/365585705): Adapt to RTL after the UX design is finalized.

        NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode();

        Path path = new Path();

        final int left = backgroundBounds.left;
        final int right = backgroundBounds.right;
        final int top = backgroundBounds.top;
        final int bottom = backgroundBounds.bottom;

        // Generate the path clockwise from the left-top corner.
        path.moveTo(left, top);
        path.lineTo(right - 2 * convexR - concaveR, top);
        path.quadTo(right - convexR - concaveR, top, right - convexR - concaveR,
                top + convexR);
        path.quadTo(right - convexR - concaveR, top + convexR + concaveR, right - convexR,
                top + convexR + concaveR);
        path.quadTo(right, top + convexR + concaveR, right, top + 2 * convexR + concaveR);
        path.lineTo(right, bottom);
        path.lineTo(left, bottom);
        path.lineTo(left, top);

        return path;
    }

    private Rect calculateBackgroundBounds() {
        NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode();

        int top = 0;
        int bottom = getActualHeight();
        if (mBottomIsRounded
                && mBottomAmountClips
                && !mExpandAnimationRunning) {
            bottom -= mClipBottomAmount;
        }
        final boolean isRtl = isLayoutRtl();
        final int width = getWidth();
        final int actualWidth = getActualWidth();

        int left = isRtl ? width - actualWidth : 0;
        int right = isRtl ? width : actualWidth;

        if (mExpandAnimationRunning) {
            // Horizontally center this background view inside of the container
            left = (int) ((width - actualWidth) / 2.0f);
            right = (int) (left + actualWidth);
        }

        return new Rect(left, top, right, bottom);
    }

    private void draw(Canvas canvas, Drawable drawable) {
        NotificationAddXOnHoverToDismiss.assertInLegacyMode();

        if (drawable != null) {
            int top = 0;
            int bottom = getActualHeight();
            if (mBottomIsRounded
                    && mBottomAmountClips
                    && !mExpandAnimationRunning) {
                bottom -= mClipBottomAmount;
            }
            final boolean isRtl = isLayoutRtl();
            final int width = getWidth();
            final int actualWidth = getActualWidth();

            int left = isRtl ? width - actualWidth : 0;
            int right = isRtl ? width : actualWidth;

            if (mExpandAnimationRunning) {
                // Horizontally center this background view inside of the container
                left = (int) ((width - actualWidth) / 2.0f);
                right = (int) (left + actualWidth);
            }
            drawable.setBounds(left, top, right, bottom);
            drawable.draw(canvas);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mBackground;
    }

    @Override
    protected void drawableStateChanged() {
        setState(getDrawableState());
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (mBackground != null) {
            mBackground.setHotspot(x, y);
        }
    }

    /**
     * Stateful colors are colors that will overlay on the notification original color when one of
     * hover states, pressed states or other similar states is activated.
     */
    private void setStatefulColors() {
        if (mTintColor != mNormalColor) {
            ColorStateList newColor = ContrastColorUtil.isColorDark(mTintColor)
                    ? mDarkColoredStatefulColors : mLightColoredStatefulColors;
            ((GradientDrawable) getStatefulBackgroundLayer().mutate()).setColor(newColor);
        }
    }

    /**
     * Sets a background drawable. As we need to change our bounds independently of layout, we need
     * the notion of a background independently of the regular View background..
     */
    public void setCustomBackground(Drawable background) {
        if (mBackground != null) {
            mBackground.setCallback(null);
            unscheduleDrawable(mBackground);
        }
        mBackground = background;
        mRippleColor = null;
        mBackground.mutate();
        if (mBackground != null) {
            mBackground.setCallback(this);
            setTint(mTintColor);
        }
        if (mBackground instanceof RippleDrawable) {
            ((RippleDrawable) mBackground).setForceSoftware(true);
        }
        updateBackgroundRadii();
        invalidate();
    }

    public void setCustomBackground(int drawableResId) {
        final Drawable d = mContext.getDrawable(drawableResId);
        setCustomBackground(d);
    }

    private Drawable getBaseBackgroundLayer() {
        return ((LayerDrawable) mBackground).getDrawable(0);
    }

    private Drawable getStatefulBackgroundLayer() {
        return ((LayerDrawable) mBackground).getDrawable(1);
    }

    public void setTint(int tintColor) {
        // Wafer glass: layer 0 of notification_material_bg.xml is fully transparent
        // and the wafer surface is painted in onDraw(), so SRC_ATOP tinting here
        // is a no-op on the visible card. We still call through to keep the rest
        // of the legacy state machinery (state colors on layer 1) consistent with
        // mTintColor.
        Drawable baseLayer = getBaseBackgroundLayer();
        baseLayer.mutate().setTintMode(PorterDuff.Mode.SRC_ATOP);
        baseLayer.setTint(tintColor);
        mTintColor = tintColor;
        setStatefulColors();
        invalidate();
    }

    public void setActualHeight(int actualHeight) {
        if (mExpandAnimationRunning) {
            return;
        }
        mActualHeight = actualHeight;
        invalidate();
    }

    private int getActualHeight() {
        if (mExpandAnimationRunning && mExpandAnimationHeight > -1) {
            return mExpandAnimationHeight;
        } else if (mActualHeight > -1) {
            return mActualHeight;
        }
        return getHeight();
    }

    public void setActualWidth(int actualWidth) {
        mActualWidth = actualWidth;
    }

    private int getActualWidth() {
        if (mExpandAnimationRunning && mExpandAnimationWidth > -1) {
            return mExpandAnimationWidth;
        } else if (mActualWidth > -1) {
            return mActualWidth;
        }
        return getWidth();
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        invalidate();
    }

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        invalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {

        // Prevents this view from creating a layer when alpha is animating.
        return false;
    }

    public void setState(int[] drawableState) {
        if (mBackground != null && mBackground.isStateful()) {
            mBackground.setState(drawableState);
        }
    }

    public void setRippleColor(int color) {
        if (mBackground instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) mBackground;
            ripple.setColor(ColorStateList.valueOf(color));
            mRippleColor = color;
        } else {
            mRippleColor = null;
        }
    }

    public void setDrawableAlpha(int drawableAlpha) {
        mDrawableAlpha = drawableAlpha;
        if (mExpandAnimationRunning) {
            return;
        }
        mBackground.setAlpha(drawableAlpha);
    }

    /**
     * Sets the current top and bottom radius for this background.
     */
    public void setRadius(float topRoundness, float bottomRoundness) {
        if (topRoundness == mCornerRadii[0] && bottomRoundness == mCornerRadii[4]) {
            return;
        }
        mBottomIsRounded = bottomRoundness != 0.0f;
        mCornerRadii[0] = topRoundness;
        mCornerRadii[1] = topRoundness;
        mCornerRadii[2] = topRoundness;
        mCornerRadii[3] = topRoundness;
        mCornerRadii[4] = bottomRoundness;
        mCornerRadii[5] = bottomRoundness;
        mCornerRadii[6] = bottomRoundness;
        mCornerRadii[7] = bottomRoundness;
        updateBackgroundRadii();
    }

    public void setBottomAmountClips(boolean clips) {
        if (clips != mBottomAmountClips) {
            mBottomAmountClips = clips;
            invalidate();
        }
    }

    private void updateBackgroundRadii() {
        if (mDontModifyCorners) {
            return;
        }
        if (mBackground instanceof LayerDrawable layerDrawable) {
            int numberOfLayers = layerDrawable.getNumberOfLayers();
            for (int i = 0; i < numberOfLayers; i++) {
                GradientDrawable gradientDrawable = (GradientDrawable) layerDrawable.getDrawable(i);
                gradientDrawable.setCornerRadii(mCornerRadii);
            }
            updateFocusOverlayRadii(layerDrawable);
        }
    }

    private void updateFocusOverlayRadii(LayerDrawable background) {
        GradientDrawable overlay =
                (GradientDrawable) background.findDrawableByLayerId(
                        R.id.notification_focus_overlay);
        for (int i = 0; i < mCornerRadii.length; i++) {
            // in theory subtracting mFocusOverlayStroke/2 should be enough but notification
            // background is still peeking a bit from below - probably due to antialiasing or
            // overlay uneven scaling. So let's subtract full mFocusOverlayStroke to make sure the
            // radius is a bit smaller and covers background corners fully
            mFocusOverlayCornerRadii[i] = Math.max(0, mCornerRadii[i] - mFocusOverlayStroke);
        }
        overlay.setCornerRadii(mFocusOverlayCornerRadii);
    }

    /** Set the current expand animation size. */
    public void setExpandAnimationSize(int width, int height) {
        mExpandAnimationHeight = height;
        mExpandAnimationWidth = width;
        invalidate();
    }

    public void setExpandAnimationRunning(boolean running) {
        mExpandAnimationRunning = running;
        if (mBackground instanceof LayerDrawable) {
            GradientDrawable gradientDrawable =
                    (GradientDrawable) ((LayerDrawable) mBackground).getDrawable(0);
            // Speed optimization: disable AA if transfer mode is not SRC_OVER. AA is not easy to
            // spot during animation anyways.
            gradientDrawable.setAntiAlias(!running);
        }
        if (!mExpandAnimationRunning) {
            setDrawableAlpha(mDrawableAlpha);
        }
        invalidate();
    }

    @Override
    public void dump(PrintWriter pw, @NonNull String[] args) {
        pw.println("mDontModifyCorners: " + mDontModifyCorners);
        pw.println("mClipTopAmount: " + mClipTopAmount);
        pw.println("mClipBottomAmount: " + mClipBottomAmount);
        pw.println("mCornerRadii: " + Arrays.toString(mCornerRadii));
        pw.println("mBottomIsRounded: " + mBottomIsRounded);
        pw.println("mBottomAmountClips: " + mBottomAmountClips);
        pw.println("mActualWidth: " + mActualWidth);
        pw.println("mActualHeight: " + mActualHeight);
        pw.println("mTintColor: " + hexColorString(mTintColor));
        pw.println("mRippleColor: " + hexColorString(mRippleColor));
        pw.println("mBackground: " + DrawableDumpKt.dumpToString(mBackground));
    }

    /** create a concise dump of this view's colors */
    public String toDumpString() {
        return "<NotificationBackgroundView"
                + " tintColor=" + hexColorString(mTintColor)
                + " rippleColor=" + hexColorString(mRippleColor)
                + " bgColor=" + DrawableDumpKt.getSolidColor(mBackground)
                + ">";

    }
}
