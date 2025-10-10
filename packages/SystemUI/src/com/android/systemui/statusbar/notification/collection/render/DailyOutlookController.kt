/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.render

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.notification.row.DailyOutlookView
import com.android.systemui.statusbar.notification.switchboard.SwitchboardServiceConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Controller for the Daily Outlook view that appears at the top of the notification stack
 */
@SysUISingleton
class DailyOutlookController
@Inject
constructor(
    private val serviceConnection: SwitchboardServiceConnection,
    @Application private val applicationScope: CoroutineScope
) : NodeController {

    override val nodeLabel = "DailyOutlook"

    // Container to hold multiple outlook views
    private var containerView: ViewGroup? = null
    private val dailyOutlookViews = mutableListOf<DailyOutlookView>()
    private val MAX_OUTLOOK_VIEWS = 5

    // For compatibility, expose the container
    var dailyOutlookView: View? = null
        private set
        get() = containerView

    private var dataObserverJob: Job? = null

    fun reinflateView(parent: ViewGroup) {
        Log.d(TAG, "[OUTLOOK-CONTROLLER] reinflateView called, parent: $parent")

        // Only create if it should be shown
        if (!DailyOutlookView.shouldShow()) {
            Log.d(TAG, "DailyOutlookView.shouldShow() returned false, not showing")
            // Remove container if it exists
            containerView?.let { container ->
                (container.parent as? ViewGroup)?.removeView(container)
            }
            containerView = null
            dailyOutlookViews.clear()
            stopDataObservation()
            return
        }

        // Create container if we don't have one
        if (containerView == null) {
            val container = LinearLayout(parent.context)
            container.orientation = LinearLayout.VERTICAL
            container.visibility = View.VISIBLE
            containerView = container

            Log.d(TAG, "[OUTLOOK-CONTROLLER] Created container view")

            // Start observing data from the Switchboard service
            Log.d(TAG, "[OUTLOOK-CONTROLLER] Starting data observation")
            startDataObservation()

            // Connect to the service if not already connected
            Log.d(TAG, "[OUTLOOK-CONTROLLER] Calling serviceConnection.connect()")
            serviceConnection.connect()
            Log.d(TAG, "[OUTLOOK-CONTROLLER] serviceConnection.connect() completed")
        } else {
            Log.d(TAG, "Reusing existing container with ${dailyOutlookViews.size} DailyOutlookViews")
        }
    }

    private fun clearAllViews() {
        containerView?.removeAllViews()
        dailyOutlookViews.clear()
    }

    private fun startDataObservation() {
        Log.d(TAG, "[OUTLOOK-CONTROLLER] Starting data observation coroutine")
        stopDataObservation() // Cancel any existing job

        dataObserverJob = applicationScope.launch {
            Log.d(TAG, "[OUTLOOK-CONTROLLER] Coroutine launched, collecting from outlookData flow")
            serviceConnection.outlookData.collect { data ->
                Log.d(TAG, "[OUTLOOK-CONTROLLER] Flow emitted data: ${data?.events?.size ?: "null"} events")
                updateViewWithData(data)
            }
        }
    }

    private fun stopDataObservation() {
        dataObserverJob?.cancel()
        dataObserverJob = null
    }

    private fun updateViewWithData(data: com.android.systemui.statusbar.notification.switchboard.DailyOutlookData?) {
        Log.d(TAG, "[OUTLOOK-CONTROLLER] updateViewWithData called with ${data?.events?.size ?: "null"} events")

        val container = containerView ?: run {
            Log.w(TAG, "[OUTLOOK-CONTROLLER] No container available")
            return
        }

        if (data == null || data.events.isEmpty()) {
            Log.d(TAG, "[OUTLOOK-CONTROLLER] No data to display (data=${data != null}, events=${data?.events?.size ?: 0})")
            // Clear all views when no data
            clearAllViews()
            return
        }

        // Clear existing views to refresh with new data
        clearAllViews()

        // Create views for up to MAX_OUTLOOK_VIEWS events
        val eventsToShow = data.events.take(MAX_OUTLOOK_VIEWS)
        Log.d(TAG, "[OUTLOOK-CONTROLLER] Creating views for ${eventsToShow.size} events (total: ${data.events.size})")

        eventsToShow.forEachIndexed { index, event ->
            Log.d(TAG, "[OUTLOOK-CONTROLLER] Creating view $index for event: ${event.header}")

            // Create a new view for each event
            val outlookView = DailyOutlookView(container.context)
            outlookView.visibility = View.VISIBLE

            // Extract time and location from the event body if possible
            val bodyLines = event.body.lines()
            val time = if (bodyLines.isNotEmpty()) bodyLines[0] else ""
            val location = if (bodyLines.size > 1) bodyLines[1] else ""
            val description = if (bodyLines.size > 2) {
                bodyLines.drop(2).joinToString("\n")
            } else {
                event.body
            }

            // Update the view with event data
            outlookView.updateData(
                event.header,
                time,
                location,
                description
            )

            // Add to our list and container
            dailyOutlookViews.add(outlookView)
            container.addView(outlookView)

            Log.d(TAG, "[OUTLOOK-CONTROLLER] ✓ View $index created and updated")
        }

        Log.d(TAG, "[OUTLOOK-CONTROLLER] ✓ All views updated successfully (${dailyOutlookViews.size} views)")
    }
    
    companion object {
        private const val TAG = "DailyOutlookController"
    }

    override val view: View
        get() {
            // Return the container view
            val v = containerView
            if (v == null) {
                android.util.Log.e(TAG, "view getter called but containerView is null!")
                throw IllegalStateException("Container view not initialized")
            }
            return v
        }

    override fun offerToKeepInParentForAnimation(): Boolean = false

    override fun removeFromParentIfKeptForAnimation(): Boolean = false

    override fun resetKeepInParentForAnimation() {}

    fun onDestroy() {
        Log.d(TAG, "Destroying DailyOutlookController")
        stopDataObservation()
        clearAllViews()
        containerView = null
        serviceConnection.disconnect()
    }
}