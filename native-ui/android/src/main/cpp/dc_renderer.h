#pragma once

#include <GLES3/gl3.h>
#include <chrono>
#include <cstdint>
#include <ctime>
#include <deque>
#include <list>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace dcui {

// Host bridge callbacks implemented in dc_jni.cpp
void HostNotifyGameLaunch(const std::string& gamePath);
void HostNotifySaveLaunch(const std::string& gamePath, int slot);
void HostNotifyDeleteSave(int saveIndex);
void HostNotifyVmuAction(int action, int cardIndex, int fileIndex);
void HostNotifyOpenGameSettings(int gameIndex);
void HostNotifySettingsChanged(const int32_t* values, int count);
void HostNotifyExitRequested();
void HostPlaySfx(int kind);

bool HostRasterizeText(
    const std::string& text,
    float virtualFontSize,
    int style,
    float maxVirtualWidth,
    int maxLines,
    int align,
    float rasterScale,
    int shadowMode,
    int& outTexW,
    int& outTexH,
    float& outLogW,
    float& outLogH,
    std::vector<uint8_t>& outRgba);

void HostRequestDecodeImage(const std::string& path, int maxDim);

struct RgbaColor {
    float r, g, b, a;
};

struct ConsoleTabInfo {
    std::string id;
    std::string label;
    RgbaColor accent{0.93f, 0.33f, 0.13f, 1.0f};
};

struct DisplayTab {
    std::string id;
    std::string label;
    RgbaColor accent{0.93f, 0.33f, 0.13f, 1.0f};
    bool isRecent = false;
    std::vector<int> gameIndices;
};

struct GameEntry {
    std::string title;
    std::string path;
    std::string serial;
    std::string coverPath;
    std::string sizeStr;
    std::string dateStr;
    std::string platformId;
    std::string platformLabel;
    int saveCount = 0;
    int latestSaveSlot = -1;
    int discCount = 1;
    int recentRank = -1; // >= 0 if launched recently (0 = most recent), -1 otherwise
    std::vector<std::string> discLabels;
    std::vector<std::string> discPaths;
};

struct SaveEntry {
    std::string title;
    std::string slotLabel;
    int slotNum = 0;
    std::string dateStr;
    std::string sizeStr;
    std::string serial;
    std::string platformLabel;
    std::string previewPath;
    std::string savePath;
    std::string gamePath;
};

struct VmuCardEntry {
    std::string portName;
    std::string fileName;
    std::string sizeStr;
    std::string dateStr;
    int freeBlocks = 200;
    int totalBlocks = 200;
    bool formatted = false;
    int fileCount = 0;
};

struct VmuFileEntry {
    std::string fileName;
    std::string description;
    std::string dateStr;
    int blocks = 0;
    std::string iconPath;
};

struct ShellSettings {
    int theme = 0;         // 0=Pearl, 1=PAL Blue, 2=Sports Black, 3=Katana Devkit, 4=Saturn Midnight
    int aspectMode = 0;    // 0=Auto Full, 1=16:9, 2=16:10, 3=4:3
    int motionMode = 0;    // 0=Smooth 60Hz, 1=Calm, 2=Power Saver 30Hz, 3=Static
    int scanlinesMode = 0; // 0=Off, 1=Subtle VGA, 2=Classic CRT
    int bootAnimMode = 0;  // 0=Full, 1=Fast, 2=Off
    bool keyTone = true;
    bool ambientSound = false;
    bool swapConfirm = false;
    bool touchControls = true;
    bool time24h = true;
    int dateFormat = 0;    // 0=DD/MM/YYYY, 1=MM/DD/YYYY, 2=YYYY/MM/DD
    bool keepScreenOn = true;
};

struct SystemStatus {
    int batteryLevel = 100;
    bool batteryCharging = false;
    bool wifiConnected = false;
    bool bluetoothConnected = false;
    bool audioMuted = false;
    std::string freeStorage;
    std::string deviceName;
    std::string appVersion;
    std::string biosSummary;
    int biosReadyCount = 0;
    int biosTotalCount = 4;
};

