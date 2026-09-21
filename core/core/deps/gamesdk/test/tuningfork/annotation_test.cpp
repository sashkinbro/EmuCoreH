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

#include "core/annotation_util.h"
#include "gtest/gtest.h"

using namespace tuningfork::annotation_util;

std::vector<uint32_t> TestSetup(const std::vector<uint32_t>& enum_sizes,
                                const std::vector<uint32_t>& expected) {
    std::vector<uint32_t> radix_mult;
    SetUpAnnotationRadixes(radix_mult, enum_sizes);
    EXPECT_EQ(radix_mult, expected);
    return radix_mult;
}

TEST(Annotation, Setup) {
    TestSetup({}, {1});
    TestSetup({1}, {2});
    TestSetup({1, 1}, {2, 4});
    TestSetup({1, 1, 1}, {2, 4, 8});
    TestSetup({2, 1, 1}, {3, 6, 12});
}

void CheckEncodeDecode(AnnotationId id, const std::vector<uint32_t>& radix_mult,
                       const std::string& err) {
    SerializedAnnotation ser;
    EXPECT_EQ(SerializeAnnotationId(id, ser, radix_mult), 0)
        << err << ": error serializing";
    auto back = DecodeAnnotationSerialization(ser, radix_mult);
    EXPECT_EQ(id, back) << err;
}
TEST(Annotation, EncodeDecodeGood) {
    auto radix_mult = TestSetup({2, 3, 4}, {3, 12, 60});
    CheckEncodeDecode(1, radix_mult, "First");
    CheckEncodeDecode(2, radix_mult, "Second");
    CheckEncodeDecode(3, radix_mult, "Third");
}
void CheckDecodeEncode(SerializedAnnotation ser,
                       const std::vector<uint32_t>& radix_mult,
                       const std::string& err) {
    auto id = DecodeAnnotationSerialization(ser, radix_mult);
    SerializedAnnotation ser_out;
    EXPECT_EQ(SerializeAnnotationId(id, ser_out, radix_mult), 0)
        << err << ": error serializing";
    EXPECT_EQ(ser, ser_out) << err;
}
void CheckGood(SerializedAnnotation ser, AnnotationId id,
               const std::vector<uint32_t>& radix_mult) {
    auto back = DecodeAnnotationSerialization(ser, radix_mult);
    EXPECT_EQ(back, id) << "Good";
}
void CheckBad(SerializedAnnotation ser,
              const std::vector<uint32_t>& radix_mult) {
    auto back = DecodeAnnotationSerialization(ser, radix_mult);
    EXPECT_EQ(back, kAnnotationError) << "Bad";
}
TEST(Annotation, Decode) {
    auto radix_mult = TestSetup({2, 3, 4}, {3, 12, 60});
    CheckBad({1}, radix_mult);
    CheckBad({5 << 3, 0}, radix_mult);
    CheckBad({1 << 3, 0}, radix_mult);
    CheckBad({1 << 3, 8}, radix_mult);
    CheckBad({1 << 3, 1, 2 << 3, 2, 3 << 3}, radix_mult);
    CheckBad({1 << 3, 1, 2 << 3, 2, 3 << 3, 3, 0}, radix_mult);
    CheckGood({1 << 3, 1}, 1, radix_mult);
    CheckGood({1 << 3, 2}, 2, radix_mult);
    CheckGood({1 << 3, 1, 2 << 3, 1}, 4, radix_mult);
    CheckGood({1 << 3, 2, 2 << 3, 3, 3 << 3, 4}, 59, radix_mult);
}

void FullCheck(SerializedAnnotation ser, AnnotationId id,
               const std::vector<uint32_t>& radix_mult,
               const std::string& err) {
    CheckGood(ser, id, radix_mult);
    CheckEncodeDecode(id, radix_mult, err + "_ED");
    CheckDecodeEncode(ser, radix_mult, err + "_DE");
}

TEST(Annotation, RealWorldUnity) {
    auto radix_mult = TestSetup({10, 3, 2, 3}, {11, 44, 132, 528});

    FullCheck({8, 6, 16, 1, 24, 1, 32, 2}, 325, radix_mult, "Unity1");
    FullCheck({8, 7, 16, 2, 24, 1, 32, 2}, 337, radix_mult, "Unity2");
    FullCheck({8, 8, 16, 1, 24, 1, 32, 2}, 327, radix_mult, "Unity3");
}

TEST(Annotation, WithLoadingAnnotationIndex) {
    auto radix_mult = TestSetup({2, 3, 4}, {3, 12, 60});

    // No loading_annotation_index
    // result = 2 + 3*3 + 4*12
    EXPECT_EQ(DecodeAnnotationSerialization({1 << 3, 2, 2 << 3, 3, 3 << 3, 4},
                                            radix_mult),
              59)
        << "Loading";

    // With loading_annotation_index = 0

    // No level_annotation_index. Other values are zeroed.
    // result = 2 + 0*3*3 + 0*4*12
    EXPECT_EQ(DecodeAnnotationSerialization({1 << 3, 2, 2 << 3, 3, 3 << 3, 4},
                                            radix_mult, 0),
              2)
        << "Loading 0-";
    // With level_annotation_index = 1
    // result = 2 + 3*3 + 0*4*12
    EXPECT_EQ(DecodeAnnotationSerialization({1 << 3, 2, 2 << 3, 3, 3 << 3, 4},
                                            radix_mult, 0, 1),
              11)
        << "Loading 00";
    // With level_annotation_index = 2
    // result = 2 + 0*3*3 + 4*12
    EXPECT_EQ(DecodeAnnotationSerialization({1 << 3, 2, 2 << 3, 3, 3 << 3, 4},
                                            radix_mult, 0, 2),
              50)
        << "Loading 01";

    // With loading_annotation_index = 1

    // No level_annotation_index. Other values are zeroed.
    // result = 0*2 + 3*3 + 0*4*12
    EXPECT_EQ(DecodeAnnotationSerialization({1 << 3, 2, 2 << 3, 3, 3 << 3, 4},
                                            radix_mult, 1),
              9)
        << "Loading 1-";
    // With level_annotation_index = 0
    // result = 2 + 3*3 + 0*4*12
    EXPECT_EQ(DecodeAnnotationSerialization({1 << 3, 2, 2 << 3, 3, 3 << 3, 4},
                                            radix_mult, 1, 0),
              11)
        << "Loading 10";
    // With level_annotation_index = 2
    // result = 0*2 + 3*3 + 4*12
    EXPECT_EQ(DecodeAnnotationSerialization({1 << 3, 2, 2 << 3, 3, 3 << 3, 4},
                                            radix_mult, 1, 2),
              57)
        << "Loading 12";

    // With loading_annotation_index = 2

    // No level_annotation_index. Other values are zeroed.
    // result = 0*2 + 0*3*3 + 4*12
    EXPECT_EQ(DecodeAnnotationSerialization({1 << 3, 2, 2 << 3, 3, 3 << 3, 4},
                                            radix_mult, 2),
              48)
        << "Loading 2-";
    // With level_annotation_index = 0
    // result = 2 + 0*3*3 + 4*12
    EXPECT_EQ(DecodeAnnotationSerialization({1 << 3, 2, 2 << 3, 3, 3 << 3, 4},
                                            radix_mult, 2, 0),
              50)
        << "Loading 20";
    // With level_annotation_index = 1
    // result = 0*2 + 3*3 + 4*12
    EXPECT_EQ(DecodeAnnotationSerialization({1 << 3, 2, 2 << 3, 3, 3 << 3, 4},
                                            radix_mult, 2, 1),
              57)
        << "Loading 21";
}
