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

#include "jni/jnictx.h"
#include "memory_advice_impl.h"

#include <algorithm>
#include <chrono>

#include "memory_advice_utils.h"
#include "system_utils.h"

namespace memory_advice {

using namespace json11;


namespace {
    template <typename T> T Clamp(T val, T min, T max) {
        return (val < min ? min : (val > max ? max : val));
    }
}

MemoryAdviceImpl::MemoryAdviceImpl(const char* params,
                                   IMetricsProvider* metrics_provider,
                                   IPredictor* realtime_predictor,
                                   IPredictor* available_predictor)
    : metrics_provider_(metrics_provider),
      available_predictor_(available_predictor) {
    if (metrics_provider_ == nullptr) {
        default_metrics_provider_ = std::make_unique<DefaultMetricsProvider>();
        metrics_provider_ = default_metrics_provider_.get();
    }
    if (available_predictor_ == nullptr) {
        default_available_predictor_ = std::make_unique<DefaultPredictor>();
        available_predictor_ = default_available_predictor_.get();
    }

    initialization_error_code_ = available_predictor_->Init(
        "available.tflite", "available_features.json");
    if (initialization_error_code_ != MEMORYADVICE_ERROR_OK) {
        return;
    }
    initialization_error_code_ = ProcessAdvisorParameters(params);
    if (initialization_error_code_ != MEMORYADVICE_ERROR_OK) {
        return;
    }
    baseline_ = GenerateBaselineMetrics();
    baseline_["constant"] = GenerateConstantMetrics();
    build_ = utils::GetBuildInfo();
}

MemoryAdvice_ErrorCode MemoryAdviceImpl::ProcessAdvisorParameters(
    const char* parameters) {
    std::string err;
    advisor_parameters_ = Json::parse(parameters, err).object_items();
    if (!err.empty()) {
        ALOGE("Error while parsing advisor parameters: %s", err.c_str());
        return MEMORYADVICE_ERROR_ADVISOR_PARAMETERS_INVALID;
    }
    return MEMORYADVICE_ERROR_OK;
}

MemoryAdvice_MemoryState MemoryAdviceImpl::GetMemoryState() {
    Json::object advice = GetAdvice();
    if (advice.find("warnings") != advice.end()) {
        Json::array warnings = advice["warnings"].array_items();
        for (auto& it : warnings) {
            if (it.object_items().at("level").string_value() == "red") {
                return MEMORYADVICE_STATE_CRITICAL;
            }
        }
        return MEMORYADVICE_STATE_APPROACHING_LIMIT;
    }
    return MEMORYADVICE_STATE_OK;
}

int64_t MemoryAdviceImpl::GetAvailableMemory() {
    return static_cast<int64_t>(GetPercentageAvailableMemory() / 100.0f * GetTotalMemory());
}

float MemoryAdviceImpl::GetPercentageAvailableMemory() {
    Json::object advice = GetAdvice();
    if (advice.find("metrics") != advice.end()) {
        Json::object metrics = advice["metrics"].object_items();
        if (metrics.find("predictedAvailable") != metrics.end()) {
            return static_cast<float>(
                metrics["predictedAvailable"].number_value()) * 100.0f;
        }
    }
    return 0.0f;
}

int64_t MemoryAdviceImpl::GetTotalMemory() {
    return static_cast<int64_t>(baseline_.at("constant")
                                    .object_items()
                                    .at("MemoryInfo")
                                    .object_items()
                                    .at("totalMem")
                                    .number_value());
}

Json::object MemoryAdviceImpl::GetAdvice() {
    CheckCancelledWatchers();

    std::lock_guard<std::mutex> lock(advice_mutex_);

    // Make sure current thread is attached to the JVM.
    // This is important because we perform many JNI calls here to get system metrics.
    gamesdk::jni::Ctx::Instance()->Env();

    double start_time = MillisecondsSinceEpoch();
    Json::object advice;
    Json::object data;
    Json::object variable_spec = advisor_parameters_.at("metrics")
                                     .object_items()
                                     .at("variable")
                                     .object_items();
    Json::object variable_metrics = GenerateVariableMetrics();

    data["baseline"] = baseline_;
    data["sample"] = variable_metrics;
    data["build"] = build_;

    if (variable_spec.find("availableRealtime") != variable_spec.end() &&
        variable_spec.at("availableRealtime").bool_value()) {
        variable_metrics["predictedAvailable"] =
            Json(Clamp(available_predictor_->Predict(data), 0.0f, 1.0f));
    }
    Json::array warnings;
    Json::object heuristics =
        advisor_parameters_.at("heuristics").object_items();

    if (heuristics.find("formulas") != heuristics.end()) {
        for (auto& entry : heuristics["formulas"].object_items()) {
            for (auto& formula_object : entry.second.array_items()) {
                std::string formula = formula_object.string_value();
                formula.erase(std::remove_if(formula.begin(), formula.end(),
                                             (int (*)(int))std::isspace),
                              formula.end());
                if (utils::EvaluateBoolean(formula, variable_metrics)) {
                    Json::object warning;
                    warning["formula"] = formula;
                    warning["level"] = entry.first;
                    warnings.push_back(warning);
                }
            }
        }
    }

    if (!warnings.empty()) {
        advice["warnings"] = warnings;
    }

    advice["metrics"] = variable_metrics;
    return advice;
}

Json MemoryAdviceImpl::GetValue(Json::object object, std::string key) {
    if (object.find(key) != object.end()) {
        return object[key];
    }
    for (auto& it : object) {
        Json value = GetValue(it.second.object_items(), key);
        if (!value.is_null()) {
            return value;
        }
    }
    return Json();
}

Json::object MemoryAdviceImpl::GenerateMetricsFromFields(Json::object fields) {
    Json::object metrics;
    for (auto& it : metrics_provider_->metrics_categories_) {
        if (fields.find(it.first) != fields.end()) {
            metrics[it.first] = ExtractValues(it.second, fields[it.first]);
        }
    }
    metrics["meta"] = (Json::object){{"time", MillisecondsSinceEpoch()}};
    return metrics;
}

Json::object MemoryAdviceImpl::ExtractValues(
    IMetricsProvider::MetricsFunction metrics_function, Json fields) {
    double start_time = MillisecondsSinceEpoch();
    Json::object metrics = (metrics_provider_->*metrics_function)();
    Json::object extracted_metrics;
    if (fields.bool_value()) {
        extracted_metrics = metrics;
    } else {
        for (auto& it : fields.object_items()) {
            if (it.second.bool_value() &&
                metrics.find(it.first) != metrics.end()) {
                extracted_metrics[it.first] = metrics[it.first];
            }
        }
    }

    extracted_metrics["_meta"] = {
        {"duration", Json(MillisecondsSinceEpoch() - start_time)}};
    return extracted_metrics;
}

double MemoryAdviceImpl::MillisecondsSinceEpoch() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch())
        .count();
}

