/*
 * Copyright 2018 The Android Open Source Project
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

/**
 * @defgroup Protobuf_util Protocol Buffer utilities
 * Helper functions for converting between C and C++ protocol buffer
 * serializations.
 * @{
 */

#pragma once

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "tuningfork/tuningfork.h"

/** @cond INTERNAL */

/**
 * Internal to this file - do not use.
 */
extern "C" void TuningFork_CProtobufSerialization_Dealloc(
    TuningFork_CProtobufSerialization* c);

/** @endcond */

namespace tuningfork {

/**
 * @brief A protocol buffer serialization stored as a STL vector of bytes
 */
typedef std::vector<uint8_t> ProtobufSerialization;

/**
 * @brief A vector of (fidelity_params, frame_time) pairs.
 */
typedef std::vector<std::pair<ProtobufSerialization, uint32_t>>
    QLTimePredictions;

/**
 * @brief Convert from a C to a C++ serialization.
 */
inline ProtobufSerialization ToProtobufSerialization(
    const TuningFork_CProtobufSerialization& cpbs) {
  return ProtobufSerialization(cpbs.bytes, cpbs.bytes + cpbs.size);
}

/**
 * @brief Convert from a C++ to a C serialization.
 */
inline void ToCProtobufSerialization(const ProtobufSerialization& pbs,
                                     TuningFork_CProtobufSerialization& cpbs) {
  cpbs.bytes = (uint8_t*)::malloc(pbs.size());
  memcpy(cpbs.bytes, pbs.data(), pbs.size());
  cpbs.size = pbs.size();
  cpbs.dealloc = TuningFork_CProtobufSerialization_Dealloc;
}

/**
 * @brief Convert from an STL string to a C serialization.
 */
inline void ToCProtobufSerialization(const std::string& s,
                                     TuningFork_CProtobufSerialization& cpbs) {
  cpbs.bytes = (uint8_t*)::malloc(s.size());
  memcpy(cpbs.bytes, s.data(), s.size());
  cpbs.size = s.size();
  cpbs.dealloc = TuningFork_CProtobufSerialization_Dealloc;
}

/**
 * @brief Convert from a C serialization to an STL string.
 */
inline std::string ToString(const TuningFork_CProtobufSerialization& cpbs) {
  return std::string((const char*)cpbs.bytes, cpbs.size);
}

/**
 * @brief Deserialize an STL vector of bytes to a protocol buffer object.
 */
template <typename T>
bool Deserialize(const std::vector<uint8_t>& ser, T& pb) {
  return pb.ParseFromArray(ser.data(), ser.size());
}

/**
 * @brief Serialize a protocol buffer object to an STL vector of bytes.
 */
template <typename T>
bool Serialize(const T& pb, std::vector<uint8_t>& ser) {
  ser.resize(pb.ByteSizeLong());
  return pb.SerializeToArray(ser.data(), ser.size());
}

/**
 * @brief Serialize a protocol buffer object to an STL vector of bytes.
 */
template <typename T>
std::vector<uint8_t> Serialize(const T& pb) {
  std::vector<uint8_t> ser(pb.ByteSizeLong());
  pb.SerializeToArray(ser.data(), ser.size());
  return ser;
}

/**
 * @brief Serialize a protocol buffer object to a CProtobuf.
 *
 * The caller takes ownership of the returned serialization and must call
 * TuningFork_CProtobufSerialization_free to deallocate any memory.
 */
template <typename T>
TuningFork_CProtobufSerialization TuningFork_CProtobufSerialization_Alloc(
    const T& pb) {
  TuningFork_CProtobufSerialization cser;
  cser.bytes = (uint8_t*)::malloc(pb.ByteSizeLong());
  cser.size = pb.ByteSizeLong();
  cser.dealloc = TuningFork_CProtobufSerialization_Dealloc;
  pb.SerializeToArray(cser.bytes, cser.size);
  return cser;
}

}  // namespace tuningfork

/** @} */