enum ScreenId {
    SCREEN_MAIN_MENU = 0,
    SCREEN_GAMES = 1,
    SCREEN_FILE = 2,
    SCREEN_SETTINGS = 3
};

enum OverlayId {
    OVERLAY_NONE = 0,
    OVERLAY_EXIT_CONFIRM = 1,
    OVERLAY_GAME_OPTIONS = 2,
    OVERLAY_GAME_INFO = 3,
    OVERLAY_DISC_PICKER = 4,
    OVERLAY_DELETE_SAVE_CONFIRM = 5,
    OVERLAY_DELETE_VMU_FILE_CONFIRM = 6,
    OVERLAY_FORMAT_VMU_CONFIRM = 7,
    OVERLAY_RESTORE_CONFIRM = 8,
    OVERLAY_SYSTEM_INFO = 9,
    OVERLAY_TOUCH_GUIDE = 10
};

enum KeyCode {
    KEY_UP = 0,
    KEY_DOWN = 1,
    KEY_LEFT = 2,
    KEY_RIGHT = 3,
    KEY_CONFIRM = 4,
    KEY_BACK = 5,
    KEY_OPTIONS = 6,
    KEY_SECONDARY = 7,
    KEY_PREV_TAB = 8,
    KEY_NEXT_TAB = 9,
    KEY_START = 10
};

enum SfxKind {
    SFX_MOVE = 0,
    SFX_CONFIRM = 1,
    SFX_CANCEL = 2,
    SFX_TAB = 3,
    SFX_ERROR = 4,
    SFX_BOOT_INTRO = 5,
    SFX_LAUNCH = 6
};

enum TextStyle {
    STYLE_WORDMARK = 0,
    STYLE_ORB_TITLE = 1,
    STYLE_BODY = 2,
    STYLE_BODY_BOLD = 3,
    STYLE_MONO = 4
};

enum ShaderIconKind {
    ICON_GDROM = 0,
    ICON_VMU = 1,
    ICON_SAVE_CRYSTAL = 2,
    ICON_GEAR = 3,
    ICON_CARTRIDGE = 4,
    ICON_ARCADE = 5,
    ICON_DROPLET = 6
};

enum VmuHostAction {
    VMU_ACTION_SELECT_CARD = 0,
    VMU_ACTION_CREATE_DEFAULTS = 1,
    VMU_ACTION_DELETE_FILE = 2,
    VMU_ACTION_FORMAT_CARD = 3
};

class DcRenderer {
public:
    static constexpr int kSettingsCount = 15;

    DcRenderer() = default;
    ~DcRenderer() = default;

    bool Init();
    void Shutdown();
    void Resize(int width, int height);
    void SetSafeInsets(float leftPx, float topPx, float rightPx, float bottomPx);
    bool Draw();

    void OnKey(int key);
    void OnTouchDown(float normX, float normY);
    void OnTouchMove(float normX, float normY);
    void OnTouchUp(float normX, float normY);
    void OnTouchCancel();
    void OnTouchTwoFingerBack();
    void OnTouchLongPress(float normX, float normY);

    void SetStrings(std::unordered_map<std::string, std::string> strings);
    void SetConsoleTabs(std::vector<ConsoleTabInfo> tabs);
    void SetGames(std::vector<GameEntry> games);
    void SetSaves(std::vector<SaveEntry> saves);
    void SetVmuCards(std::vector<VmuCardEntry> cards);
    void SetVmuFiles(int cardIndex, std::vector<VmuFileEntry> files);
    void SetSettings(const ShellSettings& settings);
    void SetSystemStatus(const SystemStatus& status);
    void SetTouchMode(bool active, bool showTutorial);
    void SetLoading(bool gamesLoading, bool savesLoading, bool vmuLoading);
    void TriggerTransition(int kind);

    void NotifyImageDecoded(const std::string& path, int width, int height, std::vector<uint8_t> rgba);

    std::vector<int> GetState() const;
    void SetState(
        int screen,
        int mainIndex,
        int tabIndex,
        int gameIndex,
        int fileTab,
        int vmuCardIndex,
        int saveIndex);

private:
    struct Vertex {
        float x, y;
        float u, v;
        float r, g, b, a;
    };

