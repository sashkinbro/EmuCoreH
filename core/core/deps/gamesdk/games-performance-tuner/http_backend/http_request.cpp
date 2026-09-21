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

#include "http_request.h"

#include <sstream>

#include "jni/jni_wrap.h"

#define LOG_TAG "TuningFork:Web"
#include "Log.h"
#include "core/tuningfork_utils.h"

namespace tuningfork {

std::string HttpRequest::GetURL(std::string rpcname) const {
    std::stringstream url;
    url << base_url_;
    url << json_utils::GetResourceName(RequestInfo::CachedValue());
    url << rpcname;
    return url.str();
}

static TuningFork_ErrorCode ConnectionIsMetered(bool& value) {
    using namespace gamesdk::jni;
    java::Object obj = AppContext().getSystemService(
        android::content::Context::CONNECTIVITY_SERVICE);
    CHECK_FOR_JNI_EXCEPTION_AND_RETURN(TUNINGFORK_ERROR_JNI_EXCEPTION);
    if (obj.IsNull())
        return TUNINGFORK_ERROR_BAD_PARAMETER;  // Can't get service
    android::net::ConnectivityManager cm(std::move(obj));
    value = cm.isActiveNetworkMetered();
    CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
        TUNINGFORK_ERROR_JNI_EXCEPTION);  // Most likely missing
                                          // Manifest.permission.ACCESS_NETWORK_STATE
                                          // or no active network.
    return TUNINGFORK_ERROR_OK;
}

TuningFork_ErrorCode HttpRequest::Send(const std::string& rpc_name,
                                       const std::string& request_json,
                                       int& response_code,
                                       std::string& response_body) {
    if (!gamesdk::jni::IsValid()) return TUNINGFORK_ERROR_JNI_BAD_ENV;
    bool connection_is_metered;
    auto err = ConnectionIsMetered(connection_is_metered);
    if (err != TUNINGFORK_ERROR_OK) return err;
    if ((!allow_metered_) && connection_is_metered)
        return TUNINGFORK_ERROR_METERED_CONNECTION_DISALLOWED;
    auto uri = GetURL(rpc_name);
    ALOGI("Connecting to: %s",
          g_verbose_logging_enabled ? uri.c_str() : LOGGING_PLACEHOLDER_TEXT);

    using namespace gamesdk::jni;

    auto url = java::net::URL(uri);
    SAFE_LOGGING_CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
        TUNINGFORK_ERROR_JNI_EXCEPTION,
        g_verbose_logging_enabled);  // Malformed URL

    // Open connection and set properties
    java::net::HttpURLConnection connection(url.openConnection());
    SAFE_LOGGING_CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
        TUNINGFORK_ERROR_JNI_EXCEPTION,
        g_verbose_logging_enabled);  // IOException
    connection.setRequestMethod("POST");
    auto timeout_ms =
        std::chrono::duration_cast<std::chrono::milliseconds>(timeout_).count();
    connection.setConnectTimeout(timeout_ms);
    connection.setReadTimeout(timeout_ms);
    connection.setDoOutput(true);
    connection.setDoInput(true);
    connection.setUseCaches(false);
    if (!api_key_.empty()) {
        connection.setRequestProperty("X-Goog-Api-Key", api_key_);
    }
    connection.setRequestProperty("Content-Type", "application/json");

    std::string package_name;
    apk_utils::GetVersionCode(&package_name);
    if (!package_name.empty())
        connection.setRequestProperty("X-Android-Package", package_name);
    auto signature = apk_utils::GetSignature();
    if (!signature.empty())
        connection.setRequestProperty("X-Android-Cert", signature);

    // Write json request body
    auto os = connection.getOutputStream();
    SAFE_LOGGING_CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
        TUNINGFORK_ERROR_JNI_EXCEPTION,
        g_verbose_logging_enabled);  // IOException
    auto writer =
        java::io::BufferedWriter(java::io::OutputStreamWriter(os, "UTF-8"));
    writer.write(request_json);
    SAFE_LOGGING_CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
        TUNINGFORK_ERROR_JNI_EXCEPTION,
        g_verbose_logging_enabled);  // IOException
    writer.flush();
    SAFE_LOGGING_CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
        TUNINGFORK_ERROR_JNI_EXCEPTION,
        g_verbose_logging_enabled);  // IOException
    writer.close();
    SAFE_LOGGING_CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
        TUNINGFORK_ERROR_JNI_EXCEPTION,
        g_verbose_logging_enabled);  // IOException
    os.close();
    SAFE_LOGGING_CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
        TUNINGFORK_ERROR_JNI_EXCEPTION,
        g_verbose_logging_enabled);  // IOException

    // Connect and get response
    connection.connect();
    SAFE_LOGGING_CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
        TUNINGFORK_ERROR_JNI_EXCEPTION,
        g_verbose_logging_enabled);  // IOException

    response_code = connection.getResponseCode();
    ALOGI("Response code: %d", response_code);
    SAFE_LOGGING_CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
        TUNINGFORK_ERROR_JNI_EXCEPTION,
        g_verbose_logging_enabled);  // IOException

    auto resp = connection.getResponseMessage();
    ALOGI("Response message: %s", resp.C());
    SAFE_LOGGING_CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
        TUNINGFORK_ERROR_JNI_EXCEPTION,
        g_verbose_logging_enabled);  // IOException

    // Read body from input stream
    auto is = connection.getInputStream();
    SAFE_LOGGING_CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
        TUNINGFORK_ERROR_JNI_EXCEPTION,
        g_verbose_logging_enabled);  // IOException
    auto reader =
        java::io::BufferedReader(java::io::InputStreamReader(is, "UTF-8"));
    std::stringstream body;
    while (true) {
        auto line = reader.readLine();
        if (line.J() == nullptr) break;
        body << line.C() << "\n";
    }

    reader.close();
    is.close();
    connection.disconnect();

    response_body = body.str();

    return TUNINGFORK_ERROR_OK;
}

}  // namespace tuningfork
