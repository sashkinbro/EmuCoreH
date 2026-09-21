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

// Import settings from tuningfork.proto
import com.google.tuningfork.Tuningfork.Settings

// Decoded from dev_tuningfork.descriptor
data class FidelityParameter(val name: String, val type: String, val value: String)

data class Annotation(val name: String, val type: String, val value: Int)

data class DeserializedTuningForkDescriptor(
    val package_name: String,
    val enums: Map<String, List<String>>,
    val fidelityParameters: List<FidelityParameter>,
    val annotations: List<Annotation>
)

// Info about the app from debugInfo messages.
data class AppData(
    val desc: DeserializedTuningForkDescriptor,
    val settings: Settings
)

// Data from uploadTelemetry requests.
data class AppTelemetry (
    val telemetry: Array<Telemetry>
)
