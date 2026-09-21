// Android: link with storage_vfs.cpp and run from an app-independent temporary directory.
#include "storage_vfs.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <unistd.h>

int main(int argc, char **argv) {
    assert(argc == 2);
    retro_vfs_interface_info info{3, nullptr};
    assert(GetStorageVfs(&info));
    auto *vfs = info.iface;
    const std::string path = std::string(argv[1]) + "/vfs-test.bin";
    auto *file = vfs->open(path.c_str(), RETRO_VFS_FILE_ACCESS_READ_WRITE, 0);
    assert(file);
    assert(vfs->write(file, "abcdefgh", 8) == 8);
    assert(vfs->seek(file, -3, RETRO_VFS_SEEK_POSITION_END) == 5);
    char bytes[4]{};
    assert(vfs->read(file, bytes, 3) == 3 && !strcmp(bytes, "fgh"));
    assert(vfs->seek(file, -1, RETRO_VFS_SEEK_POSITION_START) == -1);
    assert(vfs->size(file) == 8);
    assert(vfs->flush(file) == 0);
    assert(vfs->close(file) == 0);
    file = vfs->open(path.c_str(), RETRO_VFS_FILE_ACCESS_WRITE | RETRO_VFS_FILE_ACCESS_UPDATE_EXISTING, 0);
    assert(file && vfs->size(file) == 8);
    assert(vfs->truncate(file, 4) == 0 && vfs->size(file) == 4);
    // 64-bit offsets must not wrap at 2 GiB. This is a sparse file, not a copied game.
    assert(vfs->seek(file, (int64_t(3) << 30), RETRO_VFS_SEEK_POSITION_START) == (int64_t(3) << 30));
    assert(vfs->write(file, "x", 1) == 1);
    assert(vfs->size(file) == (int64_t(3) << 30) + 1);
    assert(vfs->close(file) == 0);
    int32_t size = 0;
    assert(vfs->stat(path.c_str(), &size) == RETRO_VFS_STAT_IS_VALID && size == INT32_MAX);
    auto *dir = vfs->opendir(argv[1], false);
    assert(dir);
    bool found = false;
    while (vfs->readdir(dir)) if (!strcmp(vfs->dirent_get_name(dir), "vfs-test.bin")) {
        found = true;
        assert(!vfs->dirent_is_dir(dir));
    }
    assert(found && vfs->closedir(dir) == 0);
    assert(vfs->remove(path.c_str()) == 0);
    assert(!vfs->open(path.c_str(), RETRO_VFS_FILE_ACCESS_READ, 0));
    int pipeFds[2];
    assert(pipe(pipeFds) == 0);
    const std::string pipePath = "/proc/self/fd/" + std::to_string(pipeFds[0]);
    assert(!vfs->open(pipePath.c_str(), RETRO_VFS_FILE_ACCESS_READ, 0));
    close(pipeFds[0]);
    close(pipeFds[1]);
    assert(!vfs->open("/__emucorea_saf__/missing/game.iso", RETRO_VFS_FILE_ACCESS_READ, 0));
    assert(vfs->remove("/__emucorea_saf__/missing/game.iso") == -1);
    info.required_interface_version = 4;
    assert(!GetStorageVfs(&info));
    puts("VFS seek/read/write/64-bit size/directory/error contracts passed");
}