    struct TextCacheEntry {
        GLuint tex = 0;
        float logW = 0.0f;
        float logH = 0.0f;
        std::list<std::string>::iterator lruIt{};
    };

    struct ImageCacheEntry {
        GLuint tex = 0;
        int w = 0;
        int h = 0;
        bool requested = false;
        bool failed = false;
        float loadedAtSec = -10.0f;
        std::list<std::string>::iterator lruIt{};
    };

    struct PendingDecodedImage {
        std::string path;
        int w = 0;
        int h = 0;
        std::vector<uint8_t> rgba;
    };

    enum HitKind {
        HIT_NONE = 0,
        HIT_HUB_ORB = 1,
        HIT_TOP_EXIT = 2,
        HIT_CONSOLE_TAB = 3,
        HIT_GAME_ROW = 4,
        HIT_GAME_START = 5,
        HIT_GAME_CONTINUE = 6,
        HIT_GAME_OPTIONS = 7,
        HIT_FILE_TAB = 8,
        HIT_SAVE_SLOT = 9,
        HIT_SAVE_ACTION = 10,
        HIT_SAVE_PAGE_PREV = 11,
        HIT_SAVE_PAGE_NEXT = 12,
        HIT_VMU_CARD = 13,
        HIT_VMU_FILE = 14,
        HIT_VMU_ACTION = 15, // 0=create defaults, 1=format, 2=delete file
        HIT_SETTING_ROW = 16,
        HIT_SETTING_LEFT = 17,
        HIT_SETTING_RIGHT = 18,
        HIT_OVERLAY_ITEM = 19,
        HIT_OVERLAY_BACKDROP = 20,
        HIT_BOTTOM_ACTION = 21
    };

    struct HitTarget {
        float x = 0.0f;
        float y = 0.0f;
        float w = 0.0f;
        float h = 0.0f;
        int kind = 0;
        int index = 0;
    };

    struct LayoutMetrics {
        bool portrait = false;
        float padX = 26.0f;
        float padTop = 10.0f;
        float padBot = 10.0f;
        float topBarH = 44.0f;
        float bottomBarH = 38.0f;
        float bodyTop = 62.0f;
        float bodyBot = 492.0f;
        float bodyH = 430.0f;
        float bodyW = 900.0f;
    };

    // GL setup / teardown
    bool CompileShaders();
    GLuint CompileProgram(const char* vsSrc, const char* fsSrc);
    void UpdateViewport();
    void ClearTextCache();
    void ClearImageCache();
    void EvictTextCacheIfNeeded();
    void EvictImageCacheIfNeeded();
    bool FlushPendingDecodedImages();

    // Text & Images
    std::string TextOr(const std::string& key, const char* fallback) const;
    const TextCacheEntry& GetOrCreateText(
        const std::string& text,
        float fontSize,
        int style,
        float maxWidth,
        int maxLines,
        int align,
        int shadowMode);
    const ImageCacheEntry& GetOrCreateImage(const std::string& path, int maxDim = 320);
    float ImageAlpha(const ImageCacheEntry& entry) const;

    void DrawText(
        const std::string& text,
        float x,
        float y,
        float fontSize,
        int style,
        const RgbaColor& color,
        float maxWidth = 0.0f,
        int maxLines = 1,
        int align = 0,
        float boxW = 0.0f,
        int shadowMode = -1);
    void MeasureText(
        const std::string& text,
        float fontSize,
        int style,
        float maxWidth,
        int maxLines,
        float& outW,
        float& outH,
        int shadowMode = -1);

    // Primitives
    void BeginQuadBatch();
    void FlushQuadBatch();
    void PushQuad(
        GLuint tex,
        float x, float y, float w, float h,
        const RgbaColor& cTop,
        const RgbaColor& cBot,
        float u0 = 0.0f, float v0 = 0.0f, float u1 = 1.0f, float v1 = 1.0f);
    void PushQuad(GLuint tex, float x, float y, float w, float h, const RgbaColor& c);
    void PushTriangle(
        float x0, float y0, const RgbaColor& c0,
        float x1, float y1, const RgbaColor& c1,
        float x2, float y2, const RgbaColor& c2);
    void PushLine(float x0, float y0, float x1, float y1, float thickness, const RgbaColor& c);
    void PushCircleOutline(float cx, float cy, float radius, float thickness, int segs, const RgbaColor& c);

