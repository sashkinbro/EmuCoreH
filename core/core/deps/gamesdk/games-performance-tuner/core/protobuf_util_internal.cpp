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
 * Structures and functions needed for the decoding of a
 * dev_tuningfork.descriptor.
 */

#include "protobuf_util_internal.h"

#include <map>
#include <mutex>

#include "lite/descriptor.pb.h"
#include "proto/protobuf_util.h"
#include "tuningfork_utils.h"

namespace tuningfork {

namespace file_descriptor {

static EnumField DeserializeFieldDescriptorProto(
    google::protobuf::FieldDescriptorProto field_descriptor_proto) {
    EnumField enum_field;
    enum_field.number = field_descriptor_proto.number();
    enum_field.name = field_descriptor_proto.name();
    enum_field.type_name = field_descriptor_proto.type_name();
    return enum_field;
}

static MessageType DeserializeDescriptorProto(
    google::protobuf::DescriptorProto descriptor_proto) {
    MessageType message_type;
    message_type.name = descriptor_proto.name();
    for (int i = 0; i < descriptor_proto.field_size(); i++) {
        if (descriptor_proto.field(i).type() ==
            google::protobuf::FieldDescriptorProto_Type_TYPE_ENUM) {
            message_type.fields.push_back(
                DeserializeFieldDescriptorProto(descriptor_proto.field(i)));
        }
    }
    return message_type;
}

static EnumType DeserializeEnumDescriptorProto(
    google::protobuf::EnumDescriptorProto enum_descriptor_proto) {
    EnumType enum_type;
    enum_type.name = enum_descriptor_proto.name();
    for (int i = 0; i < enum_descriptor_proto.value_size(); i++) {
        enum_type.value.push_back({enum_descriptor_proto.value(i).name(),
                                   enum_descriptor_proto.value(i).number()});
    }
    return enum_type;
}

ProtobufSerialization* GetTuningForkFileDescriptorSerialization() {
    // Avoid race conditions with multiple threads calling the function
    static std::mutex cache_mutex;
    static ProtobufSerialization descriptor_ser_cache;
    std::lock_guard<std::mutex> lock(cache_mutex);
    if (descriptor_ser_cache.size() != 0) {
        return &descriptor_ser_cache;
    } else {
        if (apk_utils::GetAssetAsSerialization(
                "tuningfork/dev_tuningfork.descriptor", descriptor_ser_cache))
            return &descriptor_ser_cache;
        else {
            return nullptr;
        }
    }
}

File* GetTuningForkFileDescriptor() {
    enum class ReadStatus { UNREAD, READ_OK, READ_WITH_ERROR };
    // Avoid race conditions with multiple threads calling the function
    static std::mutex cache_mutex;
    static ReadStatus read_status = ReadStatus::UNREAD;
    static File cached_file{};
    std::lock_guard<std::mutex> lock(cache_mutex);
    if (read_status == ReadStatus::UNREAD) {
        ProtobufSerialization* descriptor_ser =
            GetTuningForkFileDescriptorSerialization();
        if (descriptor_ser == nullptr) return nullptr;
        google::protobuf::FileDescriptorSet descriptor;
        if (!Deserialize(*descriptor_ser, descriptor) ||
            descriptor.file_size() == 0) {
            read_status = ReadStatus::READ_WITH_ERROR;
            return nullptr;
        }
        cached_file.package = descriptor.file(0).package();
        for (int i = 0; i < descriptor.file(0).message_type_size(); i++) {
            cached_file.message_type.push_back(
                DeserializeDescriptorProto(descriptor.file(0).message_type(i)));
        }
        for (int i = 0; i < descriptor.file(0).enum_type_size(); i++) {
            cached_file.enum_type.push_back(DeserializeEnumDescriptorProto(
                descriptor.file(0).enum_type(i)));
        }
    }
    if (read_status == ReadStatus::READ_OK)
        return &cached_file;
    else
        return nullptr;
}

}  // namespace file_descriptor

}  // namespace tuningfork
