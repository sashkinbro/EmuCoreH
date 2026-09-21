#include "file_cache.h"

#include "tuningfork_utils.h"

#define LOG_TAG "TuningFork"
#include <cinttypes>
#include <sstream>

#include "Log.h"

namespace tf = tuningfork;

namespace {

extern "C" TuningFork_ErrorCode FileCacheGet(
    uint64_t key, TuningFork_CProtobufSerialization* value, void* _self) {
    if (_self == nullptr) return TUNINGFORK_ERROR_BAD_PARAMETER;
    tf::FileCache* self = static_cast<tf::FileCache*>(_self);
    return self->Get(key, value);
}
extern "C" TuningFork_ErrorCode FileCacheSet(
    uint64_t key, const TuningFork_CProtobufSerialization* value, void* _self) {
    if (_self == nullptr) return TUNINGFORK_ERROR_BAD_PARAMETER;
    tf::FileCache* self = static_cast<tf::FileCache*>(_self);
    return self->Set(key, value);
}
extern "C" TuningFork_ErrorCode FileCacheRemove(uint64_t key, void* _self) {
    if (_self == nullptr) return TUNINGFORK_ERROR_BAD_PARAMETER;
    tf::FileCache* self = static_cast<tf::FileCache*>(_self);
    return self->Remove(key);
}

}  // anonymous namespace

namespace tuningfork {

using namespace file_utils;

std::string PathToKey(const std::string& path, uint64_t key) {
    std::stringstream str;
    str << path << "/local_cache_" << key;
    return str.str();
}

FileCache::FileCache(const std::string& path) : path_(path) {
    c_cache_ = TuningFork_Cache{(void*)this, &FileCacheSet, &FileCacheGet,
                                &FileCacheRemove};
}

TuningFork_ErrorCode FileCache::Get(uint64_t key,
                                    TuningFork_CProtobufSerialization* value) {
    std::lock_guard<std::mutex> lock(mutex_);
    ALOGV("FileCache::Get %" PRIu64, key);
    if (CheckAndCreateDir(path_)) {
        auto key_path = PathToKey(path_, key);
        if (FileExists(key_path)) {
            ALOGV("File %s exists", key_path.c_str());
            if (LoadBytesFromFile(key_path, value)) {
                ALOGV("Loaded key %" PRId64 " from %s (%" PRIu32 " bytes)", key,
                      key_path.c_str(), value->size);
                return TUNINGFORK_ERROR_OK;
            }
        } else {
            ALOGV("File %s does not exist", key_path.c_str());
        }
    }
    return TUNINGFORK_ERROR_NO_SUCH_KEY;
}

TuningFork_ErrorCode FileCache::Set(
    uint64_t key, const TuningFork_CProtobufSerialization* value) {
    std::lock_guard<std::mutex> lock(mutex_);
    ALOGV("FileCache::Set %" PRIu64, key);
    if (CheckAndCreateDir(path_)) {
        auto key_path = PathToKey(path_, key);
        if (SaveBytesToFile(key_path, value)) {
            ALOGV("Saved key %" PRId64 " to %s (%" PRIu32 " bytes)", key,
                  key_path.c_str(), value->size);
            return TUNINGFORK_ERROR_OK;
        }
    }
    return TUNINGFORK_ERROR_NO_SUCH_KEY;
}

TuningFork_ErrorCode FileCache::Remove(uint64_t key) {
    std::lock_guard<std::mutex> lock(mutex_);
    ALOGV("FileCache::Remove %" PRIu64, key);
    if (CheckAndCreateDir(path_)) {
        auto key_path = PathToKey(path_, key);
        if (DeleteFile(key_path)) {
            ALOGV("Deleted key %" PRId64 " (%s)", key, key_path.c_str());
            return TUNINGFORK_ERROR_OK;
        }
    }
    return TUNINGFORK_ERROR_NO_SUCH_KEY;
}

TuningFork_ErrorCode FileCache::Clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    ALOGV("FileCache::Clear");
    if (DeleteDir(path_))
        return TUNINGFORK_ERROR_OK;
    else
        return TUNINGFORK_ERROR_BAD_FILE_OPERATION;
}

bool FileCache::IsValid() const { return CheckAndCreateDir(path_); }

}  // namespace tuningfork
