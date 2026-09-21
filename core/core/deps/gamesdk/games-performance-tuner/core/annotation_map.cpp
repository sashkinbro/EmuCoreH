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

#include "annotation_map.h"

#include <functional>

namespace {

// We expect the serializations to be small, so a Murmur2 hash should be
// quick and distributed well enough.
// There is a std::__murmur2_or_cityhash in <utility> but only on later
// NDK's libc++, so we reproduce here.
static uint32_t Murmur2Hash(const uint8_t* data, int len) {
    const uint32_t m = 0x5bd1e995;
    const int r = 24;
    uint32_t h = len;

    for (; len >= 4; data += 4, len -= 4) {
        uint32_t k = *(uint32_t*)data;
        k *= m;
        k ^= k >> r;
        k *= m;
        h *= m;
        h ^= k;
    }

    switch (len) {
        case 3:
            h ^= data[2] << 16;
        case 2:
            h ^= data[1] << 8;
        case 1:
            h ^= data[0];
            h *= m;
    };

    h ^= h >> 13;
    h *= m;
    h ^= h >> 15;

    return h;
}

}  // anonymous namespace

namespace tuningfork {

AnnotationMap::AnnotationMap() : hash_table_(kHashTableSize) {}

TuningFork_ErrorCode AnnotationMap::GetOrInsert(
    const ProtobufSerialization& ser, AnnotationId& id) {
    id = Murmur2Hash(ser.data(), ser.size());
    auto& ls = hash_table_[HashIndex(id)];
    for (auto& l : ls) {
        if (l.first == id) return TUNINGFORK_ERROR_OK;
    }
    ls.push_back({id, ser});
    return TUNINGFORK_ERROR_OK;
}

TuningFork_ErrorCode AnnotationMap::Get(AnnotationId id,
                                        ProtobufSerialization& ser) {
    auto& ls = hash_table_[HashIndex(id)];
    for (auto& l : ls) {
        if (l.first == id) {
            ser = l.second;
            return TUNINGFORK_ERROR_OK;
        }
    }
    return TUNINGFORK_ERROR_INVALID_ANNOTATION;
}

}  // namespace tuningfork
