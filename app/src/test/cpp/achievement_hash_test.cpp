// Run against the Android release core with equivalent ISO/CSO/CHD fixtures:
// LD_LIBRARY_PATH=<core-directory> ./achievement_hash_test <core.so> <expected-md5> <image> [image...]
#include <cstdio>
#include <cstring>
#include <dlfcn.h>

int main(int argc, char** argv) {
    if (argc < 4) return 2;
    void* library = dlopen(argv[1], RTLD_NOW);
    if (!library) {
        std::fprintf(stderr, "%s\n", dlerror());
        return 2;
    }
    auto hash = reinterpret_cast<bool (*)(const char*, char*)>(
        dlsym(library, "emucorea_disc_achievement_hash"));
    if (!hash) return 2;
    char actual[33] = {};
    if (hash(nullptr, actual) || hash("/nonexistent/achievement-test.iso", actual)) return 1;
    for (int i = 3; i < argc; ++i) {
        if (!hash(argv[i], actual) || std::strcmp(actual, argv[2]) != 0) {
            std::fprintf(stderr, "Hash mismatch for %s: %s\n", argv[i], actual);
            return 1;
        }
    }
    std::puts("Achievement image hashes match; unreadable input rejected.");
    dlclose(library);
    return 0;
}
