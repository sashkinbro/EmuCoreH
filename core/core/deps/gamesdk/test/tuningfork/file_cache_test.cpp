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

#include "core/file_cache.h"

#include <gtest/gtest.h>

#include <string>

#include "core/tuningfork_utils.h"
#include "jni/jni_helper.h"
#include "proto/protobuf_util.h"
#include "test_utils.h"
#include "tuningfork_test_c.h"

namespace test {

using namespace tuningfork;

constexpr char kBasePath[] = "/data/local/tmp/tuningfork_file_test";

class FileCacheTest {
    FileCache cache_;

   public:
    FileCacheTest() : cache_(GetPath()) {
        EXPECT_EQ(cache_.Clear(), TUNINGFORK_ERROR_OK);
    }
    ~FileCacheTest() { clear_jni_for_tests(); }
    bool IsValid() const { return cache_.IsValid(); }
    void Save(uint64_t key, const ProtobufSerialization& value) {
        TuningFork_CProtobufSerialization cvalue;
        ToCProtobufSerialization(value, cvalue);
        cache_.Set(key, &cvalue);
        TuningFork_CProtobufSerialization_free(&cvalue);
    }
    ProtobufSerialization Load(uint64_t key) {
        TuningFork_CProtobufSerialization cvalue;
        if (cache_.Get(key, &cvalue) == TUNINGFORK_ERROR_OK) {
            auto value = ToProtobufSerialization(cvalue);
            TuningFork_CProtobufSerialization_free(&cvalue);
            return value;
        } else
            return {};
    }
    void Remove(uint64_t key) { cache_.Remove(key); }
    static std::string GetPath() {
        // Use JNI if we can, for app cache usage rather than /data/local/tmp
        init_jni_for_tests();
        if (gamesdk::jni::IsValid()) {
            return file_utils::GetAppCacheDir() + "/tuningfork_file_test";
        } else {
            return kBasePath;
        }
    }
    static std::string LocalFileName(uint64_t key) {
        std::stringstream str;
        str << GetPath() << "/local_cache_" << key;
        return str.str();
    }
};

static std::vector<uint64_t> keys = {0, 1, 24523, 0xffffff};

TEST(FileCacheTest, FileSaveLoadOp) {
    FileCacheTest ft;
    // Some devices don't allow writing to /data/local/tmp, however we don't
    // want to
    //  give errors when they are run on the command-line.
    if (!ft.IsValid()) GTEST_SKIP();
    ProtobufSerialization saved = {1, 2, 3};
    for (auto k : keys) {
        EXPECT_FALSE(file_utils::FileExists(FileCacheTest::LocalFileName(k)));
        ft.Save(k, saved);
        EXPECT_TRUE(file_utils::FileExists(FileCacheTest::LocalFileName(k)));
        EXPECT_EQ(ft.Load(k), saved) << "Save+Load 1";
    }
}

TEST(FileCacheTest, FileRemoveOp) {
    FileCacheTest ft;
    // Some devices don't allow writing to /data/local/tmp, however we don't
    // want to
    //  give errors when they are run on the command-line.
    if (!ft.IsValid()) GTEST_SKIP();
    ProtobufSerialization saved = {1, 2, 3};
    for (auto k : keys) {
        ft.Save(k, saved);
        EXPECT_TRUE(file_utils::FileExists(FileCacheTest::LocalFileName(k)));
        EXPECT_EQ(ft.Load(k), saved) << "Save+Load 2";
        ft.Remove(k);
        EXPECT_FALSE(file_utils::FileExists(FileCacheTest::LocalFileName(k)));
        EXPECT_EQ(ft.Load(k), ProtobufSerialization{}) << "Remove";
    }
}

}  // namespace test
