package com.android.systemui.statusbar.notification.row;

import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A custom view for displaying daily outlook information in the notification shade.
 * This view can be rendered alongside regular notifications.
 */
public class DailyOutlookView extends ExpandableView {
    private static final String TAG = "DailyOutlookView";
    
    private TextView mTitleView;
    private TextView mTimeView;
    private TextView mLocationView;
    private TextView mDescriptionView;
    private boolean mIsOnKeyguard = false;
    
    // Store a fixed intrinsic height since our content is relatively static
    private static final int INTRINSIC_HEIGHT_DP = 140;
    private int mIntrinsicHeight;

    // Static data for now - will be replaced with dynamic data later
    private static final String DEFAULT_TITLE = "📅 Daily Outlook";
    private static final String DEFAULT_TIME = "Team Meeting • 2:00 PM - 2:30 PM";
    private static final String DEFAULT_LOCATION = "📍 Conference Room A";
    private static final String DEFAULT_DESCRIPTION = "👥 With: Sarah, John, Mike\n📝 Discuss Q4 roadmap";
    
    public DailyOutlookView(Context context) {
        this(context, null);
    }

    public DailyOutlookView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DailyOutlookView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs);
        initView();
    }

    private void initView() {
        Log.d(TAG, "initView - creating UI");
        
        // Set the intrinsic height upfront
        mIntrinsicHeight = dpToPx(INTRINSIC_HEIGHT_DP);

        // Create card container
        LinearLayout cardContainer = new LinearLayout(getContext());
        cardContainer.setOrientation(LinearLayout.VERTICAL);
        cardContainer.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        
        // Create background without rounded corners for full-width card
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(0);
        cardContainer.setBackground(background);
        // Remove elevation for flat appearance consistent with notifications
        cardContainer.setElevation(0);
        
        // Layout params for full width TextViews
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );

        // Title
        mTitleView = new TextView(getContext());
        mTitleView.setText(DEFAULT_TITLE);
        mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        mTitleView.setTextColor(Color.parseColor("#1A73E8")); // Google blue
        mTitleView.setPadding(0, 0, 0, dpToPx(8));

        // Time
        mTimeView = new TextView(getContext());
        mTimeView.setText(DEFAULT_TIME);
        mTimeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mTimeView.setTextColor(Color.parseColor("#202124"));
        mTimeView.setPadding(0, 0, 0, dpToPx(4));

        // Location
        mLocationView = new TextView(getContext());
        mLocationView.setText(DEFAULT_LOCATION);
        mLocationView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mLocationView.setTextColor(Color.parseColor("#5F6368"));
        mLocationView.setPadding(0, 0, 0, dpToPx(4));

        // Description
        mDescriptionView = new TextView(getContext());
        mDescriptionView.setText(DEFAULT_DESCRIPTION);
        mDescriptionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mDescriptionView.setTextColor(Color.parseColor("#5F6368"));
        mDescriptionView.setLineSpacing(dpToPx(2), 1.0f);

        // Add all views to card with full width params
        cardContainer.addView(mTitleView, textParams);
        cardContainer.addView(mTimeView, textParams);
        cardContainer.addView(mLocationView, textParams);
        cardContainer.addView(mDescriptionView, textParams);

        // Add card to this view - full width with only vertical margins
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dpToPx(4), 0, dpToPx(4));
        addView(cardContainer, params);

        // Set minimum height
        setMinimumHeight(mIntrinsicHeight);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Ensure we use the full available width
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);

        // Force to use full available width when possible
        if (widthMode != MeasureSpec.EXACTLY && widthSize > 0) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY);
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * Update the daily outlook data
     */
    public void updateData(String title, String time, String location, String description) {
        if (mTitleView != null) mTitleView.setText(title);
        if (mTimeView != null) mTimeView.setText(time);
        if (mLocationView != null) mLocationView.setText(location);
        if (mDescriptionView != null) mDescriptionView.setText(description);
        
        requestLayout();
    }

    // ========== REQUIRED NOTIFICATION SYSTEM INTEGRATION ==========
    
    @Override
    public int getIntrinsicHeight() {
        return mIntrinsicHeight;
    }
    
    @Override
    public int getMinHeight() {
        return mIntrinsicHeight;
    }

    @Override
    public boolean needsClippingToShelf() {
        return false;
    }

    @Override
    public boolean isContentExpandable() {
        return false;
    }

    @Override
    public long performRemoveAnimation(long duration, long delay,
            float translationDirection, boolean isHeadsUpAnimation,
            Runnable onStartedRunnable, Runnable onFinishedRunnable,
            AnimatorListenerAdapter animationListener, ClipSide clipSide) {
        if (onStartedRunnable != null) {
            onStartedRunnable.run();
        }
        if (onFinishedRunnable != null) {
            onFinishedRunnable.run();
        }
        return 0;
    }

    @Override
    public void performAddAnimation(long delay, long duration,
            boolean isHeadsUpAppear, Runnable onEndRunnable) {
        if (onEndRunnable != null) {
            onEndRunnable.run();
        }
    }

    public void setOnKeyguard(boolean onKeyguard) {
        mIsOnKeyguard = onKeyguard;
        Log.d(TAG, "setOnKeyguard: " + onKeyguard);
    }

    // ========== UTILITY METHODS ==========
    
    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            getContext().getResources().getDisplayMetrics()
        );
    }

    public static boolean shouldShow() {
        return true;
    }
}