Json::object MemoryAdviceImpl::GenerateVariableMetrics() {
    return GenerateMetricsFromFields(advisor_parameters_.at("metrics")
                                         .object_items()
                                         .at("variable")
                                         .object_items());
}

Json::object MemoryAdviceImpl::GenerateBaselineMetrics() {
    return GenerateMetricsFromFields(advisor_parameters_.at("metrics")
                                         .object_items()
                                         .at("baseline")
                                         .object_items());
}

Json::object MemoryAdviceImpl::GenerateConstantMetrics() {
    return GenerateMetricsFromFields(advisor_parameters_.at("metrics")
                                         .object_items()
                                         .at("constant")
                                         .object_items());
}

MemoryAdvice_ErrorCode MemoryAdviceImpl::RegisterWatcher(
    uint64_t intervalMillis, MemoryAdvice_WatcherCallback callback,
    void* user_data) {
    std::lock_guard<std::mutex> guard(active_watchers_mutex_);
    active_watchers_.push_back(std::make_unique<StateWatcher>(
        this, callback, user_data, intervalMillis));
    return MEMORYADVICE_ERROR_OK;
}

MemoryAdvice_ErrorCode MemoryAdviceImpl::UnregisterWatcher(
    MemoryAdvice_WatcherCallback callback) {
    // We can't simply erase the watcher because the callback thread might still
    // be running which would mean blocking here for it to finish. So signal to
    // the thread to exit and put the StateWatcher object on a cancelled list
    // that is checked periodically.
    std::lock_guard<std::mutex> guard(active_watchers_mutex_);
    std::vector<WatcherContainer::iterator> to_move;
    // Search for watchers with the same callback.
    for (auto it = active_watchers_.begin(); it != active_watchers_.end();
         ++it) {
        if ((*it)->Callback() == callback) {
            to_move.push_back(it);
        }
    }

    if (to_move.empty()) return MEMORYADVICE_ERROR_WATCHER_NOT_FOUND;

    std::lock_guard<std::mutex> guard2(cancelled_watchers_mutex_);
    for (auto it : to_move) {
        (*it)->Cancel();
        cancelled_watchers_.push_back(
            std::move(*it));  // Put StateWatcher on cancelled list.
        active_watchers_.erase(it);
    }
    return MEMORYADVICE_ERROR_OK;
}

void MemoryAdviceImpl::CheckCancelledWatchers() {
    std::lock_guard<std::mutex> guard(cancelled_watchers_mutex_);
    auto it = cancelled_watchers_.begin();
    while (it != cancelled_watchers_.end()) {
        if (!(*it)->ThreadRunning())
            it = cancelled_watchers_.erase(
                it);  // Calls destructor on StateWatcher
        else
            ++it;
    }
}

}  // namespace memory_advice
