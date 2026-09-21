/*
 * Copyright 2019 The Android Open Source Project
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

package com.google.tuningfork

import org.json.JSONObject
import android.util.Base64
import android.util.Log
import java.lang.Exception

// These classes are needed because of the lack of Json deserialization in protobuf lite

data class GlesVersion(val major: Int, val minor: Int)

data class DeviceSpec(
    val fingerprint: String, val total_memory_bytes: Long, val build_version: String,
    val gles_version: GlesVersion, val cpu_core_freqs_hz: LongArray
)

data class GenerateTuningParametersRequest(
    val name: String, val device: DeviceSpec,
    val serialized_tuning_parameters: ByteArray?
)

data class DebugInfoRequest(
    val dev_tuningfork_descriptor: ByteArray?,
    val settings: ByteArray?
)

data class RenderTimeHistogram(val instrument_id: Int, val counts: IntArray)

data class RenderingReport(val histograms: Array<RenderTimeHistogram>)

data class LoadingReport(val loading_events: LoadingEvents)

data class TuningParameters(
    val experiment_id: String,
    val serialized_fidelity_parameters: ByteArray
)

data class TelemetryContext(
    val annotations: ByteArray, val tuning_parameters: TuningParameters,
    val duration_ms: Long // NB java.time.Duration is not available for SDK<26
)

data class Telemetry(val context: TelemetryContext, val report: TelemetryReport)

data class TelemetryReport(val rendering: RenderingReport?, val loading: LoadingReport?)

data class GameSdkInfo(val version: String, val session_id: String)

data class SessionContext(
    val device: DeviceSpec, val game_sdk_info: GameSdkInfo,
    val time_period: TimePeriod
)

data class UploadTelemetryRequest(
    val name: String, val session_context: SessionContext,
    val telemetry: Array<Telemetry>
)

// Note we don't use java.time.Instant here because it is only available in SDK>=26.
data class TimePeriod(val start_time: String, val end_time: String)

data class LoadingEvents(val times_ms: IntArray)

fun deserializeB64(obj: JSONObject, name: String): ByteArray? {
    try {
        val b64 = obj.getString(name)
        return Base64.decode(b64, Base64.DEFAULT)
    } catch (e: Exception) {
        return null
    }
}

class Deserializer {
    fun parseDebugInfoRequest(request_string: String): DebugInfoRequest {
        val request = JSONObject(request_string)
        val dev_tuningfork_descriptor = deserializeB64(request, "dev_tuningfork_descriptor")
        val settings = deserializeB64(request, "settings")
        return DebugInfoRequest(dev_tuningfork_descriptor, settings)
    }

    fun parseGenerateTuningParametersRequest(request_string: String):
            GenerateTuningParametersRequest {
        val request = JSONObject(request_string)
        val name = request.getString("name")
        val device_spec = getDeviceSpec(request, "device_spec")
        val serialized_tuning_parameters = deserializeB64(request, "serialized_tuning_parameters")
        return GenerateTuningParametersRequest(name, device_spec, serialized_tuning_parameters)
    }

    fun getByteArray(json: JSONObject, field_name: String): ByteArray {
        val field = json.getString(field_name)
        return Base64.decode(field, Base64.DEFAULT)
    }

    fun getDeviceSpec(json: JSONObject, field_name: String): DeviceSpec {
        val spec_json = json.getJSONObject(field_name)
        return DeviceSpec(
            spec_json.getString("fingerprint"),
            spec_json.getLong("total_memory_bytes"),
            spec_json.getString("build_version"),
            getGlesVersion(spec_json, "gles_version"),
            getLongArray(spec_json, "cpu_core_freqs_hz")
        )
    }

    fun getGlesVersion(json: JSONObject, field_name: String): GlesVersion {
        val gles_json = json.getJSONObject(field_name)
        return GlesVersion(gles_json.getInt("major"), gles_json.getInt("minor"))
    }

    fun getLongArray(json: JSONObject, field_name: String): LongArray {
        val arr_json = json.getJSONArray(field_name)
        val ls = LongArray(arr_json.length())
        for (i in 0 until arr_json.length()) {
            ls[i] = arr_json.getLong(i)
        }
        return ls
    }

    fun getIntArray(json: JSONObject, field_name: String): IntArray {
        val arr_json = json.getJSONArray(field_name)
        val ls = IntArray(arr_json.length())
        for (i in 0 until arr_json.length()) {
            ls[i] = arr_json.getInt(i)
        }
        return ls
    }

    fun parseUploadTelemetryRequest(request_string: String): UploadTelemetryRequest {
        val request = JSONObject(request_string)
        val name = request.getString("name")
        val session_context = getSessionContext(request, "session_context")
        val telemetry = getTelemetryArray(request, "telemetry")
        return UploadTelemetryRequest(name, session_context, telemetry)
    }

    fun getTelemetryArray(json: JSONObject, field_name: String): Array<Telemetry> {
        val tel_json = json.getJSONArray(field_name)
        return Array<Telemetry>(tel_json.length(), {
            val tel = tel_json.getJSONObject(it)
            val context = getTelemetryContext(tel, "context")
            val report = getTelemetryReport(tel, "report")
            Telemetry(context, report)
        })
    }

    fun getTelemetryContext(json: JSONObject, field_name: String): TelemetryContext {
        val ctx_json = json.getJSONObject(field_name)
        val annotations = getByteArray(ctx_json, "annotations")
        val tuning_parameters = getTuningParameters(ctx_json, "tuning_parameters")
        val duration_ms = getDurationMs(ctx_json, "duration")
        return TelemetryContext(annotations, tuning_parameters, duration_ms)
    }

    fun getTuningParameters(json: JSONObject, field_name: String): TuningParameters {
        val tuning_json = json.getJSONObject(field_name)
        val experiment_id = tuning_json.getString("experiment_id")
        val serialized_fidelity_parameters = getByteArray(
            tuning_json,
            "serialized_fidelity_parameters"
        )
        return TuningParameters(experiment_id, serialized_fidelity_parameters)
    }

    fun getDurationMs(json: JSONObject, field_name: String): Long {
        val dur_str = json.getString(field_name)
        if (dur_str.endsWith('s')) {
            val secs = dur_str.substring(0, dur_str.length - 1).toFloat()
            return (secs * 1000).toLong()
        } else {
            return 0
        }
    }

    fun getTelemetryReport(json: JSONObject, field_name: String): TelemetryReport {
        val ctx_json = json.getJSONObject(field_name)
        val rendering = getRenderingReport(ctx_json, "rendering")
        val loading = getLoadingReport(ctx_json, "loading")
        return TelemetryReport(rendering, loading)
    }

    fun getSessionContext(json: JSONObject, field_name: String): SessionContext {
        val ctx_json = json.getJSONObject(field_name)
        val device = getDeviceSpec(ctx_json, "device")
        val game_sdk_info = getGameSdkInfo(ctx_json, "game_sdk_info")
        val time_period = getTimePeriod(ctx_json, "time_period")
        return SessionContext(device, game_sdk_info, time_period)
    }

    fun getGameSdkInfo(json: JSONObject, field_name: String): GameSdkInfo {
        val info_json = json.getJSONObject(field_name)
        val version = info_json.getString("version")
        val session_id = info_json.getString("session_id")
        return GameSdkInfo(version, session_id)
    }

    fun getRenderingReport(json: JSONObject, field_name: String): RenderingReport? {
        try {
            val report_json = json.getJSONObject(field_name)
            val histograms_json = report_json.getJSONArray("render_time_histogram")
            val histograms = Array<RenderTimeHistogram>(histograms_json.length(), {
                getRenderTimeHistogram(histograms_json.getJSONObject(it))
            })
            return RenderingReport(histograms)
        } catch (e: Exception) {
            return null
        }
    }

    fun getLoadingReport(json: JSONObject, field_name: String): LoadingReport? {
        try {
            val report_json = json.getJSONObject(field_name)
            val loading_events = getLoadingEvents(report_json, "loading_events")
            return LoadingReport(loading_events)
        } catch (e: Exception) {
            return null
        }
    }

    fun getLoadingEvents(json: JSONObject, field_name: String): LoadingEvents {
        val load_json = json.getJSONObject(field_name)
        return LoadingEvents(getIntArray(load_json, "times_ms"))
    }

    fun getRenderTimeHistogram(hist_json: JSONObject): RenderTimeHistogram {
        val instrument_id = hist_json.getInt("instrument_id")
        val counts = getIntArray(hist_json, "counts")
        return RenderTimeHistogram(instrument_id, counts)
    }

    fun getTimePeriod(json: JSONObject, field_name: String): TimePeriod {
        val time_json = json.getJSONObject(field_name)
        val start_time = time_json.getString("start_time")
        val end_time = time_json.getString("end_time")
        return TimePeriod(start_time, end_time)
    }
}
