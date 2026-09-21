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

#include <cstdint>
#include <string>
#include <vector>

namespace tuningfork {

namespace annotation_util {

typedef uint64_t AnnotationId;
typedef std::vector<uint8_t> SerializedAnnotation;
constexpr int kKeyError = -1;
constexpr AnnotationId kAnnotationError = 0xffffffff;
constexpr uint64_t kStreamError = -1;

enum ErrorCode { NO_ERROR = 0, BAD_SERIALIZATION = 1, BAD_INDEX = 2 };

// Returns kAnnotationError if unsuccessful
AnnotationId DecodeAnnotationSerialization(
    const SerializedAnnotation& ser, const std::vector<uint32_t>& radix_mult,
    int32_t loading_annotation_index = -1, int32_t level_annotation_index = -1,
    bool* loading = nullptr);

ErrorCode SerializeAnnotationId(uint64_t id, SerializedAnnotation& ser,
                                const std::vector<uint32_t>& radix_mult);

void SetUpAnnotationRadixes(std::vector<uint32_t>& radix_mult,
                            const std::vector<uint32_t>& enum_sizes);

ErrorCode Value(uint64_t id, uint32_t index,
                const std::vector<uint32_t>& radix_mult, int& value);

// Parse the dev_tuningfork.descriptor file in order to find enum sizes.
// Returns true is successful, false if not.
bool GetEnumSizesFromDescriptors(std::vector<uint32_t>& enum_sizes);

// Get a human-readable representation of an annotation.
std::string HumanReadableAnnotation(const SerializedAnnotation& annotation);

}  // namespace annotation_util

}  // namespace tuningfork
