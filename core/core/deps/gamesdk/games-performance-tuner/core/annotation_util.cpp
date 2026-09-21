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

#include "annotation_util.h"

#include <algorithm>
#include <cstdlib>
#include <map>
#include <sstream>

#include "protobuf_util_internal.h"

#define LOG_TAG "TuningFork"
#include "Log.h"

namespace tuningfork {

namespace annotation_util {

// This is a protobuf 1-based index
int GetKeyIndex(uint8_t b) {
    int type = b & 0x7;
    if (type != 0) return kKeyError;
    return b >> 3;
}

uint64_t GetBase128IntegerFromByteStream(const std::vector<uint8_t>& bytes,
                                         int& index) {
    uint64_t m = 0;
    uint64_t r = 0;
    while (index < bytes.size() && m <= (64 - 7)) {
        auto b = bytes[index];
        r |= (((uint64_t)b) & 0x7f) << m;
        if ((b & 0x80) != 0)
            m += 7;
        else
            return r;
        ++index;
    }
    return kStreamError;
}

void WriteBase128IntToStream(uint64_t x, std::vector<uint8_t>& bytes) {
    do {
        uint8_t a = x & 0x7f;
        int b = x & 0xffffffffffffff80;
        if (b) {
            bytes.push_back(a | 0x80);
            x >>= 7;
        } else {
            bytes.push_back(a);
            return;
        }
    } while (x);
}

// Decode into a list of key-value pairs. Note that the keys are 1-based, as in
// the proto.
bool RawDecodeAnnotationSerialization(
    const SerializedAnnotation& ser, std::vector<std::pair<int, int>>& result) {
    result.clear();
    for (int i = 0; i < ser.size(); ++i) {
        int key = GetKeyIndex(ser[i]);
        if (key == kKeyError) return false;
        ++i;
        if (i >= ser.size()) return false;
        uint64_t value = GetBase128IntegerFromByteStream(ser, i);
        if (value == kStreamError) return false;
        result.push_back({key, static_cast<int>(value)});
    }
    return true;
}

AnnotationId DecodeAnnotationSerialization(
    const SerializedAnnotation& ser, const std::vector<uint32_t>& radix_mult,
    int loading_annotation_index, int level_annotation_index,
    bool* loading_out) {
    AnnotationId result = 0;
    AnnotationId result_if_loading = 0;
    bool loading = false;
    for (int i = 0; i < ser.size(); ++i) {
        int key = GetKeyIndex(ser[i]);
        if (key == kKeyError) return kAnnotationError;
        // Convert to 0-based index
        --key;
        if (key >= radix_mult.size()) return kAnnotationError;
        ++i;
        if (i >= ser.size()) return kAnnotationError;
        uint64_t value = GetBase128IntegerFromByteStream(ser, i);
        if (value == kStreamError) return kAnnotationError;
        // Check the range of the value
        if (value == 0 || value >= radix_mult[key]) return kAnnotationError;
        // We don't allow enums with more that 255 values
        if (value > 0xff) return kAnnotationError;
        if (loading_annotation_index == key) {
            loading = value > 1;
        }
        AnnotationId v;
        if (key > 0)
            v = radix_mult[key - 1] * value;
        else
            v = value;
        result += v;
        // Only the loading value and the level value are used when loading.
        if (loading_annotation_index == key || level_annotation_index == key)
            result_if_loading += v;
    }
    if (loading_out != nullptr) {
        *loading_out = loading;
    }
    if (loading)
        return result_if_loading;
    else
        return result;
}

ErrorCode SerializeAnnotationId(uint64_t id, SerializedAnnotation& ser,
                                const std::vector<uint32_t>& radix_mult) {
    uint64_t x = id;
    size_t n = radix_mult.size();
    std::vector<uint32_t> v(n);
    for (int i = n - 1; i > 0; --i) {
        auto r = ::lldiv(x, (uint64_t)radix_mult[i - 1]);
        v[i] = r.quot;
        x = r.rem;
    }
    v[0] = x;
    for (int i = 0; i < n; ++i) {
        auto value = v[i];
        if (value > 0) {
            int key = (i + 1) << 3;
            ser.push_back(key);
            WriteBase128IntToStream(value, ser);
        }
    }
    return NO_ERROR;
}

ErrorCode Value(uint64_t id, uint32_t index,
                const std::vector<uint32_t>& radix_mult, int& value) {
    uint64_t x = id;
    int ix = index;
    for (int i = 0; i < radix_mult.size(); ++i) {
        auto r = ::lldiv(x, (uint64_t)radix_mult[i]);
        if (ix == 0) {
            value = r.rem;
            return NO_ERROR;
        }
        --ix;
        x = r.quot;
    }
    return BAD_INDEX;
}

void SetUpAnnotationRadixes(std::vector<uint32_t>& radix_mult,
                            const std::vector<uint32_t>& enum_sizes) {
    ALOGV("Settings::annotation_enum_size");
    for (int i = 0; i < enum_sizes.size(); ++i) {
        ALOGV("%d", enum_sizes[i]);
    }
    auto n = enum_sizes.size();
    if (n == 0) {
        // With no annotations, we just have 1 possible histogram per key
        radix_mult.resize(1);
        radix_mult[0] = 1;
    } else {
        radix_mult.resize(n);
        int r = 1;
        for (int i = 0; i < n; ++i) {
            r *= enum_sizes[i] + 1;
            radix_mult[i] = r;
        }
    }
}

// Parse the dev_tuningfork.descriptor file in order to find enum sizes.
// Returns true is successful, false if not.
bool GetEnumSizesFromDescriptors(std::vector<uint32_t>& enum_sizes) {
    using namespace file_descriptor;
    File* file = GetTuningForkFileDescriptor();
    if (file == nullptr) return false;
    enum_sizes.clear();
    ALOGV("Searching for Annotation in TuningFork descriptor");
    for (auto& m : file->message_type) {
        if (m.name == "Annotation") {
            std::sort(m.fields.begin(), m.fields.end(),
                      [](const EnumField& a, const EnumField& b) {
                          return a.number < b.number;
                      });
            for (auto const& e : m.fields) {
                std::string n = e.type_name;
                // Strip off the package name
                std::string this_package = "." + file->package;
                if (n.find_first_of(this_package) == 0)
                    n = n.substr(this_package.size() + 1);
                for (auto const& enums_in_desc : file->enum_type) {
                    if (enums_in_desc.name == n) {
                        int max_value = 0;
                        for (auto const& v : enums_in_desc.value) {
                            if (v.second > max_value) max_value = v.second;
                        }
                        enum_sizes.push_back(max_value + 1);
                    }
                }
            }
            break;
        }
    }
    if (enum_sizes.size() == 0) return false;
    std::stringstream ostr;
    ostr << '[';
    for (auto const& e : enum_sizes) {
        ostr << e << ',';
    }
    ostr << ']';
    ALOGI("Found annotation enum sizes in descriptor: %s", ostr.str().c_str());
    return true;
}

std::string HumanReadableAnnotation(
    const SerializedAnnotation& annotation_ser) {
    using namespace file_descriptor;
    std::stringstream result;
    std::vector<std::pair<int, int>> annotation;
    if (!RawDecodeAnnotationSerialization(annotation_ser, annotation)) {
        result << "Error decoding annotation";
    } else {
        File* file = GetTuningForkFileDescriptor();
        if (file == nullptr) {
            result << "Error getting file descriptor";
        } else {
            for (auto& m : file->message_type) {
                if (m.name == "Annotation") {
                    result << "{";
                    bool first = true;
                    for (auto& afield : annotation) {
                        auto key = afield.first;
                        auto value = afield.second;
                        const EnumField* field;
                        if (m.GetField(key, &field)) {
                            auto value_str = file->GetEnumValueString(
                                field->type_name, value);
                            if (first)
                                first = false;
                            else
                                result << ",";
                            result << field->name << ":" << value_str;
                        }
                    }
                    result << "}";
                }
                break;
            }
        }
    }
    return result.str();
}

}  // namespace annotation_util

}  // namespace tuningfork
