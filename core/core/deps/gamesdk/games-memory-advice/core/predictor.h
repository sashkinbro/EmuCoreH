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

#pragma once

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#include <memory>
#include <string>
#include <vector>

#include "apk_utils.h"
#include "json11/json11.hpp"
#include "memory_advice/memory_advice.h"
#include "tensorflow/lite/c/c_api.h"
#include "tensorflow/lite/c/c_api_experimental.h"
#include "tensorflow/lite/c/c_api_types.h"
#include "tensorflow/lite/c/common.h"

namespace memory_advice {

using namespace json11;

/**
A class to help predict memory limits using tensorflow lite models.
*/
class IPredictor {
 public:
  /**
   * Initializes the predictor with the given model.
   *
   * @param model_file the location of the asset containing a predictor model
   * file
   * @param features_file the location of the asset containing the feature
   * list matching the model
   * @return MEMORYADVICE_ERROR_TFLITE_MODEL_INVALID if the provided model was
   * invalid, or MEMORYADVICE_ERROR_OK if there are no errors.
   */
  virtual MemoryAdvice_ErrorCode Init(std::string model_file,
                                      std::string features_file) = 0;

  /**
   * Runs the tensorflow model with the provided data.
   *
   * @param data the memory data from the device.
   * @return the result from the model.
   */
  virtual float Predict(Json::object data) = 0;

  virtual ~IPredictor() {}

 protected:
  float GetFromPath(std::string feature, Json::object data);
};

class DefaultPredictor : public IPredictor {
 private:
  std::vector<std::string> features;
  TfLiteModel* model;
  TfLiteInterpreterOptions* options;
  TfLiteInterpreter* interpreter;
  std::unique_ptr<apk_utils::NativeAsset> model_asset;

 public:
  MemoryAdvice_ErrorCode Init(std::string model_file,
                              std::string features_file) override;
  float Predict(Json::object data) override;
  ~DefaultPredictor() override;
};

}  // namespace memory_advice