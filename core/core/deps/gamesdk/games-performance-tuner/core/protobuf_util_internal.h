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

#pragma once

#include <string>
#include <vector>

#include "proto/protobuf_util.h"

namespace tuningfork {

namespace file_descriptor {

struct EnumField {
  int number;
  std::string name;
  std::string type_name;
};

struct MessageType {
  std::string name;
  std::vector<EnumField> fields;
  bool GetField(int field_number, const EnumField** field) const {
    for (auto& f : fields) {
      if (f.number == field_number) {
        *field = &f;
        return true;
      }
    }
    return false;
  }
};

struct EnumType {
  std::string name;
  std::vector<std::pair<std::string, int>> value;
};

struct File {
  std::string package;
  std::vector<MessageType> message_type;
  std::vector<EnumType> enum_type;
  // Find the enum in the file with type name given and convert the int value
  // to an enum field string value
  std::string GetEnumValueString(const std::string& full_enum_type_name,
                                 int value) {
    // Take off the file prefix, e.g. .com.tuningfork.something.Loading ->
    // Loading
    if (full_enum_type_name.find(package) != 1) return "Error";
    std::string enum_type_name =
        full_enum_type_name.substr(package.length() + 2);  // 2 dots
    for (auto& e : enum_type) {
      if (enum_type_name == e.name) {
        for (auto& v : e.value) {
          if (v.second == value) return v.first;
        }
      }
    }
    return "Error";
  }
};

// Load and decode the descriptor from dev_tuningfork.bin
// Returns nullptr on error.
File* GetTuningForkFileDescriptor();

// Load the descriptor from dev_tuningfork.bin
// Returns nullptr on error.
ProtobufSerialization* GetTuningForkFileDescriptorSerialization();

}  // namespace file_descriptor

}  // namespace tuningfork
