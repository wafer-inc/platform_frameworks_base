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

package com.android.systemui.statusbar.notification.switchboard

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import switchboard.ISwitchboardOutlookCallback
import switchboard.ISwitchboardService
import javax.inject.Inject

/**
 * Service connection to the Switchboard service for getting daily outlook data.
 */
@SysUISingleton
class SwitchboardServiceConnection @Inject constructor(
    @Application private val context: Context,
    @Background private val bgDispatcher: CoroutineDispatcher
) {
    private var switchboardService: ISwitchboardService? = null
    private val _outlookData = MutableStateFlow<DailyOutlookData?>(null)
    val outlookData: StateFlow<DailyOutlookData?> = _outlookData.asStateFlow()

    companion object {
        private const val TAG = "SwitchboardConnection"
        private const val SERVICE_NAME = "switchboardservice"
    }

    fun connect() {
        Log.d(TAG, "[SWITCHBOARD-CONNECT] Starting connection to Switchboard binder service")
        Log.d(TAG, "[SWITCHBOARD-CONNECT] Looking for service: $SERVICE_NAME")
        try {
            val binder = ServiceManager.getService(SERVICE_NAME)
            if (binder != null) {
                Log.d(TAG, "[SWITCHBOARD-CONNECT] ✓ Found binder service, creating interface")
                switchboardService = ISwitchboardService.Stub.asInterface(binder)
                Log.d(TAG, "[SWITCHBOARD-CONNECT] ✓ Interface created successfully")
                requestOutlookUpdate()
            } else {
                Log.w(TAG, "[SWITCHBOARD-CONNECT] ✗ Binder service not found in ServiceManager")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[SWITCHBOARD-CONNECT] ✗ Exception during connection", e)
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting from Switchboard service")
        switchboardService = null
        _outlookData.value = null
    }

    private fun requestOutlookUpdate() {
        Log.d(TAG, "[SWITCHBOARD-REQUEST] Starting outlook update request")
        val service = switchboardService ?: run {
            Log.w(TAG, "[SWITCHBOARD-REQUEST] ✗ Service not connected, aborting")
            return
        }

        try {
            Log.d(TAG, "[SWITCHBOARD-REQUEST] Creating callback object")
            service.gatherContext(object : ISwitchboardOutlookCallback.Stub() {
                override fun onResponse(contextJson: String) {
                    Log.d(TAG, "[SWITCHBOARD-RESPONSE] ✓ Received response from service")
                    Log.d(TAG, "[SWITCHBOARD-RESPONSE] JSON length: ${contextJson.length} chars")
                    Log.d(TAG, "[SWITCHBOARD-RESPONSE] First 500 chars: ${contextJson.take(500)}")
                    parseOutlookResponse(contextJson)
                }

                override fun onError(code: Int, error: String) {
                    Log.e(TAG, "[SWITCHBOARD-ERROR] ✗ Error from service: code=$code, error=$error")
                    _outlookData.value = null
                }
            })
            Log.d(TAG, "[SWITCHBOARD-REQUEST] ✓ Callback registered, waiting for response")
        } catch (e: RemoteException) {
            Log.e(TAG, "[SWITCHBOARD-ERROR] ✗ RemoteException during request", e)
        }
    }

    private fun parseOutlookResponse(json: String) {
        Log.d(TAG, "[SWITCHBOARD-PARSE] Starting JSON parsing")
        try {
            val response = JSONObject(json)
            Log.d(TAG, "[SWITCHBOARD-PARSE] JSON object created, looking for 'events' array")
            val eventsArray = response.getJSONArray("events")
            Log.d(TAG, "[SWITCHBOARD-PARSE] Found ${eventsArray.length()} events")

            val events = mutableListOf<DailyEvent>()
            for (i in 0 until eventsArray.length()) {
                val eventJson = eventsArray.getJSONObject(i)
                val event = DailyEvent(
                    header = eventJson.getString("header"),
                    body = eventJson.getString("body"),
                    spans = parseSpans(eventJson.optJSONArray("spans")),
                    launchableSources = parseLaunchableSources(
                        eventJson.optJSONArray("launchable_sources")
                    )
                )
                events.add(event)
                Log.d(TAG, "[SWITCHBOARD-PARSE] Event $i: ${event.header}")
            }

            _outlookData.value = DailyOutlookData(events)
            Log.d(TAG, "[SWITCHBOARD-PARSE] ✓ Successfully parsed and stored ${events.size} events")
        } catch (e: Exception) {
            Log.e(TAG, "[SWITCHBOARD-PARSE] ✗ Error parsing JSON response", e)
            Log.e(TAG, "[SWITCHBOARD-PARSE] Failed JSON: ${json.take(500)}")
            _outlookData.value = null
        }
    }

    private fun parseSpans(spansArray: org.json.JSONArray?): List<Span> {
        val spans = mutableListOf<Span>()
        if (spansArray == null) return spans

        for (i in 0 until spansArray.length()) {
            val spanJson = spansArray.getJSONObject(i)
            val type = spanJson.getString("type")
            val start = spanJson.getInt("start")
            val end = spanJson.getInt("end")

            val span = when (type) {
                "Feature" -> {
                    val featuresArray = spanJson.getJSONArray("features")
                    val features = mutableListOf<Int>()
                    for (j in 0 until featuresArray.length()) {
                        features.add(featuresArray.getInt(j))
                    }
                    Span.Feature(start, end, features)
                }
                "Subject" -> {
                    Span.Subject(start, end)
                }
                else -> continue
            }
            spans.add(span)
        }
        return spans
    }

    private fun parseLaunchableSources(sourcesArray: org.json.JSONArray?): List<LaunchableSource> {
        val sources = mutableListOf<LaunchableSource>()
        if (sourcesArray == null) return sources

        for (i in 0 until sourcesArray.length()) {
            val sourceJson = sourcesArray.getJSONObject(i)
            sources.add(
                LaunchableSource(
                    sourceId = sourceJson.getLong("source_id"),
                    appName = sourceJson.getString("app_name"),
                    sourceText = sourceJson.getString("source_text")
                )
            )
        }
        return sources
    }

    fun refresh() {
        requestOutlookUpdate()
    }
}

// Data models
data class DailyOutlookData(
    val events: List<DailyEvent>
)

data class DailyEvent(
    val header: String,
    val body: String,
    val spans: List<Span>,
    val launchableSources: List<LaunchableSource>
)

sealed class Span {
    abstract val start: Int
    abstract val end: Int

    data class Feature(
        override val start: Int,
        override val end: Int,
        val features: List<Int>
    ) : Span()

    data class Subject(
        override val start: Int,
        override val end: Int
    ) : Span()
}

data class LaunchableSource(
    val sourceId: Long,
    val appName: String,
    val sourceText: String
)