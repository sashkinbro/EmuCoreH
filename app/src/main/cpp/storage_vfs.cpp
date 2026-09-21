#include "storage_vfs.h"
#include <algorithm>
#include <climits>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

struct retro_vfs_file_handle {
    int fd;
    std::string path;
};
struct retro_vfs_dir_handle {
    std::vector<std::pair<std::string, bool>> entries;
    size_t next = 0;
};

namespace {
JavaVM *javaVm = nullptr;
jclass storageClass = nullptr;
jmethodID openMethod = nullptr, statMethod = nullptr, listMethod = nullptr;
constexpr char kPrefix[] = "/__emucoreh_saf__/";
bool IsSaf(const char *path) { return path && strncmp(path, kPrefix, sizeof(kPrefix) - 1) == 0; }

struct JavaScope {
    JNIEnv *env = nullptr;
    bool attached = false;
    JavaScope() {
        if (javaVm && javaVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED)
            attached = javaVm->AttachCurrentThread(&env, nullptr) == JNI_OK;
    }
    ~JavaScope() { if (attached) javaVm->DetachCurrentThread(); }
    bool Failed() { if (!env) return true; if (!env->ExceptionCheck()) return false; env->ExceptionClear(); return true; }
};

retro_vfs_file_handle *Open(const char *path, unsigned mode, unsigned) {
    if (!path) return nullptr;
    int fd = -1;
    if (IsSaf(path)) {
        // Selected game resources are read in place. Saves use the ordinary writable memstick paths.
        if (mode != RETRO_VFS_FILE_ACCESS_READ) return nullptr;
        JavaScope j;
        if (!j.env || !storageClass) return nullptr;
        jstring name = j.env->NewStringUTF(path);
        fd = j.env->CallStaticIntMethod(storageClass, openMethod, name);
        j.env->DeleteLocalRef(name);
        if (j.Failed()) return nullptr;
    } else {
        int flags = (mode & RETRO_VFS_FILE_ACCESS_WRITE) ?
            ((mode & RETRO_VFS_FILE_ACCESS_READ) ? O_RDWR : O_WRONLY) : O_RDONLY;
        if ((mode & RETRO_VFS_FILE_ACCESS_WRITE) && !(mode & RETRO_VFS_FILE_ACCESS_UPDATE_EXISTING))
            flags |= O_CREAT | O_TRUNC;
        fd = open(path, flags | O_CLOEXEC, 0666);
    }
    if (fd < 0) return nullptr;
    // ISO/CSO/CHD/PBP readers require random access. A pipe must fail, never trigger a cache copy.
    if (lseek64(fd, 0, SEEK_CUR) < 0) { close(fd); return nullptr; }
    return new retro_vfs_file_handle{fd, path};
}
const char *GetPath(retro_vfs_file_handle *file) { return file ? file->path.c_str() : nullptr; }
int Close(retro_vfs_file_handle *file) { if (!file) return -1; int result = close(file->fd); delete file; return result; }
int64_t Size(retro_vfs_file_handle *file) {
    struct stat st{};
    return file && fstat(file->fd, &st) == 0 ? st.st_size : -1;
}
int64_t Tell(retro_vfs_file_handle *file) { return file ? lseek64(file->fd, 0, SEEK_CUR) : -1; }
int64_t Seek(retro_vfs_file_handle *file, int64_t offset, int whence) {
    int origin = whence == RETRO_VFS_SEEK_POSITION_START ? SEEK_SET :
        whence == RETRO_VFS_SEEK_POSITION_CURRENT ? SEEK_CUR : whence == RETRO_VFS_SEEK_POSITION_END ? SEEK_END : -1;
    return file && origin != -1 ? lseek64(file->fd, offset, origin) : -1;
}
int64_t Read(retro_vfs_file_handle *file, void *buffer, uint64_t length) {
    if (!file || length > SSIZE_MAX) return -1;
    ssize_t result;
    do { result = read(file->fd, buffer, length); } while (result < 0 && errno == EINTR);
    return result;
}
int64_t Write(retro_vfs_file_handle *file, const void *buffer, uint64_t length) {
    if (!file || length > SSIZE_MAX) return -1;
    ssize_t result;
    do { result = write(file->fd, buffer, length); } while (result < 0 && errno == EINTR);
    return result;
}
int Flush(retro_vfs_file_handle *file) { return file ? fsync(file->fd) : -1; }
int Remove(const char *path) { return path && !IsSaf(path) ? remove(path) : -1; }
int Rename(const char *from, const char *to) { return from && to && !IsSaf(from) && !IsSaf(to) ? rename(from, to) : -1; }
int64_t Truncate(retro_vfs_file_handle *file, int64_t size) { return file ? ftruncate64(file->fd, size) : -1; }
int Stat(const char *path, int32_t *size) {
    if (size) *size = 0;
    if (!path) return 0;
    if (IsSaf(path)) {
        JavaScope j;
        if (!j.env || !storageClass) return 0;
        jstring name = j.env->NewStringUTF(path);
        auto result = static_cast<jlongArray>(j.env->CallStaticObjectMethod(storageClass, statMethod, name));
        j.env->DeleteLocalRef(name);
        if (j.Failed() || !result) return 0;
        jlong values[2]{};
        j.env->GetLongArrayRegion(result, 0, 2, values);
        j.env->DeleteLocalRef(result);
        if (j.Failed()) return 0;
        if (size) *size = static_cast<int32_t>(std::clamp<int64_t>(values[1], 0, INT32_MAX));
        return static_cast<int>(values[0]);
    }
    struct stat st{};
    if (stat(path, &st)) return 0;
    if (size) *size = static_cast<int32_t>(std::min<int64_t>(st.st_size, INT32_MAX));
    return RETRO_VFS_STAT_IS_VALID | (S_ISDIR(st.st_mode) ? RETRO_VFS_STAT_IS_DIRECTORY : 0) |
        (S_ISCHR(st.st_mode) ? RETRO_VFS_STAT_IS_CHARACTER_SPECIAL : 0);
}
int Mkdir(const char *path) {
    if (!path || IsSaf(path)) return -1;
    return mkdir(path, 0777) == 0 ? 0 : errno == EEXIST ? -2 : -1;
}
retro_vfs_dir_handle *Opendir(const char *path, bool hidden) {
    if (!path) return nullptr;
    auto *directory = new retro_vfs_dir_handle;
    if (IsSaf(path)) {
        JavaScope j;
        if (!j.env || !storageClass) { delete directory; return nullptr; }
        jstring name = j.env->NewStringUTF(path);
        auto result = static_cast<jobjectArray>(j.env->CallStaticObjectMethod(storageClass, listMethod, name));
        j.env->DeleteLocalRef(name);
        if (j.Failed() || !result) { delete directory; return nullptr; }
        const auto count = j.env->GetArrayLength(result);
        for (jsize i = 0; i < count; ++i) {
            auto item = static_cast<jstring>(j.env->GetObjectArrayElement(result, i));
            const char *text = j.env->GetStringUTFChars(item, nullptr);
            if (text && text[0]) {
                const char *name = text + 1;
                // "." and ".." must never reach the core: directory walkers
                // recurse into them until the path overflows and throw.
                const bool dotted = name[0] == '.' && (name[1] == '\0' || (name[1] == '.' && name[2] == '\0'));
                if (!dotted && (hidden || name[0] != '.'))
                    directory->entries.emplace_back(name, text[0] == 'd');
            }
            if (text) j.env->ReleaseStringUTFChars(item, text);
            j.env->DeleteLocalRef(item);
        }
        j.env->DeleteLocalRef(result);
        if (j.Failed()) { delete directory; return nullptr; }
    } else {
        DIR *dir = opendir(path);
        if (!dir) { delete directory; return nullptr; }
        while (dirent *entry = readdir(dir)) {
            // "." and ".." are never listed: the core's directory tree would
            // recurse through them forever. Other dot files stay hidden unless
            // the frontend asked for hidden entries.
            const bool dotted = entry->d_name[0] == '.' && (entry->d_name[1] == '\0'
                || (entry->d_name[1] == '.' && entry->d_name[2] == '\0'));
            if (dotted) continue;
            if (!hidden && entry->d_name[0] == '.') continue;
            struct stat st{};
            std::string full = std::string(path) + "/" + entry->d_name;
            bool isDirectory = stat(full.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
            directory->entries.emplace_back(entry->d_name, isDirectory);
        }
        closedir(dir);
    }
    return directory;
}
bool Readdir(retro_vfs_dir_handle *dir) { if (!dir || dir->next >= dir->entries.size()) return false; ++dir->next; return true; }
const char *DirName(retro_vfs_dir_handle *dir) { return dir && dir->next ? dir->entries[dir->next - 1].first.c_str() : nullptr; }
bool DirIsDirectory(retro_vfs_dir_handle *dir) { return dir && dir->next && dir->entries[dir->next - 1].second; }
int Closedir(retro_vfs_dir_handle *dir) { delete dir; return 0; }
retro_vfs_interface interface{GetPath, Open, Close, Size, Tell, Seek, Read, Write, Flush, Remove, Rename,
    Truncate, Stat, Mkdir, Opendir, Readdir, DirName, DirIsDirectory, Closedir};
}

bool InitializeStorageVfs(JavaVM *vm, JNIEnv *env) {
    javaVm = vm;
    jclass local = env->FindClass("com/sbro/emucoreh/core/SafStorageBridge");
    if (!local) return false;
    storageClass = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    openMethod = env->GetStaticMethodID(storageClass, "open", "(Ljava/lang/String;)I");
    statMethod = env->GetStaticMethodID(storageClass, "stat", "(Ljava/lang/String;)[J");
    listMethod = env->GetStaticMethodID(storageClass, "list", "(Ljava/lang/String;)[Ljava/lang/String;");
    return openMethod && statMethod && listMethod && !env->ExceptionCheck();
}
bool GetStorageVfs(retro_vfs_interface_info *info) {
    if (!info || info->required_interface_version > 3) return false;
    info->required_interface_version = 3;
    info->iface = &interface;
    return true;
}
