/*
 * Copyright 2020 The Android Open Source Project
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

#include "request_info.h"

#include <cmath>
#include <fstream>
#include <memory>
#include <sstream>

#include "jni/jni_wrap.h"
#include "system_utils.h"
#include "tuningfork_utils.h"

#define LOG_TAG "TuningFork"
#include "Log.h"

using namespace gamesdk::jni;

namespace {

std::string slurpFile(const char* fname) {
    std::ifstream f(fname);
    if (f.good()) {
        std::stringstream str;
        str << f.rdbuf();
        return str.str();
    }
    return "";
}

const char* skipSpace(const char* q) {
    while (*q && (*q == ' ' || *q == '\t')) ++q;
    return q;
}

}  // anonymous namespace

namespace tuningfork {

/* static */
RequestInfo RequestInfo::ForThisGameAndDevice(const Settings& settings) {
    RequestInfo info{};
    // Total memory
    if (gamesdk::jni::IsValid()) {
        android::app::MemoryInfo memory_info;
        java::Object obj = AppContext().getSystemService(
            android::content::Context::ACTIVITY_SERVICE);
        android::app::ActivityManager activity_manager(std::move(obj));
        activity_manager.getMemoryInfo(memory_info);
        info.total_memory_bytes = (uint64_t)memory_info.totalMem();
    }
    info.build_version_sdk = gamesdk::GetSystemProp("ro.build.version.sdk");
    info.build_fingerprint = gamesdk::GetSystemProp("ro.build.fingerprint");

    if (gamesdk::jni::IsValid()) {
        std::stringstream session_id_path;
        session_id_path << file_utils::GetAppCacheDir() << "/tuningfork";
        file_utils::CheckAndCreateDir(session_id_path.str());
        session_id_path << "/session_id.bin";
        std::string session_id_file = session_id_path.str();

        if (!file_utils::FileExists(session_id_file)) {
            info.previous_session_id = "";
        } else {
            std::ifstream file(session_id_file);
            file >> info.previous_session_id;
        }

        info.session_id = UniqueId();

        std::ofstream file(session_id_file);
        if (file.is_open()) {
            file << info.session_id;
        } else {
            ALOGE_ONCE("Session id couldn't be stored.");
        }
    }

    info.cpu_max_freq_hz.clear();
    for (int index = 0;; ++index) {
        std::stringstream str;
        str << "/sys/devices/system/cpu/cpu" << index
            << "/cpufreq/cpuinfo_max_freq";
        auto cpu_freq_file = slurpFile(str.str().c_str());
        if (cpu_freq_file.empty()) break;
        uint64_t freq;
        std::istringstream cstr(cpu_freq_file);
        cstr >> freq;
        info.cpu_max_freq_hz.push_back(freq * 1000);  // File is in kHz
    }

    if (gamesdk::jni::IsValid()) {
        using namespace gamesdk::jni;

        info.apk_version_code = apk_utils::GetVersionCode(
            &info.apk_package_name, &info.gl_es_version);
        info.model = android::os::Build::MODEL().C();
        info.brand = android::os::Build::BRAND().C();
        info.product = android::os::Build::PRODUCT().C();
        info.device = android::os::Build::DEVICE().C();
        if (gamesdk::GetSystemPropAsInt("ro.build.version.sdk") >= 31) {
            info.soc_model = android::os::Build::SOC_MODEL().C();
            info.soc_manufacturer = android::os::Build::SOC_MANUFACTURER().C();
        }

        android::util::DisplayMetrics display_metrics;

        java::Object win_obj = AppContext().getSystemService(
            android::content::Context::WINDOW_SERVICE);

        if (win_obj.valid()) {
            android::view::WindowManager window_manager(std::move(win_obj));
            window_manager.getDefaultDisplay().getMetrics(display_metrics);
            info.height_pixels = display_metrics.heightPixels();
            info.width_pixels = display_metrics.widthPixels();
        } else {
            ALOGE(
                "Unable to get WindowManager service, width and height will be "
                "set to 0");
        }
    }
    info.tuningfork_version = TUNINGFORK_PACKED_VERSION;
    info.swappy_version = settings.c_settings.swappy_version;
    return info;
}

static RequestInfo s_request_info;

/*static*/ RequestInfo& RequestInfo::CachedValue() { return s_request_info; }

void RequestInfo::UpdateMemoryValues(IMemInfoProvider* meminfo_provider) {
    meminfo_provider->UpdateMemInfo();
    swap_total_bytes = meminfo_provider->GetMemInfoSwapTotalBytes();
}

}  // namespace tuningfork
