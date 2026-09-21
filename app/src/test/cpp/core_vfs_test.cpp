// Regression: the core must accept VFS seek callbacks returning the new offset (not stdio's zero).
// Usage: core_vfs_test <core.so> <fixture-directory> <expected-hash> <image> [image...]
#include "storage_vfs.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <string>

static retro_vfs_interface original, mapped;
static std::string root;
static constexpr char prefix[] = "/__emucorea_saf__/test/";
static std::string Resolve(const char *path) {
    return strncmp(path, prefix, sizeof(prefix) - 1) == 0 ? root + "/" + (path + sizeof(prefix) - 1) : path;
}
static bool Environment(unsigned cmd, void *data) {
    if (cmd != RETRO_ENVIRONMENT_GET_VFS_INTERFACE) return false;
    auto *info = static_cast<retro_vfs_interface_info *>(data);
    info->required_interface_version = 3;
    info->iface = &mapped;
    return true;
}
int main(int argc, char **argv) {
    assert(argc >= 5);
    root = argv[2];
    retro_vfs_interface_info info{3, nullptr};
    assert(GetStorageVfs(&info));
    original = mapped = *info.iface;
    mapped.open = [](const char *path, unsigned mode, unsigned hints) { return original.open(Resolve(path).c_str(), mode, hints); };
    mapped.stat = [](const char *path, int32_t *size) { return original.stat(Resolve(path).c_str(), size); };
    mapped.opendir = [](const char *path, bool hidden) { return original.opendir(Resolve(path).c_str(), hidden); };
    void *library = dlopen(argv[1], RTLD_NOW);
    if (!library) { puts(dlerror()); return 2; }
    auto setEnvironment = reinterpret_cast<void (*)(retro_environment_t)>(dlsym(library, "retro_set_environment"));
    auto asset = reinterpret_cast<int (*)(const char *, int, void *, size_t)>(dlsym(library, "emucorea_game_asset"));
    auto hash = reinterpret_cast<bool (*)(const char *, char *)>(dlsym(library, "emucorea_disc_achievement_hash"));
    assert(setEnvironment && asset && hash);
    setEnvironment(Environment);
    char actual[33]{};
    unsigned char sfo[65536];
    for (int i = 4; i < argc; ++i) {
        const std::string path = std::string(prefix) + argv[i];
        assert(asset(path.c_str(), 0, sfo, sizeof(sfo)) > 0);
        assert(!memcmp(sfo, "\0PSF", 4));
        assert(hash(path.c_str(), actual) && !strcmp(actual, argv[3]));
        printf("Direct VFS SFO/hash PASS %s\n", argv[i]);
    }
    assert(asset("/__emucorea_saf__/test/missing.iso", 0, sfo, sizeof(sfo)) <= 0);
    dlclose(library);
}