    void DrawPanel(
        float x, float y, float w, float h,
        float radius,
        float borderPx,
        const RgbaColor& topCol,
        const RgbaColor& botCol,
        const RgbaColor& borderCol,
        const RgbaColor& glowCol = {0.0f, 0.0f, 0.0f, 0.0f},
        float shimmer = -1.0f);
    void DrawSwirl(float cx, float cy, float radius, float progress, float rotRad, float alpha, const RgbaColor& col);
    void DrawShaderIcon(int kind, float cx, float cy, float radius, float angleRad, float alpha, const RgbaColor& accent);
    void DrawImageAspectFit(
        const ImageCacheEntry& img,
        float x, float y, float w, float h,
        float alpha,
        bool fillCrop = false);
    void DrawScanlinesPass();

    // Screens & Overlays (dc_screens.cpp)
    LayoutMetrics ComputeMetrics() const;
    void DrawBootIntro(float alpha);
    void DrawTopBar(const LayoutMetrics& m, const std::string& title, bool showExitBtn, float alpha);
    void DrawBottomBar(const LayoutMetrics& m, float alpha);
    void DrawAnalogClock(float cx, float cy, float radius, float alpha);
    void DrawMainMenu(const LayoutMetrics& m, float alpha);
    void DrawGamesScreen(const LayoutMetrics& m, float alpha);
    void DrawGameShowcase(
        const GameEntry& game,
        int absGameIdx,
        const DisplayTab& tab,
        float x, float y, float w, float h,
        bool compactHorizontal,
        float alpha);
    void DrawFileScreen(const LayoutMetrics& m, float alpha);
    void DrawQuickSavesTab(const LayoutMetrics& m, float topY, float botY, float alpha);
    void DrawVmuCardsTab(const LayoutMetrics& m, float topY, float botY, float alpha);
    void DrawSettingsScreen(const LayoutMetrics& m, float alpha);
    void DrawLaunchStage();
    void DrawOverlay(const LayoutMetrics& m, float alpha = 1.0f);
    void DrawConfirmDialog(
        const LayoutMetrics& m,
        const std::string& title,
        const std::string& message,
        const std::string& yesText,
        const std::string& noText,
        int focusedBtn,
        float alpha = 1.0f);

    // State & Navigation Helpers
    void RegisterHit(float x, float y, float w, float h, int kind, int index = 0);
    const HitTarget* FindHit(float vx, float vy) const;
    bool SurfaceNormToVirtual(float normX, float normY, float& outVx, float& outVy) const;
    void RebuildDisplayTabs(bool forceSelectRecentIfPresent);
    void SwitchScreen(int nextScreen);
    int ActiveTabGameCount() const;
    int ActiveTabAbsGameIndex(int rowIdx) const;
    int ShaderIconForPlatform(const std::string& platformId) const;
    RgbaColor ColorForPlatform(const std::string& platformId) const;
    void ActivateGameLaunch(int absGameIdx);
    void StartLaunchGame(const std::string& path, const std::string& title, const std::string& serial, const std::string& coverPath, const std::string& platformId);
    void StartLaunchQuickSave(const std::string& path, int slot, const std::string& title, const std::string& serial, const std::string& previewPath, const std::string& platformId);
    void StartLaunchSave(int filteredIndex);
    void EnsureGameVisible(int count, float listH, float rowStep);
    void EnsureTabVisible(float stripW);
    void EnsureSettingVisible(float viewH);
    void CycleSetting(int row, int delta);
    void PushSettingsToHost();
    std::vector<int> FilteredSaveIndices() const;
    void ClampSelectionIndices();
    void UpdateCachedClockStrings();
    std::string FormatDateFromTm(const std::tm& tmv) const;
    std::string FormatClockFromTm(const std::tm& tmv) const;

