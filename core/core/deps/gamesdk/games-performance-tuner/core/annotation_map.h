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

#pragma once

#include <list>
#include <mutex>
#include <vector>

#include "common.h"

namespace tuningfork {

// Stores the mapping from annotation serializations to annotation ids
// and back again.
class AnnotationMap {
  // We store in a hash table of fixed size to avoid having to lock when
  // inserting. If we used an STL unordered map there's the possibility of
  // rehashing.
  static const int kHashTableSize = 256;
  typedef std::vector<std::list<std::pair<AnnotationId, ProtobufSerialization>>>
      InnerContainer;
  typedef InnerContainer::iterator Iterator;
  InnerContainer hash_table_;

 public:
  AnnotationMap();
  TuningFork_ErrorCode GetOrInsert(const ProtobufSerialization& ser,
                                   AnnotationId& id);
  TuningFork_ErrorCode Get(AnnotationId id, ProtobufSerialization& ser);

 private:
  inline int HashIndex(AnnotationId id) { return id & 0xff; }
};

}  // namespace tuningfork
