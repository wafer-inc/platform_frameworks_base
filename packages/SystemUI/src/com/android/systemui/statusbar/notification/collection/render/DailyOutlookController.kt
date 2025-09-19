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
    var dailyOutlookView: DailyOutlookView? = null
        private set

    private var dataObserverJob: Job? = null

    fun reinflateView(parent: ViewGroup) {
        Log.d(TAG, "[OUTLOOK-CONTROLLER] reinflateView called, parent: $parent")

        // Only create if it should be shown
        if (!DailyOutlookView.shouldShow()) {
            Log.d(TAG, "DailyOutlookView.shouldShow() returned false, not showing")
            // Remove old view if it exists
            dailyOutlookView?.let { _view ->
                (_view.parent as? ViewGroup)?.removeView(_view)
            }
            dailyOutlookView = null
            stopDataObservation()
            return
        }

        // Only create a new view if we don't have one already
        if (dailyOutlookView == null) {
            // Create the view - it will be added by the ViewDiffer
            val dailyView = DailyOutlookView(parent.context)
            dailyView.visibility = View.VISIBLE
            Log.d(TAG, "[OUTLOOK-CONTROLLER] Created new DailyOutlookView with visibility VISIBLE, view=$dailyView")
            dailyOutlookView = dailyView

            // Start observing data from the Switchboard service
            Log.d(TAG, "[OUTLOOK-CONTROLLER] Starting data observation")
            startDataObservation()

            // Connect to the service if not already connected
            Log.d(TAG, "[OUTLOOK-CONTROLLER] Calling serviceConnection.connect()")
            serviceConnection.connect()
            Log.d(TAG, "[OUTLOOK-CONTROLLER] serviceConnection.connect() completed")
        } else {
            Log.d(TAG, "Reusing existing DailyOutlookView: ${dailyOutlookView}")
        }
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
        val view = dailyOutlookView ?: run {
            Log.w(TAG, "[OUTLOOK-CONTROLLER] No view available to update")
            return
        }

        if (data == null || data.events.isEmpty()) {
            Log.d(TAG, "[OUTLOOK-CONTROLLER] No data to display (data=${data != null}, events=${data?.events?.size ?: 0})")
            // Use default data when no real data is available
            return
        }

        // Use the first event for now
        val firstEvent = data.events.first()
        Log.d(TAG, "[OUTLOOK-CONTROLLER] Updating view with event: ${firstEvent.header}")
        Log.d(TAG, "[OUTLOOK-CONTROLLER] Event body: ${firstEvent.body.take(100)}")

        // Extract time and location from the event body if possible
        val bodyLines = firstEvent.body.lines()
        val time = if (bodyLines.isNotEmpty()) bodyLines[0] else ""
        val location = if (bodyLines.size > 1) bodyLines[1] else ""
        val description = if (bodyLines.size > 2) {
            bodyLines.drop(2).joinToString("\n")
        } else {
            firstEvent.body
        }

        Log.d(TAG, "[OUTLOOK-CONTROLLER] Calling view.updateData with:")
        Log.d(TAG, "[OUTLOOK-CONTROLLER]   - header: ${firstEvent.header}")
        Log.d(TAG, "[OUTLOOK-CONTROLLER]   - time: $time")
        Log.d(TAG, "[OUTLOOK-CONTROLLER]   - location: $location")
        view.updateData(
            firstEvent.header,
            time,
            location,
            description
        )
        Log.d(TAG, "[OUTLOOK-CONTROLLER] ✓ View updated successfully")
    }
    
    companion object {
        private const val TAG = "DailyOutlookController"
    }

    override val view: View
        get() {
            val v = dailyOutlookView
            if (v == null) {
                android.util.Log.e(TAG, "view getter called but dailyOutlookView is null!")
                throw IllegalStateException("DailyOutlookView not initialized")
            }
            return v
        }

    override fun offerToKeepInParentForAnimation(): Boolean = false

    override fun removeFromParentIfKeptForAnimation(): Boolean = false

    override fun resetKeepInParentForAnimation() {}

    fun onDestroy() {
        Log.d(TAG, "Destroying DailyOutlookController")
        stopDataObservation()
        serviceConnection.disconnect()
    }
}