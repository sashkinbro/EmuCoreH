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

#include "core/annotation_util.h"
#include "core/protobuf_util_internal.h"
#include "gtest/gtest.h"

using namespace tuningfork;
namespace fd = tuningfork::file_descriptor;
namespace an = tuningfork::annotation_util;

extern "C" bool init_jni_for_tests();

TEST(AnnotationDescriptor, LoadTuningForkFileDescriptorSerialization) {
    if (init_jni_for_tests()) {
        EXPECT_TRUE(fd::GetTuningForkFileDescriptorSerialization() != nullptr);
    }
}

TEST(AnnotationDescriptor, LoadTuningForkFileDescriptor) {
    if (init_jni_for_tests()) {
        EXPECT_TRUE(fd::GetTuningForkFileDescriptor() != nullptr);
    }
}

TEST(AnnotationDescriptor, GetEnumSizesFromDescriptors) {
    if (init_jni_for_tests()) {
        std::vector<uint32_t> expected_enum_sizes = {3, 4};
        std::vector<uint32_t> enum_sizes;
        EXPECT_TRUE(an::GetEnumSizesFromDescriptors(enum_sizes));
        EXPECT_TRUE(expected_enum_sizes.size() == enum_sizes.size());
        for (int i = 0; i < enum_sizes.size(); ++i) {
            EXPECT_TRUE(enum_sizes[i] == expected_enum_sizes[i]);
        }
    }
}

TEST(AnnotationDescriptor, HumanReadable) {
    if (init_jni_for_tests()) {
        std::vector<uint32_t> radix_mult;
        std::vector<uint32_t> enum_sizes = {3, 4};
        EXPECT_TRUE(an::GetEnumSizesFromDescriptors(enum_sizes));
        an::SetUpAnnotationRadixes(radix_mult, enum_sizes);
        std::vector<std::string> expected = {
            "{}",
            "{loading:NOT_LOADING}",
            "{loading:LOADING}",
            "{loading:Error}",
            "{level:LEVEL_1}",
            "{loading:NOT_LOADING,level:LEVEL_1}",
            "{loading:LOADING,level:LEVEL_1}",
            "{loading:Error,level:LEVEL_1}",
            "{level:LEVEL_2}",
            "{loading:NOT_LOADING,level:LEVEL_2}",
            "{loading:LOADING,level:LEVEL_2}",
            "{loading:Error,level:LEVEL_2}",
            "{level:LEVEL_3}",
            "{loading:NOT_LOADING,level:LEVEL_3}",
            "{loading:LOADING,level:LEVEL_3}",
            "{loading:Error,level:LEVEL_3}"};
        for (an::AnnotationId id = 0; id < expected.size(); ++id) {
            an::SerializedAnnotation ser;
            an::SerializeAnnotationId(id, ser, radix_mult);
            auto hr = an::HumanReadableAnnotation(ser);
            EXPECT_EQ(hr, expected[id]);
        }
    }
}
