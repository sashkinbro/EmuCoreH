#include "disc_control.h"
#include <cassert>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

namespace {
struct FakeDiscs {
    std::vector<std::string> images{"disc-one.chd"};
    unsigned selected = 0;
    bool open = false;
    std::string fail;
    bool failed = false;
    bool failRollback = false;
    std::vector<std::string> calls;
} discs;

bool Fail(const std::string& stage) {
    if (discs.fail == stage && !discs.failed) {
        discs.failed = true;
        return true;
    }
    return false;
}

bool SetEject(bool open) {
    discs.calls.push_back(open ? "open" : "close");
    discs.open = open;
    if (!open && discs.failRollback && discs.selected == 0) return false;
    return !Fail(open ? "open" : "close");
}
bool GetEject() { return discs.open; }
unsigned GetIndex() { return discs.selected; }
unsigned GetCount() { return static_cast<unsigned>(discs.images.size()); }
bool SetIndex(unsigned index) {
    discs.calls.push_back("select:" + std::to_string(index));
    discs.selected = index;
    return !Fail("select");
}
bool AddImage() {
    discs.calls.push_back("add");
    discs.images.emplace_back(); // Some failed callbacks mutate before returning false.
    return !Fail("add");
}
bool ReplaceImage(unsigned index, const retro_game_info* info) {
    discs.calls.push_back(info ? "replace:" + std::to_string(index) + ":" + info->path : "remove:" + std::to_string(index));
    if (!info) {
        discs.images.erase(discs.images.begin() + index);
        if (discs.selected >= index && discs.selected > 0) --discs.selected;
        return true;
    }
    discs.images[index] = info->path;
    return !Fail("replace");
}
bool GetPath(unsigned index, char* output, size_t size) {
    if (index >= discs.images.size() || discs.images[index].empty() || !size) return false;
    std::strncpy(output, discs.images[index].c_str(), size - 1);
    output[size - 1] = '\0';
    return true;
}
retro_disk_control_ext_callback Callbacks() {
    return {SetEject, GetEject, GetIndex, SetIndex, GetCount, ReplaceImage, AddImage, nullptr, GetPath, nullptr};
}

void SuccessfulSwapKeepsOriginalImageAndUsesRealTraySequence() {
    discs = {};
    assert(emucoreh::ChangeDisc(Callbacks(), "disc-two.chd") == 0);
    assert((discs.calls == std::vector<std::string>{"open", "add", "replace:1:disc-two.chd", "select:1", "close"}));
    assert((discs.images == std::vector<std::string>{"disc-one.chd", "disc-two.chd"}));
    assert(discs.selected == 1 && !discs.open);
}

void ExistingImageIsReusedWithoutGrowingPlaylistOrMatchingTruncatedPrefix() {
    discs = {};
    discs.images.push_back("disc-two.chd-longer");
    assert(emucoreh::ChangeDisc(Callbacks(), "disc-two.chd") == 0);
    assert(discs.images.size() == 3 && discs.selected == 2);
    discs.calls.clear();
    assert(emucoreh::ChangeDisc(Callbacks(), "disc-one.chd") == 0);
    assert((discs.calls == std::vector<std::string>{"open", "select:0", "close"}));
    discs.calls.clear();
    assert(emucoreh::ChangeDisc(Callbacks(), "disc-two.chd") == 0);
    assert((discs.calls == std::vector<std::string>{"open", "select:2", "close"}));
    assert(discs.images.size() == 3 && !discs.open);
}

void FailedSwapRestoresIndexClosesOldDiscAndRemovesAppendedSlot() {
    for (const char* stage : {"open", "add", "replace", "select", "close"}) {
        discs = {};
        discs.images.push_back("existing-other.chd");
        discs.selected = 1;
        discs.fail = stage;
        const int result = emucoreh::ChangeDisc(Callbacks(), "disc-two.chd");
        assert(result < 0 && result != emucoreh::kDiscRollbackFailed);
        assert(discs.selected == 1 && !discs.open);
        assert((discs.images == std::vector<std::string>{"disc-one.chd", "existing-other.chd"}));
        assert(discs.calls.back() == "close");
    }
}

void FailureSelectingExistingImageDoesNotDeleteIt() {
    discs = {};
    discs.images.push_back("disc-two.chd");
    discs.fail = "close";
    assert(emucoreh::ChangeDisc(Callbacks(), "disc-two.chd") < 0);
    assert(discs.images.size() == 2 && discs.selected == 0 && !discs.open);
}

void MissingCallbacksAndInvalidContentDoNotTouchTray() {
    for (unsigned field = 0; field < 7; ++field) {
        discs = {};
        auto callbacks = Callbacks();
        switch (field) {
            case 0: callbacks.set_eject_state = nullptr; break;
            case 1: callbacks.get_eject_state = nullptr; break;
            case 2: callbacks.get_image_index = nullptr; break;
            case 3: callbacks.set_image_index = nullptr; break;
            case 4: callbacks.get_num_images = nullptr; break;
            case 5: callbacks.replace_image_index = nullptr; break;
            case 6: callbacks.add_image_index = nullptr; break;
        }
        assert(emucoreh::ChangeDisc(callbacks, "disc-two.chd") < 0);
        assert(discs.calls.empty());
    }
    discs = {};
    assert(emucoreh::ChangeDisc(Callbacks(), "") < 0);
    assert(discs.calls.empty());
    discs.images.clear();
    assert(emucoreh::ChangeDisc(Callbacks(), "disc-two.chd") < 0);
    assert(discs.calls.empty());
    discs = {};
    discs.selected = 3;
    assert(emucoreh::ChangeDisc(Callbacks(), "disc-two.chd") < 0);
    assert(discs.calls.empty());
}

void BasicInterfaceCanSwapAndRollbackFailureIsReported() {
    discs = {};
    auto basic = Callbacks();
    basic.get_image_path = nullptr;
    assert(emucoreh::ChangeDisc(basic, "disc-two.chd") == 0);
    assert(discs.selected == 1 && !discs.open);
    discs = {};
    discs.fail = "replace";
    discs.failRollback = true;
    assert(emucoreh::ChangeDisc(Callbacks(), "disc-two.chd") == emucoreh::kDiscRollbackFailed);
    assert(discs.selected == 0 && discs.images.size() == 1);
}
}

int main() {
    SuccessfulSwapKeepsOriginalImageAndUsesRealTraySequence();
    ExistingImageIsReusedWithoutGrowingPlaylistOrMatchingTruncatedPrefix();
    FailedSwapRestoresIndexClosesOldDiscAndRemovesAppendedSlot();
    FailureSelectingExistingImageDoesNotDeleteIt();
    MissingCallbacksAndInvalidContentDoNotTouchTray();
    BasicInterfaceCanSwapAndRollbackFailureIsReported();
    std::cout << "Disc control sequence, reuse, and rollback tests passed\n";
}
