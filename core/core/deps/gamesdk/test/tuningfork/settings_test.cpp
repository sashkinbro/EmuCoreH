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

#include "core/settings.h"

#include <gtest/gtest.h>

#include "lite/tuningfork.pb.h"

using namespace com::google::tuningfork;

namespace tf = tuningfork;

/*
message Histogram {
        optional int32 instrument_key = 1;
        optional float bucket_min = 2;
        optional float bucket_max = 3;
        optional int32 n_buckets = 4;
}
message AggregationStrategy {
        enum Submission {
            UNDEFINED = 0;
            TIME_BASED = 1;
            TICK_BASED = 2;
        }
        optional Submission method = 1;
        optional int32 intervalms_or_count = 2;
        optional int32 max_instrumentation_keys = 3;
        repeated int32 annotation_enum_size = 4;
}
*/

bool operator==(const tf::Settings::Histogram& a,
                const tf::Settings::Histogram& b) {
    return a.bucket_max == b.bucket_max && a.bucket_min == b.bucket_min &&
           a.n_buckets == b.n_buckets && a.instrument_key == b.instrument_key;
}

TEST(SettingsTest, Deserialize) {
    tf::ProtobufSerialization settings_ser;
    Settings settings_proto;
    std::string api_key = "API_KEY";
    std::string overridden_api_key = "overridden API_KEY";
    std::string base_uri = "BASE_URI";
    Settings_AggregationStrategy_Submission method =
        Settings_AggregationStrategy_Submission_TICK_BASED;
    int intervalms_or_count = 1000;
    int max_instrumentation_keys = 16;
    std::vector<int> annotation_enum_sizes = {5, 3, 8};
    auto aggregation_strategy = settings_proto.mutable_aggregation_strategy();
    aggregation_strategy->set_method(method);
    aggregation_strategy->set_intervalms_or_count(intervalms_or_count);
    aggregation_strategy->set_max_instrumentation_keys(
        max_instrumentation_keys);
    for (auto i : annotation_enum_sizes) {
        aggregation_strategy->add_annotation_enum_size(i);
    }
    auto histograms = settings_proto.mutable_histograms();
    auto h = histograms->Add();
    h->set_bucket_min(30);
    h->set_bucket_max(35);
    h->set_n_buckets(1);
    h->set_instrument_key(64000);
    settings_proto.set_base_uri(base_uri);
    settings_proto.set_api_key(api_key);
    settings_ser.resize(settings_proto.ByteSizeLong());
    settings_proto.SerializeWithCachedSizesToArray(settings_ser.data());
    tf::Settings settings{};
    auto result = tf::Settings::DeserializeSettings(settings_ser, &settings);
    EXPECT_EQ(result, TUNINGFORK_ERROR_OK);
    EXPECT_EQ(settings.api_key, api_key);
    EXPECT_EQ(settings.base_uri, base_uri);
    EXPECT_EQ(settings.aggregation_strategy.method,
              tf::Settings::AggregationStrategy::Submission::TICK_BASED);
    EXPECT_EQ(settings.aggregation_strategy.intervalms_or_count,
              intervalms_or_count);
    EXPECT_EQ(settings.aggregation_strategy.max_instrumentation_keys,
              max_instrumentation_keys);
    ASSERT_EQ(settings.aggregation_strategy.annotation_enum_size.size(), 3);
    int ix = 0;
    for (auto i : annotation_enum_sizes) {
        EXPECT_TRUE(settings.aggregation_strategy.annotation_enum_size[ix++] ==
                    i);
    }
    ASSERT_EQ(settings.histograms.size(), 1);
    // For some reason, the operator== doesn't work in EXPECT_EQ, so use
    // EXPECT_TRUE
    EXPECT_TRUE(settings.histograms[0] == (tf::Settings::Histogram{
                                              64000,  // instrument_key
                                              30,     // bucket_min
                                              35,     // bucket_max;
                                              1       // n_buckets;
                                          }));
    // Check overriding the api key
    settings.c_settings.api_key = overridden_api_key.c_str();
    result = tf::Settings::DeserializeSettings(settings_ser, &settings);
    EXPECT_EQ(result, TUNINGFORK_ERROR_OK);
    EXPECT_EQ(settings.api_key, overridden_api_key);
}
