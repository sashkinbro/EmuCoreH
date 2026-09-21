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

#include "predictor.h"

#include <stdlib.h>

#include <algorithm>
#include <fstream>
#include <iterator>
#include <map>
#include <sstream>
#include <streambuf>
#include <string>

#include "Log.h"
#include "apk_utils.h"
#include "jni/jni_wrap.h"
#include "memory_advice/memory_advice.h"
#include "tensorflow/lite/c/c_api.h"
#include "tensorflow/lite/c/c_api_experimental.h"
#include "tensorflow/lite/c/c_api_types.h"
#include "tensorflow/lite/c/common.h"

#define LOG_TAG "MemoryAdvice:DeviceProfiler"

namespace memory_advice {

using namespace json11;

MemoryAdvice_ErrorCode DefaultPredictor::Init(std::string model_file,
                                              std::string features_file) {
    apk_utils::NativeAsset features_asset(features_file.c_str());

    if (!features_asset.IsValid()) {
        return MEMORYADVICE_ERROR_TFLITE_MODEL_INVALID;
    }

    // Get the features list from the corresponding asset,
    // which is a list of strings denoted with quotation marks
    std::string features_string(
        static_cast<const char*>(AAsset_getBuffer(features_asset)));

    // remove the extra bits from the beginning and end of the files
    // including the brackets
    features_string =
        features_string.substr(features_string.find_first_of('\n') + 1);
    features_string =
        features_string.substr(0, features_string.find_first_of(']'));
    int pos = 0;

    // Iterate over the list, searching for quotation marks to figure out
    // where each string begins and ends. This operation ends with all the
    // features placed inside a vector<string>
    while ((pos = features_string.find_first_of('\n')) != std::string::npos) {
        std::string line(features_string.substr(0, pos));
        features.push_back(
            line.substr(line.find_first_of("/") + 1,
                        line.find_last_of("\"") - line.find_first_of("/") - 1));
        features_string = features_string.substr(pos + 1);
    }

    // Read the tflite model from the given asset file
    model_asset = std::make_unique<apk_utils::NativeAsset>(model_file.c_str());

    if (!model_asset->IsValid()) {
        return MEMORYADVICE_ERROR_TFLITE_MODEL_INVALID;
    }
    const char* model_buffer =
        static_cast<const char*>(AAsset_getBuffer(*model_asset));
    const size_t model_capacity =
        static_cast<size_t>(AAsset_getLength(*model_asset));

    // Create a tensorflow lite model using the asset file

    model = TfLiteModelCreate(model_buffer, model_capacity);

    options = TfLiteInterpreterOptionsCreate();

    // Create a tensorflow lite interpreter from the model

    interpreter = TfLiteInterpreterCreate(model, options);

    // Finally, resize the input of the model; which is just the number of
    // available features

    int sizes = features.size();
    TfLiteInterpreterResizeInputTensor(interpreter, 0, &sizes, 1);
    TfLiteInterpreterAllocateTensors(interpreter);

    return MEMORYADVICE_ERROR_OK;
}

DefaultPredictor::~DefaultPredictor() {
    // Dispose of the model and interpreter objects.
    TfLiteInterpreterDelete(interpreter);
    TfLiteInterpreterOptionsDelete(options);
    TfLiteModelDelete(model);
}

float IPredictor::GetFromPath(std::string feature, Json::object data) {
    int pos = 0;
    const Json::object* search = &data;
    while ((pos = feature.find_first_of("/")) != std::string::npos) {
        search = &(search->at(feature.substr(0, pos)).object_items());
        feature = feature.substr(pos + 1);
    }

    if (feature.length() > 4 && feature.substr(feature.length() - 4) == "Norm") {
        Json result = search->at(feature.substr(0, feature.length() - 4));

        if (result.is_number()) {
            return static_cast<float>(result.number_value()) /
                GetFromPath("baseline/constant/MemoryInfo/totalMem", data);
        } else {
            return 0.0f;
        }
    } else {
        Json result = search->at(feature);

        if (result.is_number()) {
            return static_cast<float>(result.number_value());
        } else if (result.is_bool()) {
            return result.bool_value() ? 1.0f : 0.0f;
        } else {
            return 0.0f;
        }
    }
}

float DefaultPredictor::Predict(Json::object data) {
    float input_data[features.size()];

    for (int idx = 0; idx != features.size(); idx++) {
        input_data[idx] = GetFromPath(features[idx], data);
    }
    TfLiteTensor* input_tensor =
        TfLiteInterpreterGetInputTensor(interpreter, 0);
    TfLiteTensorCopyFromBuffer(input_tensor, input_data,
                               features.size() * sizeof(float));

    TfLiteInterpreterInvoke(interpreter);

    float output_data;

    const TfLiteTensor* output_tensor =
        TfLiteInterpreterGetOutputTensor(interpreter, 0);
    TfLiteTensorCopyToBuffer(output_tensor, &output_data, 1 * sizeof(float));

    return output_data;
}

}  // namespace memory_advice