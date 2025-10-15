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

    private LinearLayout mEventsContainer;
    private boolean mIsOnKeyguard = false;

    // Store a dynamic intrinsic height based on number of events
    private static final int EVENT_HEIGHT_DP = 140;
    private static final int EVENT_SPACING_DP = 8;
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

        // Set the initial intrinsic height
        mIntrinsicHeight = dpToPx(EVENT_HEIGHT_DP);

        // Create a container to hold multiple event cards
        mEventsContainer = new LinearLayout(getContext());
        mEventsContainer.setOrientation(LinearLayout.VERTICAL);

        // Add container to this view - full width
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        addView(mEventsContainer, containerParams);

        // Set minimum height
        setMinimumHeight(mIntrinsicHeight);

        // Show default event initially
        updateWithDefaultData();
    }

    private void updateWithDefaultData() {
        clearEvents();
        addEventCard(DEFAULT_TITLE, DEFAULT_TIME, DEFAULT_LOCATION, DEFAULT_DESCRIPTION);
    }

    private void clearEvents() {
        if (mEventsContainer != null) {
            mEventsContainer.removeAllViews();
        }
    }

    private void addEventCard(String title, String time, String location, String description) {
        // Create card container for this event
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
        TextView titleView = new TextView(getContext());
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.parseColor("#1A73E8")); // Google blue
        titleView.setPadding(0, 0, 0, dpToPx(8));

        // Time
        TextView timeView = new TextView(getContext());
        timeView.setText(time);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        timeView.setTextColor(Color.parseColor("#202124"));
        timeView.setPadding(0, 0, 0, dpToPx(4));

        // Location
        TextView locationView = new TextView(getContext());
        locationView.setText(location);
        locationView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        locationView.setTextColor(Color.parseColor("#5F6368"));
        locationView.setPadding(0, 0, 0, dpToPx(4));

        // Description
        TextView descriptionView = new TextView(getContext());
        descriptionView.setText(description);
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        descriptionView.setTextColor(Color.parseColor("#5F6368"));
        descriptionView.setLineSpacing(dpToPx(2), 1.0f);

        // Add all views to card with full width params
        cardContainer.addView(titleView, textParams);
        cardContainer.addView(timeView, textParams);
        cardContainer.addView(locationView, textParams);
        cardContainer.addView(descriptionView, textParams);

        // Add card to events container with margins
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, dpToPx(4), 0, dpToPx(4));
        mEventsContainer.addView(cardContainer, cardParams);
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
     * Update the daily outlook data with a single event (backwards compatibility)
     */
    public void updateData(String title, String time, String location, String description) {
        clearEvents();
        addEventCard(title, time, location, description);
        updateIntrinsicHeight(1);
        requestLayout();
    }

    /**
     * Update the daily outlook data with multiple events
     */
    public void updateMultipleEvents(java.util.List<EventData> events) {
        clearEvents();
        if (events == null || events.isEmpty()) {
            updateWithDefaultData();
            updateIntrinsicHeight(1);
        } else {
            for (EventData event : events) {
                addEventCard(event.title, event.time, event.location, event.description);
            }
            updateIntrinsicHeight(events.size());
        }
        requestLayout();
    }

    private void updateIntrinsicHeight(int eventCount) {
        // Calculate height based on number of events
        mIntrinsicHeight = dpToPx(EVENT_HEIGHT_DP * eventCount + EVENT_SPACING_DP * (eventCount - 1));
        setMinimumHeight(mIntrinsicHeight);
    }

    /**
     * Data class for passing event information
     */
    public static class EventData {
        public final String title;
        public final String time;
        public final String location;
        public final String description;

        public EventData(String title, String time, String location, String description) {
            this.title = title;
            this.time = time;
            this.location = location;
            this.description = description;
        }
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