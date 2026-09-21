/*
 * Copyright 2021 The Android Open Source Project
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

#include "battery_provider.h"

#include "Log.h"
#include "jni/jni_wrap.h"
#include "system_utils.h"

#define LOG_TAG "TuningFork"

namespace tuningfork {

int32_t DefaultBatteryProvider::GetBatteryPercentage() {
    using namespace gamesdk::jni;

    android::content::BroadcastReceiver broadcast_receiver(nullptr);
    android::content::IntentFilter intent_filter(
        android::content::Intent::ACTION_BATTERY_CHANGED);
    java::Object obj =
        AppContext().registerReceiver(broadcast_receiver, intent_filter);
    if (!obj.IsNull()) {
        android::content::Intent battery_intent(std::move(obj));
        return (100 * battery_intent.getIntExtra(
                          android::os::BatteryManager::EXTRA_LEVEL, 0)) /
               battery_intent.getIntExtra(
                   android::os::BatteryManager::EXTRA_SCALE, 100);
    } else {
        return 0;
    }
}

int32_t DefaultBatteryProvider::GetBatteryCharge() {
    if (gamesdk::GetSystemPropAsInt("ro.build.version.sdk") >= 21) {
        using namespace gamesdk::jni;
        java::Object obj = AppContext().getSystemService(
            android::content::Context::BATTERY_SERVICE);
        if (!obj.IsNull()) {
            android::os::BatteryManager battery_manager(std::move(obj));
            return battery_manager.getIntProperty(
                android::os::BatteryManager::BATTERY_PROPERTY_CHARGE_COUNTER);
        } else {
            return 0;
        }
    } else {
        return 0;
    }
}

bool DefaultBatteryProvider::IsBatteryCharging() {
    using namespace gamesdk::jni;

    android::content::BroadcastReceiver broadcast_receiver(nullptr);
    android::content::IntentFilter intent_filter(
        android::content::Intent::ACTION_BATTERY_CHANGED);
    java::Object obj =
        AppContext().registerReceiver(broadcast_receiver, intent_filter);
    if (!obj.IsNull()) {
        android::content::Intent battery_intent(std::move(obj));
        return battery_intent.getIntExtra(
            android::os::BatteryManager::EXTRA_PLUGGED, 0);
    } else {
        return false;
    }
}

bool DefaultBatteryProvider::IsPowerSaveModeEnabled() {
    if (gamesdk::GetSystemPropAsInt("ro.build.version.sdk") >= 21) {
        using namespace gamesdk::jni;
        java::Object obj = AppContext().getSystemService(
            android::content::Context::POWER_SERVICE);
        CHECK_FOR_JNI_EXCEPTION_AND_RETURN(false);
        if (!obj.IsNull()) {
            android::os::PowerManager power_manager(std::move(obj));
            return power_manager.isPowerSaveMode();
        } else {
            return false;
        }
    } else {
        return false;
    }
}

IBatteryProvider::ThermalState
DefaultBatteryProvider::GetCurrentThermalStatus() {
    if (gamesdk::GetSystemPropAsInt("ro.build.version.sdk") >= 29 &&
        gamesdk::jni::IsValid()) {
        using namespace gamesdk::jni;
        java::Object obj = AppContext().getSystemService(
            android::content::Context::POWER_SERVICE);
        CHECK_FOR_JNI_EXCEPTION_AND_RETURN(
            IBatteryProvider::THERMAL_STATE_UNSPECIFIED);
        if (!obj.IsNull()) {
            android::os::PowerManager power_manager(std::move(obj));
            int status = power_manager.getCurrentThermalStatus();
            if (status < 0 || status > 6) {
                return IBatteryProvider::THERMAL_STATE_UNSPECIFIED;
            } else {
                // Adding +1 here because we want 0 to be thermal status
                // 'unspecified', which shifts the entire enum by 1
                return static_cast<IBatteryProvider::ThermalState>(status + 1);
            }
        } else {
            return IBatteryProvider::THERMAL_STATE_UNSPECIFIED;
        }
    } else {
        return IBatteryProvider::THERMAL_STATE_UNSPECIFIED;
    }
}

bool DefaultBatteryProvider::IsBatteryReportingEnabled() { return true; }

}  // namespace tuningfork