    // Theme Palette Helpers
    bool IsLightTheme() const;
    RgbaColor SwirlAccentColor(float alpha = 1.0f) const;
    RgbaColor SecondaryAccentColor(float alpha = 1.0f) const;
    RgbaColor PanelTopColor(float alpha = 0.88f) const;
    RgbaColor PanelBotColor(float alpha = 0.92f) const;
    RgbaColor PanelBorderColor(float alpha = 0.85f) const;
    RgbaColor SelectedBorderColor(float alpha = 1.0f) const;
    RgbaColor PrimaryTextColor(float alpha = 1.0f) const;
    RgbaColor SecondaryTextColor(float alpha = 0.82f) const;
    int DefaultShadowMode() const;

    // GL Resources
    bool gl_ready_ = false;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLuint fs_vao_ = 0;
    GLuint fs_vbo_ = 0;
    GLuint white_tex_ = 0;

    GLuint prog_quad_ = 0;
    GLint quad_u_screen_ = -1;
    GLint quad_u_texture_ = -1;

    GLuint prog_bg_ = 0;
    GLint bg_u_screen_ = -1;
    GLint bg_u_theme_ = -1;
    GLint bg_u_time_ = -1;
    GLint bg_u_spheres_ = -1;

    GLuint prog_swirl_ = 0;
    GLint swirl_u_screen_ = -1;
    GLint swirl_u_rect_ = -1;
    GLint swirl_u_progress_ = -1;
    GLint swirl_u_rot_ = -1;
    GLint swirl_u_alpha_ = -1;
    GLint swirl_u_color_ = -1;

    GLuint prog_panel_ = 0;
    GLint pan_u_screen_ = -1;
    GLint pan_u_rect_ = -1;
    GLint pan_u_size_ = -1;
    GLint pan_u_radius_ = -1;
    GLint pan_u_border_px_ = -1;
    GLint pan_u_top_col_ = -1;
    GLint pan_u_bot_col_ = -1;
    GLint pan_u_border_col_ = -1;
    GLint pan_u_glow_col_ = -1;
    GLint pan_u_shimmer_ = -1;

    GLuint prog_icon_ = 0;
    GLint icon_u_screen_ = -1;
    GLint icon_u_rect_ = -1;
    GLint icon_u_kind_ = -1;
    GLint icon_u_angle_ = -1;
    GLint icon_u_alpha_ = -1;
    GLint icon_u_accent_ = -1;
    GLint icon_u_light_ui_ = -1;

    GLuint prog_scan_ = 0;
    GLint scan_u_viewport_ = -1;
    GLint scan_u_strength_ = -1;

    std::vector<Vertex> batch_verts_;
    GLuint batch_tex_ = 0;

    // Viewport & Scaling
    int surface_width_ = 1280;
    int surface_height_ = 720;
    float screen_width_ = 960.0f;
    float screen_height_ = 540.0f;
    int view_x_ = 0;
    int view_y_ = 0;
    int view_width_ = 1280;
    int view_height_ = 720;
    float inset_left_px_ = 0.0f;
    float inset_top_px_ = 0.0f;
    float inset_right_px_ = 0.0f;
    float inset_bottom_px_ = 0.0f;
    float safe_left_ = 0.0f;
    float safe_top_ = 0.0f;
    float safe_right_ = 0.0f;
    float safe_bottom_ = 0.0f;
    float raster_scale_ = 2.0f;
    float last_raster_scale_ = 0.0f;

    // Timing & Animations
    std::chrono::steady_clock::time_point last_frame_tp_;
    bool has_frame_tp_ = false;
    float time_sec_ = 0.0f;
    int prev_screen_ = -1;
    float screen_fade_t_ = 1.0f;
    int last_overlay_ = OVERLAY_NONE;
    float overlay_fade_t_ = 1.0f;
    bool exiting_shell_ = false;
    float exit_t_ = 0.0f;
    float spin_angle_ = 0.0f;
    float hub_visual_pos_ = 0.0f;
    int static_idle_frames_ = 0;

