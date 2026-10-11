#pragma once

#include "libretro.h"
#include <limits>
#include <string>
#include <vector>

namespace emucoreh {
enum DiscChangeResult {
    kDiscChanged = 0,
    kDiscInvalidPath = -1,
    kDiscMissingCallbacks = -4,
    kDiscInvalidIndex = -5,
    kDiscEjectFailed = -6,
    kDiscAddFailed = -7,
    kDiscReplaceFailed = -8,
    kDiscSelectFailed = -9,
    kDiscInsertFailed = -10,
    kDiscRollbackFailed = -11
};

/** Swap media through the tray protocol without unloading the running game. */
inline int ChangeDisc(const retro_disk_control_ext_callback& callbacks, const std::string& path) {
    if (path.empty()) return kDiscInvalidPath;
    if (!callbacks.set_eject_state || !callbacks.get_eject_state ||
        !callbacks.get_image_index || !callbacks.set_image_index ||
        !callbacks.get_num_images || !callbacks.replace_image_index || !callbacks.add_image_index) {
        return kDiscMissingCallbacks;
    }
    const unsigned originalCount = callbacks.get_num_images();
    const unsigned originalIndex = callbacks.get_image_index();
    if (!originalCount || originalIndex >= originalCount || originalCount == std::numeric_limits<unsigned>::max()) {
        return kDiscInvalidIndex;
    }

    unsigned targetIndex = originalCount;
    if (callbacks.get_image_path) {
        // One extra character prevents a truncated longer path matching this path's prefix.
        std::vector<char> imagePath(path.size() + 2, '\0');
        for (unsigned index = 0; index < originalCount; ++index) {
            if (callbacks.get_image_path(index, imagePath.data(), imagePath.size())) {
                imagePath.back() = '\0';
                if (path == imagePath.data()) {
                    targetIndex = index;
                    break;
                }
            }
        }
    }

    bool addedImage = false;
    auto rollback = [&](int failure) {
        // Failed close callbacks can leave the tray marked closed even though insertion failed.
        const bool opened = callbacks.set_eject_state(true);
        const bool selected = callbacks.set_image_index(originalIndex);
        bool removed = true;
        if (addedImage) {
            removed = callbacks.replace_image_index(originalCount, nullptr);
            removed = callbacks.get_num_images() == originalCount && removed;
        }
        const bool closed = callbacks.set_eject_state(false);
        return opened && selected && removed && closed && !callbacks.get_eject_state()
            ? failure : kDiscRollbackFailed;
    };

    if (!callbacks.set_eject_state(true) || !callbacks.get_eject_state()) {
        return rollback(kDiscEjectFailed);
    }
    if (targetIndex == originalCount) {
        const bool added = callbacks.add_image_index();
        const unsigned count = callbacks.get_num_images();
        addedImage = count > originalCount;
        if (!added || count != originalCount + 1) return rollback(kDiscAddFailed);
        const retro_game_info image{path.c_str(), nullptr, 0, nullptr};
        if (!callbacks.replace_image_index(targetIndex, &image)) return rollback(kDiscReplaceFailed);
    }
    if (!callbacks.set_image_index(targetIndex)) return rollback(kDiscSelectFailed);
    if (!callbacks.set_eject_state(false) || callbacks.get_eject_state()) return rollback(kDiscInsertFailed);
    return kDiscChanged;
}
}
