#include "dc_renderer.h"
#include "dc_utils.h"

#include <algorithm>
#include <cmath>

namespace dcui {

void DcRenderer::ActivateGameLaunch(int absGameIdx) {
    if (absGameIdx < 0 || absGameIdx >= static_cast<int>(games_.size())) {
        HostPlaySfx(SFX_ERROR);
        return;
    }
    const GameEntry& g = games_[absGameIdx];
    if (g.discCount > 1 && g.discPaths.size() > 1) {
        disc_picker_index_ = 0;
        overlay_ = OVERLAY_DISC_PICKER;
        HostPlaySfx(SFX_CONFIRM);
    } else {
        StartLaunchGame(g.path, g.title, g.serial, g.coverPath, g.platformId);
    }
}

void DcRenderer::StartLaunchGame(
    const std::string& path,
    const std::string& title,
    const std::string& serial,
    const std::string& coverPath,
    const std::string& platformId) {
    if (path.empty() || launching_) {
        HostPlaySfx(SFX_ERROR);
        return;
    }
    launching_ = true;
    launch_is_save_ = false;
    launch_dispatched_ = false;
    launch_t_ = 0.0f;
    launch_path_ = path;
    launch_title_ = title;
    launch_serial_ = serial;
    launch_cover_ = coverPath;
    launch_platform_id_ = platformId;
    overlay_ = OVERLAY_NONE;
    static_idle_frames_ = 0;
    HostPlaySfx(SFX_LAUNCH);
}

void DcRenderer::StartLaunchQuickSave(
    const std::string& path,
    int slot,
    const std::string& title,
    const std::string& serial,
    const std::string& previewPath,
    const std::string& platformId) {
    if (path.empty() || slot < 0 || launching_) {
        HostPlaySfx(SFX_ERROR);
        return;
    }
    launching_ = true;
    launch_is_save_ = true;
    launch_dispatched_ = false;
    launch_t_ = 0.0f;
    launch_path_ = path;
    launch_save_slot_ = slot;
    launch_title_ = title;
    launch_serial_ = serial;
    launch_cover_ = previewPath;
    launch_platform_id_ = platformId;
    overlay_ = OVERLAY_NONE;
    static_idle_frames_ = 0;
    HostPlaySfx(SFX_LAUNCH);
}

void DcRenderer::StartLaunchSave(int filteredIndex) {
    const std::vector<int> filtered = FilteredSaveIndices();
    if (filteredIndex < 0 || filteredIndex >= static_cast<int>(filtered.size())) {
        HostPlaySfx(SFX_ERROR);
        return;
    }
    const SaveEntry& sv = saves_[filtered[filteredIndex]];
    if (sv.gamePath.empty()) {
        HostPlaySfx(SFX_ERROR);
        return;
    }
    StartLaunchQuickSave(sv.gamePath, sv.slotNum, sv.title, sv.serial, sv.previewPath, "");
}

void DcRenderer::PushSettingsToHost() {
    const int32_t packed[12] = {
        settings_.theme,
        settings_.aspectMode,
        settings_.motionMode,
        settings_.scanlinesMode,
        settings_.bootAnimMode,
        settings_.keyTone ? 1 : 0,
        settings_.ambientSound ? 1 : 0,
        settings_.swapConfirm ? 1 : 0,
        settings_.touchControls ? 1 : 0,
        settings_.time24h ? 1 : 0,
        settings_.dateFormat,
        settings_.keepScreenOn ? 1 : 0,
    };
    HostNotifySettingsChanged(packed, 12);
}

void DcRenderer::CycleSetting(int row, int delta) {
    const int dir = (delta >= 0) ? 1 : -1;
    switch (row) {
        case 0: {
            const bool oldLight = IsLightTheme();
            settings_.theme = (settings_.theme + dir + 5) % 5;
            if (oldLight != IsLightTheme()) ClearTextCache();
            HostPlaySfx(SFX_MOVE);
            PushSettingsToHost();
            break;
        }
        case 1:
            settings_.aspectMode = (settings_.aspectMode + dir + 4) % 4;
            HostPlaySfx(SFX_MOVE);
            PushSettingsToHost();
            break;
        case 2:
            settings_.motionMode = (settings_.motionMode + dir + 4) % 4;
            HostPlaySfx(SFX_MOVE);
            PushSettingsToHost();
            break;
        case 3:
            settings_.scanlinesMode = (settings_.scanlinesMode + dir + 3) % 3;
            HostPlaySfx(SFX_MOVE);
            PushSettingsToHost();
            break;
        case 4:
            settings_.bootAnimMode = (settings_.bootAnimMode + dir + 3) % 3;
            HostPlaySfx(SFX_MOVE);
            PushSettingsToHost();
            break;
        case 5:
            settings_.keyTone = !settings_.keyTone;
            PushSettingsToHost();
            if (settings_.keyTone) HostPlaySfx(SFX_CONFIRM);
            break;
        case 6:
            settings_.ambientSound = !settings_.ambientSound;
            HostPlaySfx(SFX_MOVE);
            PushSettingsToHost();
            break;
        case 7:
            settings_.swapConfirm = !settings_.swapConfirm;
            HostPlaySfx(SFX_MOVE);
            PushSettingsToHost();
            break;
        case 8:
            settings_.touchControls = !settings_.touchControls;
            HostPlaySfx(SFX_MOVE);
            PushSettingsToHost();
            break;
        case 9:
            overlay_ = OVERLAY_TOUCH_GUIDE;
            HostPlaySfx(SFX_CONFIRM);
            break;
        case 10:
            settings_.time24h = !settings_.time24h;
            HostPlaySfx(SFX_MOVE);
            PushSettingsToHost();
            break;
        case 11:
            settings_.dateFormat = (settings_.dateFormat + dir + 3) % 3;
            HostPlaySfx(SFX_MOVE);
            PushSettingsToHost();
            break;
        case 12:
            settings_.keepScreenOn = !settings_.keepScreenOn;
            HostPlaySfx(SFX_MOVE);
            PushSettingsToHost();
            break;
        case 13:
            overlay_ = OVERLAY_SYSTEM_INFO;
            HostPlaySfx(SFX_CONFIRM);
            break;
        case 14:
            dialog_btn_ = 1;
            overlay_ = OVERLAY_RESTORE_CONFIRM;
            HostPlaySfx(SFX_CONFIRM);
            break;
        default:
            break;
    }
}

void DcRenderer::OnKey(int key) {
    static_idle_frames_ = 0;
    if (launching_) return;

    if (boot_active_) {
        if (boot_t_ > 0.14f) {
            boot_active_ = false;
            screen_fade_t_ = 0.0f;
        }
        return;
    }

    // Overlays first
    if (overlay_ != OVERLAY_NONE) {
        if (overlay_ == OVERLAY_EXIT_CONFIRM ||
            overlay_ == OVERLAY_DELETE_SAVE_CONFIRM ||
            overlay_ == OVERLAY_DELETE_VMU_FILE_CONFIRM ||
            overlay_ == OVERLAY_FORMAT_VMU_CONFIRM ||
            overlay_ == OVERLAY_RESTORE_CONFIRM) {
            if (key == KEY_LEFT || key == KEY_RIGHT) {
                dialog_btn_ = 1 - dialog_btn_;
                HostPlaySfx(SFX_MOVE);
            } else if (key == KEY_BACK) {
                overlay_ = OVERLAY_NONE;
                HostPlaySfx(SFX_CANCEL);
            } else if (key == KEY_CONFIRM || key == KEY_START) {
                if (dialog_btn_ == 0) {
                    if (overlay_ == OVERLAY_EXIT_CONFIRM) {
                        overlay_ = OVERLAY_NONE;
                        HostPlaySfx(SFX_CONFIRM);
                        HostNotifyExitRequested();
                    } else if (overlay_ == OVERLAY_DELETE_SAVE_CONFIRM) {
                        const int delIdx = pending_delete_save_idx_;
                        overlay_ = OVERLAY_NONE;
                        if (delIdx >= 0) {
                            HostNotifyDeleteSave(delIdx);
                        }
                        HostPlaySfx(SFX_CONFIRM);
                    } else if (overlay_ == OVERLAY_DELETE_VMU_FILE_CONFIRM) {
                        const int cIdx = pending_vmu_card_idx_;
                        const int fIdx = pending_vmu_file_idx_;
                        overlay_ = OVERLAY_NONE;
                        if (cIdx >= 0 && fIdx >= 0) {
                            HostNotifyVmuAction(VMU_ACTION_DELETE_FILE, cIdx, fIdx);
                        }
                        HostPlaySfx(SFX_CONFIRM);
                    } else if (overlay_ == OVERLAY_FORMAT_VMU_CONFIRM) {
                        const int cIdx = pending_vmu_card_idx_;
                        overlay_ = OVERLAY_NONE;
                        if (cIdx >= 0) {
                            HostNotifyVmuAction(VMU_ACTION_FORMAT_CARD, cIdx, 0);
                        }
                        HostPlaySfx(SFX_CONFIRM);
                    } else if (overlay_ == OVERLAY_RESTORE_CONFIRM) {
                        overlay_ = OVERLAY_NONE;
                        const bool oldLight = IsLightTheme();
                        settings_ = ShellSettings{};
                        if (oldLight != IsLightTheme()) ClearTextCache();
                        PushSettingsToHost();
                        HostPlaySfx(SFX_CONFIRM);
                    }
                } else {
                    overlay_ = OVERLAY_NONE;
                    HostPlaySfx(SFX_CANCEL);
                }
            }
            return;
        }

        if (overlay_ == OVERLAY_GAME_OPTIONS) {
            if (key == KEY_UP) {
                options_index_ = (options_index_ + 4) % 5;
                HostPlaySfx(SFX_MOVE);
            } else if (key == KEY_DOWN) {
                options_index_ = (options_index_ + 1) % 5;
                HostPlaySfx(SFX_MOVE);
            } else if (key == KEY_BACK || key == KEY_OPTIONS) {
                overlay_ = OVERLAY_NONE;
                HostPlaySfx(SFX_CANCEL);
            } else if (key == KEY_CONFIRM || key == KEY_START) {
                const int absIdx = ActiveTabAbsGameIndex(game_index_);
                if (absIdx < 0 || absIdx >= static_cast<int>(games_.size())) {
                    overlay_ = OVERLAY_NONE;
                    return;
                }
                const GameEntry g = games_[absIdx];
                if (options_index_ == 0) {
                    overlay_ = OVERLAY_NONE;
                    ActivateGameLaunch(absIdx);
                } else if (options_index_ == 1) {
                    overlay_ = OVERLAY_NONE;
                    if (g.latestSaveSlot >= 0) {
                        StartLaunchQuickSave(g.path, g.latestSaveSlot, g.title, g.serial, g.coverPath, g.platformId);
                    } else {
                        HostPlaySfx(SFX_ERROR);
                    }
                } else if (options_index_ == 2) {
                    overlay_ = OVERLAY_NONE;
                    file_filter_game_path_ = g.path;
                    file_filter_game_title_ = g.title;
                    file_tab_ = 1;
                    save_page_ = 0;
                    save_slot_index_ = 0;
                    file_focus_pane_ = 0;
                    return_screen_ = SCREEN_GAMES;
                    SwitchScreen(SCREEN_FILE);
                    HostPlaySfx(SFX_CONFIRM);
                } else if (options_index_ == 3) {
                    overlay_ = OVERLAY_GAME_INFO;
                    HostPlaySfx(SFX_CONFIRM);
                } else if (options_index_ == 4) {
                    overlay_ = OVERLAY_NONE;
                    HostPlaySfx(SFX_CONFIRM);
                    HostNotifyOpenGameSettings(absIdx);
                }
            }
            return;
        }

        if (overlay_ == OVERLAY_DISC_PICKER) {
            const int absIdx = ActiveTabAbsGameIndex(game_index_);
            const int nDiscs = (absIdx >= 0 && absIdx < static_cast<int>(games_.size()))
                ? std::max(1, static_cast<int>(games_[absIdx].discLabels.size()))
                : 1;
            if (key == KEY_UP) {
                disc_picker_index_ = (disc_picker_index_ - 1 + nDiscs) % nDiscs;
                HostPlaySfx(SFX_MOVE);
            } else if (key == KEY_DOWN) {
                disc_picker_index_ = (disc_picker_index_ + 1) % nDiscs;
                HostPlaySfx(SFX_MOVE);
            } else if (key == KEY_BACK) {
                overlay_ = OVERLAY_NONE;
                HostPlaySfx(SFX_CANCEL);
            } else if (key == KEY_CONFIRM || key == KEY_START) {
                if (absIdx >= 0 && absIdx < static_cast<int>(games_.size())) {
                    const GameEntry g = games_[absIdx];
                    const std::string discPath = (disc_picker_index_ < static_cast<int>(g.discPaths.size()))
                        ? g.discPaths[disc_picker_index_] : g.path;
                    overlay_ = OVERLAY_NONE;
                    StartLaunchGame(discPath, g.title, g.serial, g.coverPath, g.platformId);
                }
            }
            return;
        }

        // Info / Touch guide overlays dismiss on confirm or back
        if (key == KEY_CONFIRM || key == KEY_BACK || key == KEY_START) {
            overlay_ = OVERLAY_NONE;
            HostPlaySfx(SFX_CANCEL);
        }
        return;
    }

    // Main Menu 4-Orb Hub
    if (screen_ == SCREEN_MAIN_MENU) {
        if (key == KEY_BACK) {
            dialog_btn_ = 1;
            overlay_ = OVERLAY_EXIT_CONFIRM;
            HostPlaySfx(SFX_CONFIRM);
        } else if (key == KEY_LEFT || key == KEY_UP) {
            main_index_ = (main_index_ + 3) % 4;
            HostPlaySfx(SFX_MOVE);
        } else if (key == KEY_RIGHT || key == KEY_DOWN) {
            main_index_ = (main_index_ + 1) % 4;
            HostPlaySfx(SFX_MOVE);
        } else if (key == KEY_CONFIRM || key == KEY_START) {
            HostPlaySfx(SFX_CONFIRM);
            return_screen_ = SCREEN_MAIN_MENU;
            if (main_index_ == 0) {
                SwitchScreen(SCREEN_GAMES);
            } else if (main_index_ == 1) {
                file_tab_ = 2;
                file_focus_pane_ = 0;
                if (!vmu_cards_.empty()) {
                    HostNotifyVmuAction(VMU_ACTION_SELECT_CARD, vmu_card_index_, 0);
                }
                SwitchScreen(SCREEN_FILE);
            } else if (main_index_ == 2) {
                file_tab_ = 0;
                file_focus_pane_ = 0;
                save_page_ = 0;
                save_slot_index_ = 0;
                file_filter_game_path_.clear();
                file_filter_game_title_.clear();
                SwitchScreen(SCREEN_FILE);
            } else {
                SwitchScreen(SCREEN_SETTINGS);
            }
        }
        return;
    }

    // Games Screen (Console Tabs + Game List)
    if (screen_ == SCREEN_GAMES) {
        const LayoutMetrics m = ComputeMetrics();
        if (key == KEY_BACK) {
            SwitchScreen(SCREEN_MAIN_MENU);
            HostPlaySfx(SFX_CANCEL);
            return;
        }
        const int numTabs = static_cast<int>(display_tabs_.size());
        if (numTabs > 0 && (key == KEY_PREV_TAB || key == KEY_NEXT_TAB || key == KEY_LEFT || key == KEY_RIGHT)) {
            const int delta = (key == KEY_PREV_TAB || key == KEY_LEFT) ? -1 : 1;
            if (numTabs > 1) {
                tab_index_ = (tab_index_ + delta + numTabs) % numTabs;
                game_index_ = 0;
                game_scroll_y_ = 0.0f;
                game_scroll_target_y_ = 0.0f;
                EnsureTabVisible(m.bodyW);
                HostPlaySfx(SFX_TAB);
            }
            return;
        }
        const int count = ActiveTabGameCount();
        if (count <= 0) return;
        const float listH = m.portrait ? std::max(90.0f, m.bodyH - 256.0f) : std::max(120.0f, m.bodyH - 50.0f);
        if (key == KEY_UP) {
            game_index_ = (game_index_ - 1 + count) % count;
            EnsureGameVisible(count, listH, 72.0f);
            HostPlaySfx(SFX_MOVE);
        } else if (key == KEY_DOWN) {
            game_index_ = (game_index_ + 1) % count;
            EnsureGameVisible(count, listH, 72.0f);
            HostPlaySfx(SFX_MOVE);
        } else if (key == KEY_CONFIRM || key == KEY_START) {
            ActivateGameLaunch(ActiveTabAbsGameIndex(game_index_));
        } else if (key == KEY_SECONDARY) {
            const int absIdx = ActiveTabAbsGameIndex(game_index_);
            if (absIdx >= 0 && absIdx < static_cast<int>(games_.size()) && games_[absIdx].latestSaveSlot >= 0) {
                const GameEntry& g = games_[absIdx];
                StartLaunchQuickSave(g.path, g.latestSaveSlot, g.title, g.serial, g.coverPath, g.platformId);
            } else {
                HostPlaySfx(SFX_ERROR);
            }
        } else if (key == KEY_OPTIONS) {
            options_index_ = 0;
            overlay_ = OVERLAY_GAME_OPTIONS;
            HostPlaySfx(SFX_CONFIRM);
        }
        return;
    }

    // File & VMU Screen
    if (screen_ == SCREEN_FILE) {
        const LayoutMetrics m = ComputeMetrics();
        if (key == KEY_BACK) {
            SwitchScreen(return_screen_);
            HostPlaySfx(SFX_CANCEL);
            return;
        }
        if (key == KEY_PREV_TAB || key == KEY_NEXT_TAB) {
            file_tab_ = (file_tab_ + (key == KEY_PREV_TAB ? 2 : 1)) % 3;
            file_focus_pane_ = 0;
            save_page_ = 0;
            if (file_tab_ == 2 && !vmu_cards_.empty()) {
                HostNotifyVmuAction(VMU_ACTION_SELECT_CARD, vmu_card_index_, 0);
            }
            HostPlaySfx(SFX_TAB);
            return;
        }
        if (file_tab_ == 2) {
            if (vmu_cards_.empty()) {
                if (key == KEY_CONFIRM || key == KEY_START) {
                    HostPlaySfx(SFX_CONFIRM);
                    HostNotifyVmuAction(VMU_ACTION_CREATE_DEFAULTS, 0, 0);
                }
                return;
            }
            const int numCards = static_cast<int>(vmu_cards_.size());
            if (key == KEY_LEFT) {
                vmu_card_index_ = (vmu_card_index_ - 1 + numCards) % numCards;
                vmu_file_index_ = 0;
                vmu_files_scroll_y_ = 0.0f;
                HostNotifyVmuAction(VMU_ACTION_SELECT_CARD, vmu_card_index_, 0);
                HostPlaySfx(SFX_MOVE);
            } else if (key == KEY_RIGHT) {
                vmu_card_index_ = (vmu_card_index_ + 1) % numCards;
                vmu_file_index_ = 0;
                vmu_files_scroll_y_ = 0.0f;
                HostNotifyVmuAction(VMU_ACTION_SELECT_CARD, vmu_card_index_, 0);
                HostPlaySfx(SFX_MOVE);
            } else if (key == KEY_UP && !vmu_files_.empty()) {
                const int nf = static_cast<int>(vmu_files_.size());
                vmu_file_index_ = (vmu_file_index_ - 1 + nf) % nf;
                HostPlaySfx(SFX_MOVE);
            } else if (key == KEY_DOWN && !vmu_files_.empty()) {
                const int nf = static_cast<int>(vmu_files_.size());
                vmu_file_index_ = (vmu_file_index_ + 1) % nf;
                HostPlaySfx(SFX_MOVE);
            } else if ((key == KEY_SECONDARY || key == KEY_CONFIRM) && !vmu_files_.empty()) {
                pending_vmu_card_idx_ = vmu_card_index_;
                pending_vmu_file_idx_ = vmu_file_index_;
                dialog_btn_ = 1;
                overlay_ = OVERLAY_DELETE_VMU_FILE_CONFIRM;
                HostPlaySfx(SFX_CONFIRM);
            } else if (key == KEY_OPTIONS) {
                pending_vmu_card_idx_ = vmu_card_index_;
                dialog_btn_ = 1;
                overlay_ = OVERLAY_FORMAT_VMU_CONFIRM;
                HostPlaySfx(SFX_CONFIRM);
            }
            return;
        }

        // Quick Saves Grid navigation
        const std::vector<int> filtered = FilteredSaveIndices();
        const int totalFiltered = static_cast<int>(filtered.size());
        const int cols = m.portrait ? 3 : 4;
        const int perPage = cols * 3;
        const int totalPages = std::max(1, (totalFiltered + perPage - 1) / perPage);
        const int curPos = save_page_ * perPage + save_slot_index_;
        const bool hasSave = (curPos >= 0 && curPos < totalFiltered);

        if (file_focus_pane_ == 0) {
            const int col = save_slot_index_ % cols;
            if (key == KEY_LEFT) {
                if (col > 0) {
                    save_slot_index_--;
                    HostPlaySfx(SFX_MOVE);
                } else if (save_page_ > 0) {
                    save_page_--;
                    save_slot_index_ += (cols - 1);
                    HostPlaySfx(SFX_MOVE);
                }
            } else if (key == KEY_RIGHT) {
                if (col < cols - 1) {
                    save_slot_index_++;
                    HostPlaySfx(SFX_MOVE);
                } else if (!m.portrait) {
                    file_focus_pane_ = 1;
                    file_action_idx_ = hasSave ? 0 : 2;
                    HostPlaySfx(SFX_MOVE);
                } else if (save_page_ < totalPages - 1) {
                    save_page_++;
                    save_slot_index_ -= col;
                    HostPlaySfx(SFX_MOVE);
                }
            } else if (key == KEY_UP) {
                if (save_slot_index_ >= cols) {
                    save_slot_index_ -= cols;
                    HostPlaySfx(SFX_MOVE);
                }
            } else if (key == KEY_DOWN) {
                if (save_slot_index_ + cols < perPage) {
                    save_slot_index_ += cols;
                    HostPlaySfx(SFX_MOVE);
                }
            } else if (key == KEY_CONFIRM || key == KEY_START) {
                if (hasSave) {
                    if (key == KEY_START || m.portrait) {
                        StartLaunchSave(curPos);
                    } else {
                        file_focus_pane_ = 1;
                        file_action_idx_ = 0;
                        HostPlaySfx(SFX_CONFIRM);
                    }
                } else {
                    HostPlaySfx(SFX_ERROR);
                }
            } else if (key == KEY_SECONDARY && hasSave) {
                pending_delete_save_idx_ = filtered[curPos];
                dialog_btn_ = 1;
                overlay_ = OVERLAY_DELETE_SAVE_CONFIRM;
                HostPlaySfx(SFX_CONFIRM);
            }
        } else {
            if (key == KEY_LEFT) {
                if (file_action_idx_ > 0) {
                    file_action_idx_--;
                    HostPlaySfx(SFX_MOVE);
                } else {
                    file_focus_pane_ = 0;
                    HostPlaySfx(SFX_MOVE);
                }
            } else if (key == KEY_RIGHT) {
                if (file_action_idx_ < 2) {
                    file_action_idx_++;
                    HostPlaySfx(SFX_MOVE);
                }
            } else if (key == KEY_UP) {
                file_focus_pane_ = 0;
                HostPlaySfx(SFX_MOVE);
            } else if (key == KEY_CONFIRM) {
                if (file_action_idx_ == 0 && hasSave) {
                    StartLaunchSave(curPos);
                } else if (file_action_idx_ == 1 && hasSave) {
                    pending_delete_save_idx_ = filtered[curPos];
                    dialog_btn_ = 1;
                    overlay_ = OVERLAY_DELETE_SAVE_CONFIRM;
                    HostPlaySfx(SFX_CONFIRM);
                } else if (file_action_idx_ == 2) {
                    SwitchScreen(return_screen_);
                    HostPlaySfx(SFX_CANCEL);
                } else {
                    HostPlaySfx(SFX_ERROR);
                }
            }
        }
        return;
    }

    // Settings Screen
    if (screen_ == SCREEN_SETTINGS) {
        const LayoutMetrics m = ComputeMetrics();
        if (key == KEY_BACK) {
            SwitchScreen(SCREEN_MAIN_MENU);
            HostPlaySfx(SFX_CANCEL);
            return;
        }
        if (key == KEY_UP) {
            settings_index_ = (settings_index_ - 1 + kSettingsCount) % kSettingsCount;
            EnsureSettingVisible(m.bodyH);
            HostPlaySfx(SFX_MOVE);
        } else if (key == KEY_DOWN) {
            settings_index_ = (settings_index_ + 1) % kSettingsCount;
            EnsureSettingVisible(m.bodyH);
            HostPlaySfx(SFX_MOVE);
        } else if (key == KEY_LEFT) {
            CycleSetting(settings_index_, -1);
        } else if (key == KEY_RIGHT || key == KEY_CONFIRM) {
            CycleSetting(settings_index_, 1);
        }
    }
}

void DcRenderer::OnTouchDown(float normX, float normY) {
    static_idle_frames_ = 0;
    float vx = 0.0f, vy = 0.0f;
    SurfaceNormToVirtual(normX, normY, vx, vy);
    touch_down_vx_ = vx;
    touch_down_vy_ = vy;
    touch_last_vx_ = vx;
    touch_last_vy_ = vy;
    touch_moved_ = false;
    touch_dragging_ = false;
    touch_drag_target_ = 0;

    if (overlay_ == OVERLAY_NONE && !boot_active_ && !launching_) {
        const LayoutMetrics m = ComputeMetrics();
        if (screen_ == SCREEN_MAIN_MENU) {
            touch_drag_target_ = 4;
        } else if (screen_ == SCREEN_GAMES) {
            if (vy >= m.bodyTop - 4.0f && vy <= m.bodyTop + 44.0f) {
                touch_drag_target_ = 3;
            } else if ((m.portrait && vy >= m.bodyTop + 48.0f) || (!m.portrait && vx < m.padX + m.bodyW * 0.56f)) {
                touch_drag_target_ = 1;
            }
        } else if (screen_ == SCREEN_FILE && file_tab_ == 2) {
            touch_drag_target_ = 5;
        } else if (screen_ == SCREEN_SETTINGS) {
            touch_drag_target_ = 2;
        }
    }
}

void DcRenderer::OnTouchMove(float normX, float normY) {
    static_idle_frames_ = 0;
    float vx = 0.0f, vy = 0.0f;
    SurfaceNormToVirtual(normX, normY, vx, vy);
    const float dyTotal = vy - touch_down_vy_;
    const float dxTotal = vx - touch_down_vx_;
    if (!touch_moved_ && (std::fabs(dyTotal) > 10.0f || std::fabs(dxTotal) > 10.0f)) {
        touch_moved_ = true;
    }

    if (touch_drag_target_ == 1 && std::fabs(dyTotal) > 10.0f) {
        touch_dragging_ = true;
        const float delta = touch_last_vy_ - vy;
        game_scroll_target_y_ += delta;
        game_scroll_y_ = game_scroll_target_y_;
    } else if (touch_drag_target_ == 2 && std::fabs(dyTotal) > 10.0f) {
        touch_dragging_ = true;
        const float delta = touch_last_vy_ - vy;
        settings_scroll_target_y_ += delta;
        settings_scroll_y_ = settings_scroll_target_y_;
    } else if (touch_drag_target_ == 3 && std::fabs(dxTotal) > 10.0f) {
        touch_dragging_ = true;
        const float delta = touch_last_vx_ - vx;
        tab_scroll_target_x_ += delta;
        tab_scroll_x_ = tab_scroll_target_x_;
    } else if (touch_drag_target_ == 5 && std::fabs(dyTotal) > 10.0f) {
        touch_dragging_ = true;
        const float delta = touch_last_vy_ - vy;
        vmu_files_scroll_y_ = std::max(0.0f, vmu_files_scroll_y_ + delta);
    } else if (touch_drag_target_ == 4 && std::fabs(dxTotal) > 42.0f) {
        touch_dragging_ = true;
        main_index_ = (main_index_ + (dxTotal < 0.0f ? 1 : 3)) % 4;
        touch_down_vx_ = vx;
        HostPlaySfx(SFX_MOVE);
    }

    touch_last_vx_ = vx;
    touch_last_vy_ = vy;
}

void DcRenderer::OnTouchUp(float normX, float normY) {
    static_idle_frames_ = 0;
    const bool wasDragging = touch_moved_ || touch_dragging_;
    touch_dragging_ = false;
    touch_moved_ = false;
    touch_drag_target_ = 0;
    if (wasDragging || launching_) return;

    if (boot_active_) {
        if (boot_t_ > 0.14f) {
            boot_active_ = false;
            screen_fade_t_ = 0.0f;
        }
        return;
    }

    float vx = 0.0f, vy = 0.0f;
    if (!SurfaceNormToVirtual(normX, normY, vx, vy)) return;
    const HitTarget* hit = FindHit(vx, vy);
    if (!hit) return;

    switch (hit->kind) {
        case HIT_OVERLAY_BACKDROP:
            overlay_ = OVERLAY_NONE;
            HostPlaySfx(SFX_CANCEL);
            break;

        case HIT_OVERLAY_ITEM:
            if (overlay_ == OVERLAY_EXIT_CONFIRM ||
                overlay_ == OVERLAY_DELETE_SAVE_CONFIRM ||
                overlay_ == OVERLAY_DELETE_VMU_FILE_CONFIRM ||
                overlay_ == OVERLAY_FORMAT_VMU_CONFIRM ||
                overlay_ == OVERLAY_RESTORE_CONFIRM) {
                dialog_btn_ = hit->index;
                OnKey(KEY_CONFIRM);
            } else if (overlay_ == OVERLAY_GAME_OPTIONS) {
                options_index_ = hit->index;
                OnKey(KEY_CONFIRM);
            } else if (overlay_ == OVERLAY_DISC_PICKER) {
                disc_picker_index_ = hit->index;
                OnKey(KEY_CONFIRM);
            } else {
                overlay_ = OVERLAY_NONE;
                HostPlaySfx(SFX_CANCEL);
            }
            break;

        case HIT_TOP_EXIT:
            dialog_btn_ = 1;
            overlay_ = OVERLAY_EXIT_CONFIRM;
            HostPlaySfx(SFX_CONFIRM);
            break;

        case HIT_BOTTOM_ACTION:
            OnKey(hit->index);
            break;

        case HIT_HUB_ORB:
            if (main_index_ == hit->index) {
                OnKey(KEY_CONFIRM);
            } else {
                main_index_ = hit->index;
                HostPlaySfx(SFX_MOVE);
            }
            break;

        case HIT_CONSOLE_TAB:
            if (hit->index >= 0 && hit->index < static_cast<int>(display_tabs_.size())) {
                tab_index_ = hit->index;
                game_index_ = 0;
                game_scroll_y_ = 0.0f;
                game_scroll_target_y_ = 0.0f;
                EnsureTabVisible(ComputeMetrics().bodyW);
                HostPlaySfx(SFX_TAB);
            }
            break;

        case HIT_GAME_ROW:
            if (game_index_ == hit->index) {
                ActivateGameLaunch(ActiveTabAbsGameIndex(hit->index));
            } else {
                game_index_ = hit->index;
                HostPlaySfx(SFX_MOVE);
            }
            break;

        case HIT_GAME_START:
            ActivateGameLaunch(hit->index);
            break;

        case HIT_GAME_CONTINUE:
            if (hit->index >= 0 && hit->index < static_cast<int>(games_.size())) {
                const GameEntry& g = games_[hit->index];
                if (g.latestSaveSlot >= 0) {
                    StartLaunchQuickSave(g.path, g.latestSaveSlot, g.title, g.serial, g.coverPath, g.platformId);
                }
            }
            break;

        case HIT_GAME_OPTIONS:
            options_index_ = 0;
            overlay_ = OVERLAY_GAME_OPTIONS;
            HostPlaySfx(SFX_CONFIRM);
            break;

        case HIT_FILE_TAB:
            file_tab_ = std::clamp(hit->index, 0, 2);
            file_focus_pane_ = 0;
            save_page_ = 0;
            if (file_tab_ == 2 && !vmu_cards_.empty()) {
                HostNotifyVmuAction(VMU_ACTION_SELECT_CARD, vmu_card_index_, 0);
            }
            HostPlaySfx(SFX_TAB);
            break;

        case HIT_SAVE_SLOT: {
            const std::vector<int> filtered = FilteredSaveIndices();
            const int cols = ComputeMetrics().portrait ? 3 : 4;
            const int perPage = cols * 3;
            const int filteredPos = save_page_ * perPage + hit->index;
            const bool same = (file_focus_pane_ == 0 && save_slot_index_ == hit->index);
            file_focus_pane_ = 0;
            save_slot_index_ = hit->index;
            if (filteredPos < static_cast<int>(filtered.size())) {
                if (same) {
                    StartLaunchSave(filteredPos);
                } else {
                    HostPlaySfx(SFX_MOVE);
                }
            } else {
                HostPlaySfx(SFX_MOVE);
            }
            break;
        }

        case HIT_SAVE_ACTION: {
            const std::vector<int> filtered = FilteredSaveIndices();
            const int cols = ComputeMetrics().portrait ? 3 : 4;
            const int perPage = cols * 3;
            const int filteredPos = save_page_ * perPage + save_slot_index_;
            file_focus_pane_ = 1;
            file_action_idx_ = hit->index;
            if (hit->index == 0 && filteredPos < static_cast<int>(filtered.size())) {
                StartLaunchSave(filteredPos);
            } else if (hit->index == 1 && filteredPos < static_cast<int>(filtered.size())) {
                pending_delete_save_idx_ = filtered[filteredPos];
                dialog_btn_ = 1;
                overlay_ = OVERLAY_DELETE_SAVE_CONFIRM;
                HostPlaySfx(SFX_CONFIRM);
            } else if (hit->index == 2) {
                SwitchScreen(return_screen_);
                HostPlaySfx(SFX_CANCEL);
            }
            break;
        }

        case HIT_SAVE_PAGE_PREV:
            if (save_page_ > 0) {
                save_page_--;
                HostPlaySfx(SFX_MOVE);
            }
            break;

        case HIT_SAVE_PAGE_NEXT: {
            const int cols = ComputeMetrics().portrait ? 3 : 4;
            const int perPage = cols * 3;
            const int totalPages = std::max(1, (static_cast<int>(FilteredSaveIndices().size()) + perPage - 1) / perPage);
            if (save_page_ < totalPages - 1) {
                save_page_++;
                HostPlaySfx(SFX_MOVE);
            }
            break;
        }

        case HIT_VMU_CARD:
            if (hit->index >= 0 && hit->index < static_cast<int>(vmu_cards_.size())) {
                vmu_card_index_ = hit->index;
                vmu_file_index_ = 0;
                vmu_files_scroll_y_ = 0.0f;
                HostNotifyVmuAction(VMU_ACTION_SELECT_CARD, vmu_card_index_, 0);
                HostPlaySfx(SFX_MOVE);
            }
            break;

        case HIT_VMU_FILE:
            vmu_file_index_ = std::clamp(hit->index, 0, std::max(0, static_cast<int>(vmu_files_.size()) - 1));
            HostPlaySfx(SFX_MOVE);
            break;

        case HIT_VMU_ACTION:
            if (hit->index == 0) {
                HostPlaySfx(SFX_CONFIRM);
                HostNotifyVmuAction(VMU_ACTION_CREATE_DEFAULTS, 0, 0);
            } else if (hit->index == 1 && !vmu_cards_.empty()) {
                pending_vmu_card_idx_ = vmu_card_index_;
                dialog_btn_ = 1;
                overlay_ = OVERLAY_FORMAT_VMU_CONFIRM;
                HostPlaySfx(SFX_CONFIRM);
            } else if (hit->index >= 100 && !vmu_files_.empty()) {
                const int fIdx = hit->index - 100;
                if (fIdx >= 0 && fIdx < static_cast<int>(vmu_files_.size())) {
                    vmu_file_index_ = fIdx;
                    pending_vmu_card_idx_ = vmu_card_index_;
                    pending_vmu_file_idx_ = fIdx;
                    dialog_btn_ = 1;
                    overlay_ = OVERLAY_DELETE_VMU_FILE_CONFIRM;
                    HostPlaySfx(SFX_CONFIRM);
                }
            }
            break;

        case HIT_SETTING_LEFT:
            settings_index_ = hit->index;
            CycleSetting(hit->index, -1);
            break;

        case HIT_SETTING_RIGHT:
        case HIT_SETTING_ROW:
            settings_index_ = hit->index;
            CycleSetting(hit->index, 1);
            break;

        default:
            break;
    }
}

void DcRenderer::OnTouchCancel() {
    touch_dragging_ = false;
    touch_moved_ = false;
    touch_drag_target_ = 0;
}

void DcRenderer::OnTouchTwoFingerBack() {
    OnKey(KEY_BACK);
}

void DcRenderer::OnTouchLongPress(float normX, float normY) {
    static_idle_frames_ = 0;
    if (boot_active_ || launching_ || overlay_ != OVERLAY_NONE) return;
    float vx = 0.0f, vy = 0.0f;
    if (!SurfaceNormToVirtual(normX, normY, vx, vy)) return;
    const HitTarget* hit = FindHit(vx, vy);
    if (!hit) return;

    if (hit->kind == HIT_GAME_ROW) {
        game_index_ = hit->index;
        options_index_ = 0;
        overlay_ = OVERLAY_GAME_OPTIONS;
        HostPlaySfx(SFX_CONFIRM);
    } else if (hit->kind == HIT_SAVE_SLOT) {
        const std::vector<int> filtered = FilteredSaveIndices();
        const int cols = ComputeMetrics().portrait ? 3 : 4;
        const int perPage = cols * 3;
        const int filteredPos = save_page_ * perPage + hit->index;
        if (filteredPos < static_cast<int>(filtered.size())) {
            save_slot_index_ = hit->index;
            pending_delete_save_idx_ = filtered[filteredPos];
            dialog_btn_ = 1;
            overlay_ = OVERLAY_DELETE_SAVE_CONFIRM;
            HostPlaySfx(SFX_CONFIRM);
        }
    } else if (hit->kind == HIT_VMU_FILE) {
        if (hit->index >= 0 && hit->index < static_cast<int>(vmu_files_.size())) {
            vmu_file_index_ = hit->index;
            pending_vmu_card_idx_ = vmu_card_index_;
            pending_vmu_file_idx_ = hit->index;
            dialog_btn_ = 1;
            overlay_ = OVERLAY_DELETE_VMU_FILE_CONFIRM;
            HostPlaySfx(SFX_CONFIRM);
        }
    }
}

} // namespace dcui