    bool boot_active_ = false;
    float boot_t_ = 0.0f;
    float boot_duration_ = 2.50f;
    bool boot_sfx_played_ = false;

    bool launching_ = false;
    bool launch_is_save_ = false;
    bool launch_dispatched_ = false;
    float launch_t_ = 0.0f;
    std::string launch_title_;
    std::string launch_serial_;
    std::string launch_cover_;
    std::string launch_path_;
    std::string launch_platform_id_;
    int launch_save_slot_ = 0;

    // Navigation State
    int screen_ = SCREEN_MAIN_MENU;
    int return_screen_ = SCREEN_MAIN_MENU;
    int overlay_ = OVERLAY_NONE;
    int main_index_ = 0;

    int tab_index_ = 0;
    float tab_scroll_x_ = 0.0f;
    float tab_scroll_target_x_ = 0.0f;
    std::vector<std::pair<float, float>> tab_layout_bounds_; // cached x,w per tab for EnsureTabVisible

    int game_index_ = 0;
    float game_scroll_y_ = 0.0f;
    float game_scroll_target_y_ = 0.0f;

    int file_tab_ = 0;        // 0=All Quick Saves, 1=Game Saves, 2=VMU Cards
    int save_page_ = 0;
    int save_slot_index_ = 0; // index within page
    int file_focus_pane_ = 0; // 0=Grid/List, 1=Action Buttons
    int file_action_idx_ = 0; // 0=Load, 1=Delete, 2=Exit (on saves), or VMU actions
    int vmu_card_index_ = 0;
    int vmu_file_index_ = 0;
    float vmu_files_scroll_y_ = 0.0f;
    std::string file_filter_game_path_;
    std::string file_filter_game_title_;

    int settings_index_ = 0;
    float settings_scroll_y_ = 0.0f;
    float settings_scroll_target_y_ = 0.0f;

    int dialog_btn_ = 1; // safe default = NO
    int options_index_ = 0;
    int disc_picker_index_ = 0;
    int pending_delete_save_idx_ = -1;
    int pending_vmu_card_idx_ = -1;
    int pending_vmu_file_idx_ = -1;

    // Touch Tracking
    bool touch_mode_active_ = false;
    bool touch_dragging_ = false;
    bool touch_moved_ = false;
    float touch_down_vx_ = 0.0f;
    float touch_down_vy_ = 0.0f;
    float touch_last_vx_ = 0.0f;
    float touch_last_vy_ = 0.0f;
    int touch_drag_target_ = 0; // 1=games list, 2=settings list, 3=tabs, 4=hub swipe, 5=vmu files
    std::vector<HitTarget> hit_targets_;

    // Host Data
    std::unordered_map<std::string, std::string> strings_;
    std::vector<ConsoleTabInfo> console_tabs_;
    std::vector<GameEntry> games_;
    std::vector<DisplayTab> display_tabs_;
    bool has_recent_tab_ = false;

    std::vector<SaveEntry> saves_;
    std::vector<VmuCardEntry> vmu_cards_;
    int loaded_vmu_card_index_ = -1;
    std::vector<VmuFileEntry> vmu_files_;

    ShellSettings settings_;
    SystemStatus status_;
    bool games_loading_ = false;
    bool saves_loading_ = false;
    bool vmu_loading_ = false;

    // Dirty & Async Image Queue
    std::unordered_set<std::string> dirty_image_paths_;
    std::mutex decoded_mutex_;
    std::deque<PendingDecodedImage> pending_decoded_;

    // Cached 1-Hz clock strings & analog hand angles
    std::time_t cached_clock_sec_ = 0;
    bool cached_clock_24h_ = true;
    int cached_clock_date_fmt_ = -1;
    std::string cached_time_str_;
    std::string cached_date_str_;
    int clock_hour_ = 12;
    int clock_min_ = 0;
    int clock_sec_ = 0;

    // LRU Texture Caches (GL thread only)
    std::unordered_map<std::string, TextCacheEntry> text_cache_;
    std::list<std::string> text_lru_;
    std::unordered_map<std::string, ImageCacheEntry> image_cache_;
    std::list<std::string> image_lru_;
};

} // namespace dcui
