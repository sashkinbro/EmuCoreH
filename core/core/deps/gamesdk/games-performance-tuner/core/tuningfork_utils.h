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

#pragma once

#include <string>

#include "json11/json11.hpp"
#include "tuningfork_internal.h"

class AAsset;

namespace tuningfork {

// Convert an array of bytes into a string hex representation
std::string Base16(const std::vector<unsigned char>& bytes);

namespace apk_utils {

// Get the serialization of an asset or return false.
bool GetAssetAsSerialization(const char* name, ProtobufSerialization& out);

// Get the app's version code. Also fills packageNameStr, if not null, with
// the package name.
int GetVersionCode(std::string* packageNameStr = nullptr,
                   uint32_t* gl_es_version = nullptr);

// Get the app's SHA1 signature
std::string GetSignature();

// Get whether the ApplicationInfo indicates the APK is debuggable
bool GetDebuggable();

}  // namespace apk_utils

namespace file_utils {

// Creates the directory if it does not exist. Returns true if the directory
//  already existed or could be created.
bool CheckAndCreateDir(const std::string& path);

bool FileExists(const std::string& fname);

bool DeleteFile(const std::string& path);

bool DeleteDir(const std::string& path);

bool LoadBytesFromFile(std::string file_name,
                       TuningFork_CProtobufSerialization* params);

bool SaveBytesToFile(std::string file_name,
                     const TuningFork_CProtobufSerialization* params);

// Call NativeContext.getCacheDir via JNI
std::string GetAppCacheDir();

}  // namespace file_utils

namespace json_utils {

// Resource name for the tuning parameters of an apk, identified by package
// name and version code.
std::string GetResourceName(const RequestInfo& request_info);

// See DeviceSpec in proto/performanceparameters.proto
json11::Json::object DeviceSpecJson(const RequestInfo& request_info);

}  // namespace json_utils

// Get a unique identifier using java.util.UUID
std::string UniqueId();

// Time between Unix Epoch and now.
// Returns a duration or 0 count on error.
Duration GetElapsedTimeSinceEpoch();

// Time between the Unix Epoch and process start.
// Returns a duration or 0 count on error.
Duration GetProcessStartTimeSinceEpoch();

// Time between process start and now.
// Returns a duration or 0 count on error.
Duration GetTimeSinceProcessStart();

}  // namespace tuningfork
