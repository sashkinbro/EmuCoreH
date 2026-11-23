#include "dc_renderer.h"
#include "dc_utils.h"

#include <algorithm>
#include <cmath>
#include <cstdio>

namespace dcui {

namespace {

struct ScopedScissor {
    bool active = false;
    ScopedScissor(
        int viewX, int viewY, int viewW, int viewH,
        float screenW, float screenH,
        float x, float y, float w, float h) {
        if (w <= 1.0f || h <= 1.0f || viewW <= 0 || viewH <= 0) return;
        const float sx = static_cast<float>(viewW) / screenW;
        const float sy = static_cast<float>(viewH) / screenH;
        const int gx = viewX + static_cast<int>(std::floor(x * sx));
        const int gy = viewY + static_cast<int>(std::floor((screenH - (y + h)) * sy));
        const int gw = std::max(1, static_cast<int>(std::ceil(w * sx)));
        const int gh = std::max(1, static_cast<int>(std::ceil(h * sy)));
        glEnable(GL_SCISSOR_TEST);
        glScissor(gx, gy, gw, gh);
        active = true;
    }
    ~ScopedScissor() {
        if (active) {
            glDisable(GL_SCISSOR_TEST);
        }
    }
};

} // namespace

DcRenderer::LayoutMetrics DcRenderer::ComputeMetrics() const {
    LayoutMetrics m;
    m.portrait = (screen_height_ > screen_width_);
    const float basePadX = m.portrait ? 18.0f : 26.0f;
    m.padX = basePadX + std::max(safe_left_, safe_right_);
    m.padTop = 8.0f + safe_top_;
    m.padBot = 8.0f + safe_bottom_;
    m.topBarH = m.portrait ? 44.0f : 40.0f;
    m.bottomBarH = 36.0f;
    m.bodyTop = m.padTop + m.topBarH + 8.0f;
    m.bodyBot = screen_height_ - m.padBot - m.bottomBarH - 8.0f;
    m.bodyH = std::max(200.0f, m.bodyBot - m.bodyTop);
    m.bodyW = std::max(280.0f, screen_width_ - m.padX * 2.0f);
    return m;
}

void DcRenderer::DrawBootIntro(float alpha) {
    // 2-Stage Authentic Dreamcast BIOS Startup:
    //   Stage 1 (0.00..0.48): Glowing water droplet bounces 4 times across luminous canvas,
    //                         spawning translucent concentric ripples before rising to center.
    //   Stage 2 (0.42..1.00): Logarithmic Dreamcast Spiral Swirl unspools cleanly above the
    //                         EmuCoreH wordmark, then dissolves smoothly into the 4-Orb Hub.
    const float t = Clamp01(boot_t_);
    if (alpha <= 0.01f) return;

    const bool light = IsLightTheme();
    const float cx = screen_width_ * 0.5f;
    const float baselineY = screen_height_ * 0.53f;
    const float swirlCy = screen_height_ * 0.41f;
    const RgbaColor accent = SwirlAccentColor(1.0f);

    const float envIn = SmoothStep(0.0f, 0.08f, t);
    const float envOut = (t > 0.85f) ? SmoothStep(0.85f, 1.0f, t) : 0.0f;
    const float stageAlpha = envIn * (1.0f - envOut) * alpha;
    if (stageAlpha <= 0.005f) return;

    // Clean luminous BIOS boot veil
    if (light) {
        PushQuad(white_tex_, 0.0f, 0.0f, screen_width_, screen_height_,
                 {0.975f, 0.985f, 1.0f, stageAlpha * 0.94f},
                 {0.89f, 0.93f, 0.985f, stageAlpha * 0.94f});
    } else {
        PushQuad(white_tex_, 0.0f, 0.0f, screen_width_, screen_height_,
                 {0.025f, 0.038f, 0.068f, stageAlpha * 0.96f},
                 {0.055f, 0.082f, 0.145f, stageAlpha * 0.96f});
    }

    // Stage 1: 4 bouncing droplet arcs & water ripples
    if (t < 0.54f) {
        const float dropPhase = Clamp01(t / 0.46f);
        const float bounceT[5] = {0.0f, 0.27f, 0.52f, 0.74f, 0.93f};
        const float bounceH[4] = {120.0f, 82.0f, 54.0f, 34.0f};
        const float startX = cx - std::min(220.0f, screen_width_ * 0.30f);

        // Draw expanding ripples at each bounce point after impact
        for (int b = 1; b <= 4; ++b) {
            if (dropPhase >= bounceT[b]) {
                const float age = Clamp01((dropPhase - bounceT[b]) / 0.28f);
                if (age < 1.0f) {
                    const float bx = Lerp(startX, cx, bounceT[b] / 0.93f);
                    const float rx = (12.0f + age * 46.0f);
                    const float rAlpha = (1.0f - age) * 0.52f * stageAlpha;
                    RgbaColor ripCol = accent;
                    ripCol.a = rAlpha;
                    PushCircleOutline(bx, baselineY + 4.0f, rx, 2.0f * (1.0f - age * 0.5f), 28, ripCol);
                }
            }
        }

        // Compute droplet (x, y)
        float dx = cx;
        float dy = swirlCy;
        float dropFade = 1.0f;
        if (dropPhase < bounceT[4]) {
            int seg = 0;
            for (int i = 0; i < 4; ++i) {
                if (dropPhase >= bounceT[i] && dropPhase <= bounceT[i + 1]) {
                    seg = i;
                    break;
                }
            }
            const float u = Clamp01((dropPhase - bounceT[seg]) / std::max(0.01f, bounceT[seg + 1] - bounceT[seg]));
            const float x0 = Lerp(startX, cx, bounceT[seg] / 0.93f);
            const float x1 = Lerp(startX, cx, bounceT[seg + 1] / 0.93f);
            dx = Lerp(x0, x1, u);
            const float arc = 4.0f * u * (1.0f - u);
            dy = baselineY - arc * bounceH[seg];
        } else {
            const float u = EaseOutCubic(Clamp01((dropPhase - bounceT[4]) / 0.07f));
            dx = cx;
            dy = Lerp(baselineY, swirlCy, u);
            dropFade = 1.0f - SmoothStep(0.45f, 0.53f, t);
        }

        if (dropFade > 0.01f) {
            DrawShaderIcon(ICON_DROPLET, dx, dy, 12.5f, t * 4.0f, dropFade * stageAlpha, accent);
        }
    }

    // Stage 2: Unspooling Dreamcast Spiral Swirl + clean EmuCoreH wordmark
    if (t >= 0.42f) {
        const float swirlP = EaseOutCubic(Clamp01((t - 0.42f) / 0.34f));
        const float swirlR = std::min(screen_width_, screen_height_) * 0.145f;
        const float rot = (1.0f - swirlP) * 0.65f + (t - 0.42f) * 0.35f;
        DrawSwirl(cx, swirlCy, swirlR, swirlP, rot, stageAlpha, accent);

        const float textP = EaseOutCubic(Clamp01((t - 0.56f) / 0.22f));
        if (textP > 0.01f) {
            const float textA = textP * stageAlpha;
            const float slideY = (1.0f - textP) * 10.0f;
            const std::string brand = "EmuCoreH";
            const float fontSz = (screen_width_ < 600.0f) ? 34.0f : 40.0f;
            float bw = 0.0f, bh = 0.0f;
            MeasureText(brand, fontSz, STYLE_WORDMARK, 500.0f, 1, bw, bh, light ? 0 : 1);

            const RgbaColor wordCol = light
                ? RgbaColor{0.10f, 0.17f, 0.30f, textA}
                : RgbaColor{0.96f, 0.98f, 1.00f, textA};
            const float textY = swirlCy + swirlR + 18.0f + slideY;
            DrawText(brand, cx - bw * 0.5f, textY, fontSz, STYLE_WORDMARK, wordCol, 500.0f, 1, 0, 0.0f, light ? 0 : 1);

            // Clean Dreamcast accent hairline under wordmark
            const float lineW = std::max(140.0f, bw + 34.0f) * textP;
            RgbaColor cL = accent; cL.a = textA * 0.92f;
            RgbaColor cR = SecondaryAccentColor(textA * 0.92f);
            PushQuad(white_tex_, cx - lineW * 0.5f, textY + bh + 10.0f, lineW, 2.2f, cL, cR);
        }
    }
}

void DcRenderer::DrawTopBar(const LayoutMetrics& m, const std::string& title, bool showExitBtn, float alpha) {
    const float y = m.padTop;
    const float h = m.topBarH;
    const float swirlR = 14.0f;
    const float swirlCx = m.padX + swirlR + 2.0f;
    const float swirlCy = y + h * 0.5f;

    DrawSwirl(swirlCx, swirlCy, swirlR, 1.0f, spin_angle_ * 0.35f, alpha, SwirlAccentColor(1.0f));

    float titleX = swirlCx + swirlR + 12.0f;
    const bool showBackOrExitPill = (touch_mode_active_ && settings_.touchControls) || showExitBtn;
    float leftUsedEnd = titleX;

    float tw = 0.0f, th = 0.0f;
    const float maxTitleW = m.portrait ? (m.bodyW * 0.42f) : (m.bodyW * 0.38f);
    MeasureText(title, m.portrait ? 20.0f : 22.0f, STYLE_WORDMARK, maxTitleW, 1, tw, th);
    DrawText(title, titleX, y + (h - th) * 0.5f, m.portrait ? 20.0f : 22.0f, STYLE_WORDMARK,
             PrimaryTextColor(alpha), maxTitleW, 1, 0);
    leftUsedEnd = titleX + tw + 12.0f;

    if (showBackOrExitPill && overlay_ == OVERLAY_NONE) {
        const std::string pillText = showExitBtn
            ? TextOr("dcui_btn_exit", "Exit")
            : TextOr("dcui_hint_back", "Back");
        float pw = 0.0f, ph = 0.0f;
        MeasureText(pillText, 13.5f, STYLE_BODY_BOLD, 100.0f, 1, pw, ph);
        const float btnW = std::max(60.0f, pw + 24.0f);
        const float btnH = 28.0f;
        const float btnX = leftUsedEnd + 2.0f;
        const float btnY = y + (h - btnH) * 0.5f;
        if (btnX + btnW < m.padX + m.bodyW - 150.0f) {
            DrawPanel(
                btnX, btnY, btnW, btnH, 14.0f, 1.1f,
                PanelTopColor(0.84f * alpha),
                PanelBotColor(0.90f * alpha),
                PanelBorderColor(0.75f * alpha));
            DrawText(pillText, btnX, btnY + (btnH - ph) * 0.5f, 13.5f, STYLE_BODY_BOLD,
                     PrimaryTextColor(alpha), btnW - 10.0f, 1, 1, btnW);
            RegisterHit(btnX, btnY, btnW, btnH, showExitBtn ? HIT_TOP_EXIT : HIT_BOTTOM_ACTION,
                        showExitBtn ? 0 : KEY_BACK);
        }
    }

    // Right System Status Capsule (BIOS badge, WiFi/BT/Mute, Battery)
    char batBuf[24];
    std::snprintf(batBuf, sizeof(batBuf), "%d%%%s", status_.batteryLevel, status_.batteryCharging ? "+" : "");
    char biosBuf[32];
    std::snprintf(biosBuf, sizeof(biosBuf), "BIOS %d/%d", status_.biosReadyCount, std::max(1, status_.biosTotalCount));

    std::string statusLine;
    if (!m.portrait) {
        if (status_.wifiConnected) statusLine += "WiFi  ";
        if (status_.bluetoothConnected) statusLine += "BT  ";
        if (status_.audioMuted) statusLine += "MUTE  ";
        statusLine += std::string(biosBuf) + "  |  " + batBuf;
    } else {
        statusLine = std::string(biosBuf) + "  |  " + batBuf;
    }

    float sw = 0.0f, sh = 0.0f;
    MeasureText(statusLine, 13.5f, STYLE_MONO, m.bodyW * 0.52f, 1, sw, sh);
    const float capW = sw + 28.0f;
    const float capH = 30.0f;
    const float capX = m.padX + m.bodyW - capW;
    const float capY = y + (h - capH) * 0.5f;
    const RgbaColor biosDotCol = (status_.biosReadyCount > 0)
        ? RgbaColor{0.16f, 0.78f, 0.45f, alpha}
        : RgbaColor{0.94f, 0.62f, 0.16f, alpha};

    DrawPanel(
        capX, capY, capW, capH, 15.0f, 1.1f,
        PanelTopColor(0.82f * alpha),
        PanelBotColor(0.90f * alpha),
        PanelBorderColor(0.75f * alpha));
    // Status dot on left of capsule
    DrawPanel(capX + 10.0f, capY + (capH - 7.0f) * 0.5f, 7.0f, 7.0f, 3.5f, 0.0f,
              biosDotCol, biosDotCol, biosDotCol);
    DrawText(statusLine, capX + 21.0f, capY + (capH - sh) * 0.5f, 13.5f, STYLE_MONO,
             PrimaryTextColor(alpha), capW - 26.0f, 1, 0);
}

void DcRenderer::DrawBottomBar(const LayoutMetrics& m, float alpha) {
    struct HintBtn {
        std::string badge;
        std::string label;
        int key;
        RgbaColor badgeCol;
    };
    std::vector<HintBtn> btns;
    const std::string confirmGlyph = settings_.swapConfirm ? "B" : "A";
    const std::string backGlyph = settings_.swapConfirm ? "A" : "B";
    const RgbaColor cOk = SwirlAccentColor(1.0f);
    const RgbaColor cBack = SecondaryAccentColor(1.0f);
    const RgbaColor cOpt = {0.22f, 0.72f, 0.48f, 1.0f};
    const RgbaColor cTab = {0.52f, 0.56f, 0.88f, 1.0f};

    if (overlay_ == OVERLAY_EXIT_CONFIRM ||
        overlay_ == OVERLAY_DELETE_SAVE_CONFIRM ||
        overlay_ == OVERLAY_DELETE_VMU_FILE_CONFIRM ||
        overlay_ == OVERLAY_FORMAT_VMU_CONFIRM ||
        overlay_ == OVERLAY_RESTORE_CONFIRM) {
        btns.push_back({confirmGlyph, TextOr("dcui_hint_select", "Select"), KEY_CONFIRM, cOk});
        btns.push_back({backGlyph, TextOr("dcui_hint_back", "Back"), KEY_BACK, cBack});
    } else if (overlay_ != OVERLAY_NONE) {
        btns.push_back({confirmGlyph, TextOr("dcui_hint_select", "Select"), KEY_CONFIRM, cOk});
        btns.push_back({backGlyph, TextOr("dcui_close", "Close"), KEY_BACK, cBack});
    } else if (screen_ == SCREEN_MAIN_MENU) {
        btns.push_back({confirmGlyph, TextOr("dcui_hint_select", "Select"), KEY_CONFIRM, cOk});
        btns.push_back({backGlyph, TextOr("dcui_hint_exit", "Exit"), KEY_BACK, cBack});
    } else if (screen_ == SCREEN_GAMES) {
        btns.push_back({confirmGlyph, TextOr("dcui_btn_start", "Start"), KEY_CONFIRM, cOk});
        if (!m.portrait) {
            btns.push_back({"Y", TextOr("dcui_btn_options", "Options"), KEY_OPTIONS, cOpt});
        }
        btns.push_back({"L/R", TextOr("dcui_hint_tab", "Tab"), KEY_NEXT_TAB, cTab});
        btns.push_back({backGlyph, TextOr("dcui_hint_back", "Back"), KEY_BACK, cBack});
    } else if (screen_ == SCREEN_FILE) {
        btns.push_back({confirmGlyph, TextOr("dcui_hint_select", "Select"), KEY_CONFIRM, cOk});
        btns.push_back({"L/R", TextOr("dcui_hint_tab", "Tab"), KEY_NEXT_TAB, cTab});
        btns.push_back({backGlyph, TextOr("dcui_hint_back", "Back"), KEY_BACK, cBack});
    } else if (screen_ == SCREEN_SETTINGS) {
        btns.push_back({"</>", TextOr("dcui_hint_change", "Change"), KEY_RIGHT, cOk});
        btns.push_back({backGlyph, TextOr("dcui_hint_back", "Back"), KEY_BACK, cBack});
    }

    const float barY = screen_height_ - m.padBot - m.bottomBarH;
    const float barH = m.bottomBarH;
    std::vector<float> widths;
    widths.reserve(btns.size());
    float totalW = 0.0f;
    for (const auto& b : btns) {
        float bw = 0.0f, bh = 0.0f, lw = 0.0f, lh = 0.0f;
        MeasureText(b.badge, 13.5f, STYLE_MONO, 70.0f, 1, bw, bh);
        MeasureText(b.label, 14.0f, STYLE_BODY_BOLD, 140.0f, 1, lw, lh);
        const float w = bw + lw + 32.0f;
        widths.push_back(w);
        totalW += w;
    }
    if (!btns.empty()) totalW += 10.0f * static_cast<float>(btns.size() - 1);

    float curX = std::max(m.padX, (screen_width_ - totalW) * 0.5f);
    for (size_t i = 0; i < btns.size(); ++i) {
        const float w = widths[i];
        if (curX + w > m.padX + m.bodyW + 4.0f) break;
        DrawPanel(
            curX, barY, w, barH, 10.0f, 1.1f,
            PanelTopColor(0.84f * alpha),
            PanelBotColor(0.92f * alpha),
            PanelBorderColor(0.70f * alpha));
        float bw = 0.0f, bh = 0.0f, lw = 0.0f, lh = 0.0f;
        MeasureText(btns[i].badge, 13.5f, STYLE_MONO, 70.0f, 1, bw, bh);
        MeasureText(btns[i].label, 14.0f, STYLE_BODY_BOLD, 140.0f, 1, lw, lh);
        RgbaColor bCol = btns[i].badgeCol;
        bCol.a *= alpha;
        DrawText(btns[i].badge, curX + 11.0f, barY + (barH - bh) * 0.5f, 13.5f, STYLE_MONO, bCol, 70.0f, 1, 0);
        DrawText(btns[i].label, curX + 17.0f + bw, barY + (barH - lh) * 0.5f, 14.0f, STYLE_BODY_BOLD,
                 PrimaryTextColor(alpha), 140.0f, 1, 0);

        if (overlay_ == OVERLAY_NONE) {
            RegisterHit(curX, barY, w, barH, HIT_BOTTOM_ACTION, btns[i].key);
        }
        curX += w + 10.0f;
    }
}

void DcRenderer::DrawAnalogClock(float cx, float cy, float radius, float alpha) {
    // Classic Dreamcast BIOS analog clock dial with luminous center jewel
    DrawPanel(
        cx - radius, cy - radius, radius * 2.0f, radius * 2.0f, radius, 1.4f,
        PanelTopColor(0.92f * alpha),
        PanelBotColor(0.95f * alpha),
        PanelBorderColor(0.85f * alpha));

    const RgbaColor tickCol = SecondaryTextColor(0.65f * alpha);
    const RgbaColor majorCol = PrimaryTextColor(0.85f * alpha);
    for (int i = 0; i < 12; ++i) {
        const float a = (static_cast<float>(i) / 12.0f) * kTwoPi - kPi * 0.5f;
        const bool major = (i % 3 == 0);
        const float rIn = radius * (major ? 0.68f : 0.76f);
        const float rOut = radius * 0.88f;
        PushLine(
            cx + std::cos(a) * rIn, cy + std::sin(a) * rIn,
            cx + std::cos(a) * rOut, cy + std::sin(a) * rOut,
            major ? 1.8f : 1.1f, major ? majorCol : tickCol);
    }

    const float secAngle = (static_cast<float>(clock_sec_) / 60.0f) * kTwoPi - kPi * 0.5f;
    const float minAngle = ((static_cast<float>(clock_min_) + clock_sec_ / 60.0f) / 60.0f) * kTwoPi - kPi * 0.5f;
    const float hrAngle = ((static_cast<float>(clock_hour_ % 12) + clock_min_ / 60.0f) / 12.0f) * kTwoPi - kPi * 0.5f;

    const RgbaColor handCol = PrimaryTextColor(0.94f * alpha);
    const RgbaColor secCol = SwirlAccentColor(alpha);
    PushLine(cx, cy, cx + std::cos(hrAngle) * radius * 0.48f, cy + std::sin(hrAngle) * radius * 0.48f, 2.4f, handCol);
    PushLine(cx, cy, cx + std::cos(minAngle) * radius * 0.72f, cy + std::sin(minAngle) * radius * 0.72f, 1.7f, handCol);
    PushLine(cx, cy, cx + std::cos(secAngle) * radius * 0.80f, cy + std::sin(secAngle) * radius * 0.80f, 1.1f, secCol);

    DrawPanel(cx - 2.6f, cy - 2.6f, 5.2f, 5.2f, 2.6f, 0.0f, secCol, secCol, secCol);
}

void DcRenderer::DrawMainMenu(const LayoutMetrics& m, float alpha) {
    DrawTopBar(m, "EmuCoreH", true, alpha);

    struct HubItem {
        std::string title;
        std::string subtitle;
        std::string badge;
        int iconKind;
    };

    char gamesBadge[24] = {};
    char gamesSub[96] = {};
    if (!games_.empty()) {
        std::snprintf(gamesBadge, sizeof(gamesBadge), "%zu", games_.size());
        const size_t sysCount = display_tabs_.size() - (has_recent_tab_ ? 1 : 0);
        std::snprintf(gamesSub, sizeof(gamesSub), "%zu %s · %zu %s",
                      games_.size(), TextOr("dcui_games_word", "Games").c_str(),
                      sysCount, TextOr("dcui_systems_word", "Systems").c_str());
    } else {
        std::snprintf(gamesSub, sizeof(gamesSub), "%s", TextOr("dcui_no_games", "No games found").c_str());
    }

    char vmuBadge[24] = {};
    char vmuSub[96] = {};
    if (!vmu_cards_.empty()) {
        std::snprintf(vmuBadge, sizeof(vmuBadge), "%zu", vmu_cards_.size());
        std::snprintf(vmuSub, sizeof(vmuSub), "%zu %s",
                      vmu_cards_.size(), TextOr("dcui_vmu_cards_word", "VMU Cards").c_str());
    } else {
        std::snprintf(vmuSub, sizeof(vmuSub), "%s", TextOr("dcui_vmu_subtitle_empty", "Create & format VMU memory").c_str());
    }

    char savesBadge[24] = {};
    char savesSub[96] = {};
    if (!saves_.empty()) {
        std::snprintf(savesBadge, sizeof(savesBadge), "%zu", saves_.size());
        std::snprintf(savesSub, sizeof(savesSub), "%zu %s",
                      saves_.size(), TextOr("dcui_saves_word", "Saves").c_str());
    } else {
        std::snprintf(savesSub, sizeof(savesSub), "%s", TextOr("dcui_mc_no_saves", "No quick saves yet").c_str());
    }

    const HubItem items[4] = {
        {TextOr("dcui_menu_play", "Play"), gamesSub, gamesBadge, ICON_GDROM},
        {TextOr("dcui_menu_file", "File"), vmuSub, vmuBadge, ICON_VMU},
        {TextOr("dcui_menu_saves", "Quick Saves"), savesSub, savesBadge, ICON_SAVE_CRYSTAL},
        {TextOr("dcui_menu_settings", "Settings"),
         TextOr("dcui_menu_settings_sub", "Theme, Screen, Sound & BIOS"), "", ICON_GEAR},
    };

    // 4-Orb Orbital Ring geometry
    const float hubCx = screen_width_ * 0.5f;
    const float hubCy = m.portrait ? (m.bodyTop + m.bodyH * 0.25f) : (m.bodyTop + m.bodyH * 0.29f);
    const float rx = m.portrait ? std::min(m.bodyW * 0.34f, 170.0f) : std::min(m.bodyW * 0.27f, 235.0f);
    const float ry = m.portrait ? std::min(m.bodyH * 0.085f, 52.0f) : std::min(m.bodyH * 0.115f, 50.0f);
    const float baseOrbR = m.portrait ? 48.0f : 52.0f;

    struct OrbDraw {
        int idx;
        float px, py, r, depth;
    };
    OrbDraw order[4];
    for (int i = 0; i < 4; ++i) {
        const float rel = static_cast<float>(i) - hub_visual_pos_;
        const float ang = rel * (kPi * 0.5f);
        const float px = hubCx + std::sin(ang) * rx;
        const float py = hubCy + std::cos(ang) * ry;
        const float depth = (std::cos(ang) + 1.0f) * 0.5f;
        const float orbR = baseOrbR * (0.62f + 0.44f * depth);
        order[i] = {i, px, py, orbR, depth};
    }
    std::sort(order, order + 4, [](const OrbDraw& a, const OrbDraw& b) {
        return a.depth < b.depth;
    });

    // Subtle orbital ring guide ellipse
    const RgbaColor ringCol = PanelBorderColor(0.28f * alpha);
    for (int s = 0; s < 36; ++s) {
        const float a0 = (static_cast<float>(s) / 36.0f) * kTwoPi;
        const float a1 = (static_cast<float>(s + 1) / 36.0f) * kTwoPi;
        PushLine(
            hubCx + std::sin(a0) * rx, hubCy + std::cos(a0) * ry,
            hubCx + std::sin(a1) * rx, hubCy + std::cos(a1) * ry,
            1.4f, ringCol);
    }

    const float shimmerPhase = (settings_.motionMode == 3) ? -1.0f : std::fmod(time_sec_ * 0.45f, 1.8f);
    for (int k = 0; k < 4; ++k) {
        const OrbDraw& o = order[k];
        const bool selected = (o.idx == main_index_);
        const float orbAlpha = alpha * (0.68f + 0.32f * o.depth);
        const RgbaColor accent = SwirlAccentColor(1.0f);

        if (selected) {
            // Soft radial Dreamcast halo ring behind active orb
            const float haloR = o.r + 7.0f;
            RgbaColor hCol = accent;
            hCol.a = 0.28f * orbAlpha;
            DrawPanel(o.px - haloR, o.py - haloR, haloR * 2.0f, haloR * 2.0f, haloR, 2.2f,
                      {accent.r, accent.g, accent.b, 0.08f * orbAlpha},
                      {accent.r, accent.g, accent.b, 0.02f * orbAlpha},
                      hCol);
        }

        DrawPanel(
            o.px - o.r, o.py - o.r, o.r * 2.0f, o.r * 2.0f, o.r, selected ? 2.2f : 1.2f,
            PanelTopColor((selected ? 0.94f : 0.78f) * orbAlpha),
            PanelBotColor((selected ? 0.96f : 0.86f) * orbAlpha),
            selected ? SelectedBorderColor(0.95f * orbAlpha) : PanelBorderColor(0.70f * orbAlpha),
            selected ? RgbaColor{accent.r, accent.g, accent.b, 0.16f} : RgbaColor{0, 0, 0, 0},
            selected ? shimmerPhase : -1.0f);

        const float iconAngle = selected ? spin_angle_ : (spin_angle_ * 0.25f + o.idx * 1.1f);
        DrawShaderIcon(items[o.idx].iconKind, o.px, o.py, o.r * 0.66f, iconAngle, orbAlpha, accent);

        if (!items[o.idx].badge.empty() && o.depth > 0.35f) {
            float bw = 0.0f, bh = 0.0f;
            MeasureText(items[o.idx].badge, 12.5f, STYLE_MONO, 64.0f, 1, bw, bh, 1);
            const float pW = std::max(26.0f, bw + 14.0f);
            const float pH = 21.0f;
            const float bx = o.px + o.r * 0.44f;
            const float by = o.py - o.r * 0.92f;
            RgbaColor pillCol = selected ? accent : SecondaryAccentColor(1.0f);
            DrawPanel(bx, by, pW, pH, 10.5f, 1.0f,
                      {pillCol.r, pillCol.g, pillCol.b, orbAlpha},
                      {pillCol.r * 0.78f, pillCol.g * 0.78f, pillCol.b * 0.78f, orbAlpha},
                      {1.0f, 1.0f, 1.0f, 0.65f * orbAlpha});
            DrawText(items[o.idx].badge, bx, by + (pH - bh) * 0.5f, 12.5f, STYLE_MONO,
                     {1.0f, 1.0f, 1.0f, orbAlpha}, pW, 1, 1, pW, 1);
        }

        if (overlay_ == OVERLAY_NONE) {
            RegisterHit(o.px - o.r * 1.08f, o.py - o.r * 1.08f, o.r * 2.16f, o.r * 2.16f,
                        HIT_HUB_ORB, o.idx);
        }
    }

    // Active Orb Title & Subtitle Capsule
    const HubItem& activeItem = items[main_index_];
    const float frontOrbBot = hubCy + ry + baseOrbR * 1.06f;
    const float labelW = std::min(m.bodyW, m.portrait ? 460.0f : 480.0f);
    const float labelH = 62.0f;
    const float labelX = hubCx - labelW * 0.5f;
    const float labelY = frontOrbBot + 14.0f;
    DrawPanel(
        labelX, labelY, labelW, labelH, 18.0f, 1.4f,
        PanelTopColor(0.88f * alpha),
        PanelBotColor(0.94f * alpha),
        SelectedBorderColor(0.75f * alpha));

    float titleW = 0.0f, titleH = 0.0f, subW = 0.0f, subH = 0.0f;
    MeasureText(activeItem.title, 22.0f, STYLE_ORB_TITLE, labelW - 28.0f, 1, titleW, titleH);
    MeasureText(activeItem.subtitle, 13.5f, STYLE_BODY_BOLD, labelW - 28.0f, 1, subW, subH);
    DrawText(activeItem.title, labelX, labelY + 9.0f, 22.0f, STYLE_ORB_TITLE,
             PrimaryTextColor(alpha), labelW - 28.0f, 1, 1, labelW);
    DrawText(activeItem.subtitle, labelX, labelY + 35.0f, 13.5f, STYLE_BODY_BOLD,
             SecondaryTextColor(alpha), labelW - 28.0f, 1, 1, labelW);

    // Lower Live Dreamcast Clock + Multi-Core Library Summary Cards
    const float infoTop = labelY + labelH + 14.0f;
    const float infoAvailH = std::max(64.0f, m.bodyBot - infoTop);
    const float cardH = std::min(78.0f, infoAvailH);

    if (!m.portrait) {
        const float gap = 14.0f;
        const float cardW = std::min(280.0f, (m.bodyW - gap) * 0.5f);
        const float groupX = hubCx - (cardW * 2.0f + gap) * 0.5f;

        // Left: Live Analog + Digital Dreamcast Clock
        DrawPanel(groupX, infoTop, cardW, cardH, 16.0f, 1.2f,
                  PanelTopColor(0.85f * alpha), PanelBotColor(0.92f * alpha), PanelBorderColor(0.75f * alpha));
        const float clockR = std::min(27.0f, cardH * 0.36f);
        DrawAnalogClock(groupX + 14.0f + clockR, infoTop + cardH * 0.5f, clockR, alpha);
        const float tx = groupX + 26.0f + clockR * 2.0f;
        const float twMax = cardW - (tx - groupX) - 12.0f;
        DrawText(cached_time_str_, tx, infoTop + cardH * 0.5f - 19.0f, 18.0f, STYLE_MONO,
                 PrimaryTextColor(alpha), twMax, 1, 0);
        DrawText(cached_date_str_, tx, infoTop + cardH * 0.5f + 4.0f, 13.0f, STYLE_BODY,
                 SecondaryTextColor(alpha), twMax, 1, 0);

        // Right: Sega Multi-Core Readiness Card
        const float rxCard = groupX + cardW + gap;
        DrawPanel(rxCard, infoTop, cardW, cardH, 16.0f, 1.2f,
                  PanelTopColor(0.85f * alpha), PanelBotColor(0.92f * alpha), PanelBorderColor(0.75f * alpha));
        const float sIconX = rxCard + 32.0f;
        DrawSwirl(sIconX, infoTop + cardH * 0.5f, 18.0f, 1.0f, -spin_angle_ * 0.25f, alpha * 0.9f, SecondaryAccentColor(1.0f));
        const float rtx = rxCard + 60.0f;
        const float rtw = cardW - 72.0f;
        DrawText(TextOr("dcui_hub_systems_title", "Sega BIOS Hub"), rtx, infoTop + cardH * 0.5f - 18.0f,
                 15.0f, STYLE_BODY_BOLD, PrimaryTextColor(alpha), rtw, 1, 0);
        DrawText(status_.biosSummary.empty() ? "Dreamcast · Saturn · Mega Drive · Arcade" : status_.biosSummary,
                 rtx, infoTop + cardH * 0.5f + 3.0f, 12.5f, STYLE_BODY,
                 SecondaryTextColor(alpha), rtw, 1, 0);
    } else {
        // Portrait: stack Live Clock & BIOS Summary cleanly
        const float cardW = std::min(m.bodyW, 460.0f);
        const float cxCard = hubCx - cardW * 0.5f;
        const float pCardH = std::min(76.0f, (infoAvailH - 12.0f) * 0.5f);
        if (pCardH >= 54.0f) {
            DrawPanel(cxCard, infoTop, cardW, pCardH, 16.0f, 1.2f,
                      PanelTopColor(0.85f * alpha), PanelBotColor(0.92f * alpha), PanelBorderColor(0.75f * alpha));
            const float clockR = std::min(26.0f, pCardH * 0.36f);
            DrawAnalogClock(cxCard + 16.0f + clockR, infoTop + pCardH * 0.5f, clockR, alpha);
            const float tx = cxCard + 28.0f + clockR * 2.0f;
            DrawText(cached_time_str_, tx, infoTop + pCardH * 0.5f - 19.0f, 18.5f, STYLE_MONO,
                     PrimaryTextColor(alpha), cardW - (tx - cxCard) - 14.0f, 1, 0);
            DrawText(cached_date_str_, tx, infoTop + pCardH * 0.5f + 5.0f, 13.5f, STYLE_BODY,
                     SecondaryTextColor(alpha), cardW - (tx - cxCard) - 14.0f, 1, 0);

            const float y2 = infoTop + pCardH + 12.0f;
            DrawPanel(cxCard, y2, cardW, pCardH, 16.0f, 1.2f,
                      PanelTopColor(0.85f * alpha), PanelBotColor(0.92f * alpha), PanelBorderColor(0.75f * alpha));
            DrawSwirl(cxCard + 36.0f, y2 + pCardH * 0.5f, 18.0f, 1.0f, -spin_angle_ * 0.25f, alpha * 0.9f, SecondaryAccentColor(1.0f));
            DrawText(TextOr("dcui_hub_systems_title", "Sega BIOS Hub"), cxCard + 68.0f, y2 + pCardH * 0.5f - 18.0f,
                     15.5f, STYLE_BODY_BOLD, PrimaryTextColor(alpha), cardW - 82.0f, 1, 0);
            DrawText(status_.biosSummary.empty() ? "Dreamcast · Saturn · Mega Drive · Arcade" : status_.biosSummary,
                     cxCard + 68.0f, y2 + pCardH * 0.5f + 4.0f, 13.0f, STYLE_BODY,
                     SecondaryTextColor(alpha), cardW - 82.0f, 1, 0);
        }
    }
}

void DcRenderer::EnsureTabVisible(float stripW) {
    if (tab_index_ < 0 || tab_index_ >= static_cast<int>(tab_layout_bounds_.size())) return;
    const auto [tx, tw] = tab_layout_bounds_[tab_index_];
    const float margin = 24.0f;
    if (tx - tab_scroll_target_x_ < margin) {
        tab_scroll_target_x_ = std::max(0.0f, tx - margin);
    } else if (tx + tw - tab_scroll_target_x_ > stripW - margin) {
        tab_scroll_target_x_ = std::max(0.0f, tx + tw - (stripW - margin));
    }
}

void DcRenderer::EnsureGameVisible(int count, float listH, float rowStep) {
    if (count <= 0) {
        game_scroll_target_y_ = 0.0f;
        return;
    }
    const float maxScroll = std::max(0.0f, static_cast<float>(count) * rowStep - listH + 8.0f);
    const float top = static_cast<float>(game_index_) * rowStep;
    const float bot = top + rowStep - 6.0f;
    if (top < game_scroll_target_y_) game_scroll_target_y_ = top;
    else if (bot > game_scroll_target_y_ + listH) game_scroll_target_y_ = bot - listH;
    game_scroll_target_y_ = std::clamp(game_scroll_target_y_, 0.0f, maxScroll);
}

void DcRenderer::EnsureSettingVisible(float viewH) {
    const float rowStep = 55.0f;
    const float maxScroll = std::max(0.0f, static_cast<float>(kSettingsCount) * rowStep - viewH + 8.0f);
    const float top = static_cast<float>(settings_index_) * rowStep;
    const float bot = top + 48.0f;
    if (top < settings_scroll_target_y_) settings_scroll_target_y_ = top;
    else if (bot > settings_scroll_target_y_ + viewH) settings_scroll_target_y_ = bot - viewH;
    settings_scroll_target_y_ = std::clamp(settings_scroll_target_y_, 0.0f, maxScroll);
}

void DcRenderer::DrawGameShowcase(
    const GameEntry& game,
    int absGameIdx,
    const DisplayTab& tab,
    float x, float y, float w, float h,
    bool compactHorizontal,
    float alpha) {
    const RgbaColor platCol = tab.isRecent ? ColorForPlatform(game.platformId) : tab.accent;
    DrawPanel(
        x, y, w, h, 18.0f, 1.5f,
        PanelTopColor(0.90f * alpha),
        PanelBotColor(0.95f * alpha),
        PanelBorderColor(0.82f * alpha),
        {platCol.r, platCol.g, platCol.b, 0.08f});

    const float pad = 14.0f;
    const float btnH = 36.0f;

    if (compactHorizontal) {
        // Portrait top showcase card: cover on left, title/meta + action buttons on right
        const float artH = std::max(96.0f, h - pad * 2.0f);
        const float artW = std::min(w * 0.34f, artH * 0.92f);
        const float artX = x + pad;
        const float artY = y + (h - artH) * 0.5f;

        DrawPanel(artX, artY, artW, artH, 12.0f, 1.1f,
                  PanelTopColor(0.75f * alpha), PanelBotColor(0.88f * alpha), PanelBorderColor(0.65f * alpha));
        const auto& img = GetOrCreateImage(game.coverPath, 320);
        if (img.tex) {
            DrawImageAspectFit(img, artX + 4.0f, artY + 4.0f, artW - 8.0f, artH - 8.0f, alpha * ImageAlpha(img));
        } else {
            DrawShaderIcon(ShaderIconForPlatform(game.platformId),
                           artX + artW * 0.5f, artY + artH * 0.5f, std::min(artW, artH) * 0.36f,
                           spin_angle_ * 0.7f, alpha, platCol);
        }

        const float infoX = artX + artW + 12.0f;
        const float infoW = x + w - pad - infoX;
        DrawText(game.title, infoX, y + pad, 17.5f, STYLE_ORB_TITLE, PrimaryTextColor(alpha), infoW, 2, 0);

        char meta[128];
        if (!game.serial.empty() && !game.sizeStr.empty()) {
            std::snprintf(meta, sizeof(meta), "%s · %s · %s",
                          game.platformLabel.c_str(), game.serial.c_str(), game.sizeStr.c_str());
        } else {
            std::snprintf(meta, sizeof(meta), "%s · %s",
                          game.platformLabel.c_str(), game.sizeStr.c_str());
        }
        DrawText(meta, infoX, y + pad + 46.0f, 12.5f, STYLE_BODY, SecondaryTextColor(alpha), infoW, 1, 0);

        if (game.saveCount > 0) {
            char sv[48];
            std::snprintf(sv, sizeof(sv), "%d %s", game.saveCount, TextOr("dcui_saves_word", "Saves").c_str());
            DrawText(sv, infoX, y + pad + 65.0f, 12.5f, STYLE_MONO, SecondaryAccentColor(alpha), infoW, 1, 0);
        }

        // Action Buttons Row at bottom right
        const float btnY = y + h - pad - btnH;
        const bool hasContinue = (game.latestSaveSlot >= 0);
        const float gap = 8.0f;
        const float optW = 42.0f;
        const float availBtnW = infoW - optW - gap;
        const float startW = hasContinue ? (availBtnW - gap) * 0.52f : availBtnW;

        DrawPanel(infoX, btnY, startW, btnH, 10.0f, 1.2f,
                  {platCol.r, platCol.g, platCol.b, 0.96f * alpha},
                  {platCol.r * 0.78f, platCol.g * 0.78f, platCol.b * 0.78f, 0.98f * alpha},
                  {1.0f, 1.0f, 1.0f, 0.55f * alpha});
        float sw = 0.0f, sh = 0.0f;
        const std::string sTxt = TextOr("dcui_btn_start", "Start");
        MeasureText(sTxt, 14.0f, STYLE_BODY_BOLD, startW - 8.0f, 1, sw, sh, 1);
        DrawText(sTxt, infoX, btnY + (btnH - sh) * 0.5f, 14.0f, STYLE_BODY_BOLD,
                 {1.0f, 1.0f, 1.0f, alpha}, startW - 8.0f, 1, 1, startW, 1);
        if (overlay_ == OVERLAY_NONE) RegisterHit(infoX, btnY, startW, btnH, HIT_GAME_START, absGameIdx);

        float curX = infoX + startW + gap;
        if (hasContinue) {
            const float contW = availBtnW - gap - startW;
            const RgbaColor cCol = SecondaryAccentColor(1.0f);
            DrawPanel(curX, btnY, contW, btnH, 10.0f, 1.2f,
                      {cCol.r, cCol.g, cCol.b, 0.92f * alpha},
                      {cCol.r * 0.78f, cCol.g * 0.78f, cCol.b * 0.78f, 0.96f * alpha},
                      {1.0f, 1.0f, 1.0f, 0.45f * alpha});
            const std::string cTxt = TextOr("dcui_btn_continue", "Continue");
            MeasureText(cTxt, 14.0f, STYLE_BODY_BOLD, contW - 8.0f, 1, sw, sh, 1);
            DrawText(cTxt, curX, btnY + (btnH - sh) * 0.5f, 14.0f, STYLE_BODY_BOLD,
                     {1.0f, 1.0f, 1.0f, alpha}, contW - 8.0f, 1, 1, contW, 1);
            if (overlay_ == OVERLAY_NONE) RegisterHit(curX, btnY, contW, btnH, HIT_GAME_CONTINUE, absGameIdx);
            curX += contW + gap;
        }

        DrawPanel(curX, btnY, optW, btnH, 10.0f, 1.2f,
                  PanelTopColor(0.86f * alpha), PanelBotColor(0.94f * alpha), PanelBorderColor(0.8f * alpha));
        MeasureText("...", 15.0f, STYLE_MONO, optW, 1, sw, sh);
        DrawText("...", curX, btnY + (btnH - sh) * 0.5f, 15.0f, STYLE_MONO, PrimaryTextColor(alpha), optW, 1, 1, optW);
        if (overlay_ == OVERLAY_NONE) RegisterHit(curX, btnY, optW, btnH, HIT_GAME_OPTIONS, absGameIdx);
        return;
    }

    // Landscape tall Showcase Panel
    const float artH = std::clamp(h * 0.50f, 120.0f, 224.0f);
    const float artW = w - pad * 2.0f;
    const float artX = x + pad;
    const float artY = y + pad;
    DrawPanel(artX, artY, artW, artH, 14.0f, 1.1f,
              PanelTopColor(0.75f * alpha), PanelBotColor(0.88f * alpha), PanelBorderColor(0.65f * alpha));

    const auto& img = GetOrCreateImage(game.coverPath, 384);
    if (img.tex) {
        DrawImageAspectFit(img, artX + 6.0f, artY + 6.0f, artW - 12.0f, artH - 12.0f, alpha * ImageAlpha(img));
    } else {
        DrawShaderIcon(ShaderIconForPlatform(game.platformId),
                       artX + artW * 0.5f, artY + artH * 0.5f, std::min(artW, artH) * 0.34f,
                       spin_angle_ * 0.7f, alpha, platCol);
    }

    const float textTop = artY + artH + 10.0f;
    const float textW = w - pad * 2.0f;
    DrawText(game.title, x + pad, textTop, 18.0f, STYLE_ORB_TITLE, PrimaryTextColor(alpha), textW, 2, 0);

    // Console pill + serial/size
    float pw = 0.0f, ph = 0.0f;
    MeasureText(game.platformLabel, 12.0f, STYLE_BODY_BOLD, 160.0f, 1, pw, ph, 1);
    const float chipW = pw + 18.0f;
    const float chipH = 22.0f;
    const float chipY = textTop + 48.0f;
    DrawPanel(x + pad, chipY, chipW, chipH, 11.0f, 0.0f,
              {platCol.r, platCol.g, platCol.b, alpha},
              {platCol.r * 0.80f, platCol.g * 0.80f, platCol.b * 0.80f, alpha},
              {1.0f, 1.0f, 1.0f, 0.0f});
    DrawText(game.platformLabel, x + pad, chipY + (chipH - ph) * 0.5f, 12.0f, STYLE_BODY_BOLD,
             {1.0f, 1.0f, 1.0f, alpha}, chipW, 1, 1, chipW, 1);

    char metaBuf[128];
    if (!game.serial.empty() && !game.sizeStr.empty()) {
        std::snprintf(metaBuf, sizeof(metaBuf), "%s · %s", game.serial.c_str(), game.sizeStr.c_str());
    } else {
        std::snprintf(metaBuf, sizeof(metaBuf), "%s", game.sizeStr.c_str());
    }
    DrawText(metaBuf, x + pad + chipW + 10.0f, chipY + 3.0f, 13.0f, STYLE_MONO,
             SecondaryTextColor(alpha), textW - chipW - 12.0f, 1, 0);

    if (game.saveCount > 0 || game.discCount > 1) {
        char extraBuf[96] = {};
        if (game.saveCount > 0 && game.discCount > 1) {
            std::snprintf(extraBuf, sizeof(extraBuf), "%d %s · %d %s",
                          game.discCount, TextOr("dcui_discs", "Discs").c_str(),
                          game.saveCount, TextOr("dcui_saves_word", "Saves").c_str());
        } else if (game.discCount > 1) {
            std::snprintf(extraBuf, sizeof(extraBuf), "%d %s",
                          game.discCount, TextOr("dcui_discs", "Discs").c_str());
        } else {
            std::snprintf(extraBuf, sizeof(extraBuf), "%s: %d",
                          TextOr("dcui_quick_saves_lbl", "Quick Saves").c_str(), game.saveCount);
        }
        DrawText(extraBuf, x + pad, chipY + 28.0f, 12.5f, STYLE_BODY_BOLD,
                 SecondaryAccentColor(alpha), textW, 1, 0);
    }

    // Bottom Action Buttons
    const float btnY = y + h - pad - btnH;
    const bool hasContinue = (game.latestSaveSlot >= 0);
    const float gap = 8.0f;
    const float optW = 78.0f;
    const float availBtnW = textW - optW - gap;
    const float startW = hasContinue ? (availBtnW - gap) * 0.50f : availBtnW;

    DrawPanel(x + pad, btnY, startW, btnH, 10.0f, 1.2f,
              {platCol.r, platCol.g, platCol.b, 0.96f * alpha},
              {platCol.r * 0.78f, platCol.g * 0.78f, platCol.b * 0.78f, 0.98f * alpha},
              {1.0f, 1.0f, 1.0f, 0.55f * alpha});
    float bw = 0.0f, bh = 0.0f;
    const std::string sTxt = TextOr("dcui_btn_start", "Start");
    MeasureText(sTxt, 14.5f, STYLE_BODY_BOLD, startW - 8.0f, 1, bw, bh, 1);
    DrawText(sTxt, x + pad, btnY + (btnH - bh) * 0.5f, 14.5f, STYLE_BODY_BOLD,
             {1.0f, 1.0f, 1.0f, alpha}, startW - 8.0f, 1, 1, startW, 1);
    if (overlay_ == OVERLAY_NONE) RegisterHit(x + pad, btnY, startW, btnH, HIT_GAME_START, absGameIdx);

    float curX = x + pad + startW + gap;
    if (hasContinue) {
        const float contW = availBtnW - gap - startW;
        const RgbaColor cCol = SecondaryAccentColor(1.0f);
        DrawPanel(curX, btnY, contW, btnH, 10.0f, 1.2f,
                  {cCol.r, cCol.g, cCol.b, 0.92f * alpha},
                  {cCol.r * 0.78f, cCol.g * 0.78f, cCol.b * 0.78f, 0.96f * alpha},
                  {1.0f, 1.0f, 1.0f, 0.45f * alpha});
        const std::string cTxt = TextOr("dcui_btn_continue", "Continue");
        MeasureText(cTxt, 14.5f, STYLE_BODY_BOLD, contW - 8.0f, 1, bw, bh, 1);
        DrawText(cTxt, curX, btnY + (btnH - bh) * 0.5f, 14.5f, STYLE_BODY_BOLD,
                 {1.0f, 1.0f, 1.0f, alpha}, contW - 8.0f, 1, 1, contW, 1);
        if (overlay_ == OVERLAY_NONE) RegisterHit(curX, btnY, contW, btnH, HIT_GAME_CONTINUE, absGameIdx);
        curX += contW + gap;
    }

    DrawPanel(curX, btnY, optW, btnH, 10.0f, 1.2f,
              PanelTopColor(0.86f * alpha), PanelBotColor(0.94f * alpha), PanelBorderColor(0.82f * alpha));
    const std::string oTxt = TextOr("dcui_btn_options", "Options");
    MeasureText(oTxt, 13.5f, STYLE_BODY_BOLD, optW - 8.0f, 1, bw, bh);
    DrawText(oTxt, curX, btnY + (btnH - bh) * 0.5f, 13.5f, STYLE_BODY_BOLD,
             PrimaryTextColor(alpha), optW - 8.0f, 1, 1, optW);
    if (overlay_ == OVERLAY_NONE) RegisterHit(curX, btnY, optW, btnH, HIT_GAME_OPTIONS, absGameIdx);
}

void DcRenderer::DrawGamesScreen(const LayoutMetrics& m, float alpha) {
    DrawTopBar(m, TextOr("dcui_menu_play", "Play"), false, alpha);

    // 1. Horizontal Console Tab Strip (Recent first when any game launched, then per-console tabs)
    const float stripX = m.padX;
    const float stripY = m.bodyTop;
    const float stripW = m.bodyW;
    const float stripH = 40.0f;

    tab_layout_bounds_.clear();
    if (!display_tabs_.empty()) {
        float totalTabsW = 0.0f;
        std::vector<float> tabWidths;
        tabWidths.reserve(display_tabs_.size());
        for (size_t i = 0; i < display_tabs_.size(); ++i) {
            char tabText[128];
            std::snprintf(tabText, sizeof(tabText), "%s (%zu)",
                          display_tabs_[i].label.c_str(), display_tabs_[i].gameIndices.size());
            float lw = 0.0f, lh = 0.0f;
            MeasureText(tabText, 13.5f, STYLE_BODY_BOLD, 210.0f, 1, lw, lh, 1);
            const float w = lw + 26.0f;
            tabWidths.push_back(w);
            tab_layout_bounds_.push_back({totalTabsW, w});
            totalTabsW += w + 8.0f;
        }
        if (!display_tabs_.empty()) totalTabsW -= 8.0f;
        const float maxTabScroll = std::max(0.0f, totalTabsW - stripW);
        tab_scroll_target_x_ = std::clamp(tab_scroll_target_x_, 0.0f, maxTabScroll);
        tab_scroll_x_ = std::clamp(tab_scroll_x_, 0.0f, maxTabScroll);

        {
            FlushQuadBatch();
            ScopedScissor scissor(view_x_, view_y_, view_width_, view_height_,
                                  screen_width_, screen_height_, stripX, stripY, stripW, stripH);
            float curX = stripX - tab_scroll_x_;
            for (size_t i = 0; i < display_tabs_.size(); ++i) {
                const float w = tabWidths[i];
                const bool sel = (static_cast<int>(i) == tab_index_);
                const RgbaColor ac = display_tabs_[i].accent;
                if (curX + w >= stripX - 10.0f && curX <= stripX + stripW + 10.0f) {
                    if (sel) {
                        DrawPanel(curX, stripY + 2.0f, w, stripH - 4.0f, 16.0f, 1.5f,
                                  {ac.r, ac.g, ac.b, 0.94f * alpha},
                                  {ac.r * 0.78f, ac.g * 0.78f, ac.b * 0.78f, 0.98f * alpha},
                                  {1.0f, 1.0f, 1.0f, 0.60f * alpha});
                    } else {
                        DrawPanel(curX, stripY + 3.0f, w, stripH - 6.0f, 15.0f, 1.1f,
                                  PanelTopColor(0.80f * alpha),
                                  PanelBotColor(0.88f * alpha),
                                  {ac.r, ac.g, ac.b, 0.45f * alpha});
                    }
                    char tabText[128];
                    std::snprintf(tabText, sizeof(tabText), "%s (%zu)",
                                  display_tabs_[i].label.c_str(), display_tabs_[i].gameIndices.size());
                    float tw = 0.0f, th = 0.0f;
                    const int sMode = sel ? 1 : DefaultShadowMode();
                    MeasureText(tabText, 13.5f, STYLE_BODY_BOLD, 210.0f, 1, tw, th, sMode);
                    DrawText(tabText, curX, stripY + (stripH - th) * 0.5f, 13.5f, STYLE_BODY_BOLD,
                             sel ? RgbaColor{1.0f, 1.0f, 1.0f, alpha} : PrimaryTextColor(0.88f * alpha),
                             210.0f, 1, 1, w, sMode);
                    if (overlay_ == OVERLAY_NONE) {
                        RegisterHit(std::max(stripX, curX), stripY, std::min(w, stripX + stripW - std::max(stripX, curX)),
                                    stripH, HIT_CONSOLE_TAB, static_cast<int>(i));
                    }
                }
                curX += w + 8.0f;
            }
            FlushQuadBatch();
        }
    }

    const float contentTop = stripY + stripH + 10.0f;
    const float contentBot = m.bodyBot;
    const float contentH = std::max(120.0f, contentBot - contentTop);

    if (display_tabs_.empty()) {
        DrawPanel(m.padX, contentTop, m.bodyW, contentH, 18.0f, 1.4f,
                  PanelTopColor(0.86f * alpha), PanelBotColor(0.94f * alpha), PanelBorderColor(0.75f * alpha));
        const std::string msg = games_loading_
            ? TextOr("dcui_loading_games", "Scanning game library...")
            : TextOr("dcui_no_games", "No games found in library.");
        float mw = 0.0f, mh = 0.0f;
        MeasureText(msg, 18.0f, STYLE_BODY_BOLD, m.bodyW - 40.0f, 2, mw, mh);
        DrawText(msg, m.padX, contentTop + (contentH - mh) * 0.5f, 18.0f, STYLE_BODY_BOLD,
                 PrimaryTextColor(alpha), m.bodyW - 40.0f, 2, 1, m.bodyW);
        return;
    }

    const DisplayTab& activeTab = display_tabs_[tab_index_];
    const int count = static_cast<int>(activeTab.gameIndices.size());
    const int absSelIdx = ActiveTabAbsGameIndex(game_index_);

    float listX = m.padX;
    float listY = contentTop;
    float listW = m.bodyW;
    float listH = contentH;

    if (!m.portrait) {
        listW = m.bodyW * 0.545f;
        const float rightX = listX + listW + 14.0f;
        const float rightW = m.padX + m.bodyW - rightX;
        if (absSelIdx >= 0 && absSelIdx < static_cast<int>(games_.size())) {
            DrawGameShowcase(games_[absSelIdx], absSelIdx, activeTab, rightX, contentTop, rightW, contentH, false, alpha);
        }
    } else {
        const float showcaseH = std::min(198.0f, contentH * 0.42f);
        if (absSelIdx >= 0 && absSelIdx < static_cast<int>(games_.size())) {
            DrawGameShowcase(games_[absSelIdx], absSelIdx, activeTab, m.padX, contentTop, m.bodyW, showcaseH, true, alpha);
        }
        listY = contentTop + showcaseH + 10.0f;
        listH = std::max(90.0f, contentBot - listY);
    }

    // Scrolled Games List
    const float rowH = 64.0f;
    const float rowStep = 72.0f;
    const float maxScroll = std::max(0.0f, static_cast<float>(count) * rowStep - listH + 4.0f);
    game_scroll_target_y_ = std::clamp(game_scroll_target_y_, 0.0f, maxScroll);
    game_scroll_y_ = std::clamp(game_scroll_y_, 0.0f, maxScroll);

    {
        FlushQuadBatch();
        ScopedScissor scissor(view_x_, view_y_, view_width_, view_height_,
                              screen_width_, screen_height_, listX, listY, listW, listH);

        const int firstVis = std::max(0, static_cast<int>(std::floor(game_scroll_y_ / rowStep)) - 1);
        const int lastVis = std::min(count - 1, static_cast<int>(std::ceil((game_scroll_y_ + listH) / rowStep)) + 1);
        const float shimmer = (settings_.motionMode == 3) ? -1.0f : std::fmod(time_sec_ * 0.55f, 1.8f);

        for (int row = firstVis; row <= lastVis; ++row) {
            const int absIdx = activeTab.gameIndices[row];
            const GameEntry& g = games_[absIdx];
            const float ry = listY + static_cast<float>(row) * rowStep - game_scroll_y_;
            const bool sel = (row == game_index_);
            const RgbaColor pCol = activeTab.isRecent ? ColorForPlatform(g.platformId) : activeTab.accent;

            DrawPanel(
                listX, ry, listW, rowH, 14.0f, sel ? 1.8f : 1.0f,
                PanelTopColor((sel ? 0.95f : 0.80f) * alpha),
                PanelBotColor((sel ? 0.98f : 0.88f) * alpha),
                sel ? pCol : PanelBorderColor(0.62f * alpha),
                sel ? RgbaColor{pCol.r, pCol.g, pCol.b, 0.14f} : RgbaColor{0, 0, 0, 0},
                sel ? shimmer : -1.0f);

            // Left accent stripe
            if (sel) {
                DrawPanel(listX + 5.0f, ry + 12.0f, 4.0f, rowH - 24.0f, 2.0f, 0.0f, pCol, pCol, pCol);
            }

            // Cover Thumbnail (46x46)
            const float thumbS = 48.0f;
            const float thumbX = listX + (sel ? 16.0f : 12.0f);
            const float thumbY = ry + (rowH - thumbS) * 0.5f;
            const auto& img = GetOrCreateImage(g.coverPath, 192);
            if (img.tex) {
                DrawImageAspectFit(img, thumbX, thumbY, thumbS, thumbS, alpha * ImageAlpha(img));
            } else {
                DrawShaderIcon(ShaderIconForPlatform(g.platformId),
                               thumbX + thumbS * 0.5f, thumbY + thumbS * 0.5f, thumbS * 0.44f,
                               sel ? spin_angle_ * 0.9f : 0.25f * static_cast<float>(row), alpha, pCol);
            }

            // Right Badges (Discs / Saves)
            float rightReserve = 12.0f;
            if (g.saveCount > 0) {
                char sBuf[16];
                std::snprintf(sBuf, sizeof(sBuf), "S%d", g.saveCount);
                float bw = 0.0f, bh = 0.0f;
                MeasureText(sBuf, 12.0f, STYLE_MONO, 48.0f, 1, bw, bh, 1);
                const float pw = std::max(28.0f, bw + 12.0f);
                const float ph = 22.0f;
                const float px = listX + listW - rightReserve - pw;
                const float py = ry + (rowH - ph) * 0.5f;
                const RgbaColor sc = SecondaryAccentColor(1.0f);
                DrawPanel(px, py, pw, ph, 11.0f, 0.0f,
                          {sc.r, sc.g, sc.b, 0.92f * alpha},
                          {sc.r * 0.80f, sc.g * 0.80f, sc.b * 0.80f, 0.95f * alpha},
                          {0, 0, 0, 0});
                DrawText(sBuf, px, py + (ph - bh) * 0.5f, 12.0f, STYLE_MONO,
                         {1.0f, 1.0f, 1.0f, alpha}, pw, 1, 1, pw, 1);
                rightReserve += pw + 6.0f;
            }
            if (g.discCount > 1) {
                char dBuf[16];
                std::snprintf(dBuf, sizeof(dBuf), "%dD", g.discCount);
                float bw = 0.0f, bh = 0.0f;
                MeasureText(dBuf, 12.0f, STYLE_MONO, 42.0f, 1, bw, bh);
                const float pw = std::max(26.0f, bw + 12.0f);
                const float ph = 22.0f;
                const float px = listX + listW - rightReserve - pw;
                const float py = ry + (rowH - ph) * 0.5f;
                DrawPanel(px, py, pw, ph, 11.0f, 1.0f,
                          PanelTopColor(0.90f * alpha), PanelBotColor(0.94f * alpha), PanelBorderColor(0.8f * alpha));
                DrawText(dBuf, px, py + (ph - bh) * 0.5f, 12.0f, STYLE_MONO,
                         PrimaryTextColor(alpha), pw, 1, 1, pw);
                rightReserve += pw + 6.0f;
            }

            const float textX = thumbX + thumbS + 12.0f;
            const float maxTW = std::max(60.0f, (listX + listW - rightReserve) - textX);
            DrawText(g.title, textX, ry + 11.0f, 16.0f, STYLE_BODY_BOLD,
                     PrimaryTextColor(alpha), maxTW, 1, 0);

            char sub[128];
            if (activeTab.isRecent) {
                std::snprintf(sub, sizeof(sub), "%s · %s", g.platformLabel.c_str(), g.sizeStr.c_str());
            } else if (!g.serial.empty()) {
                std::snprintf(sub, sizeof(sub), "%s · %s", g.serial.c_str(), g.sizeStr.c_str());
            } else {
                std::snprintf(sub, sizeof(sub), "%s", g.sizeStr.c_str());
            }
            DrawText(sub, textX, ry + 36.0f, 12.5f, STYLE_BODY, SecondaryTextColor(alpha), maxTW, 1, 0);

            if (overlay_ == OVERLAY_NONE && ry + rowH >= listY && ry <= listY + listH) {
                RegisterHit(listX, std::max(listY, ry), listW,
                            std::min(ry + rowH, listY + listH) - std::max(listY, ry),
                            HIT_GAME_ROW, row);
            }
        }
        FlushQuadBatch();
    }
}

void DcRenderer::DrawQuickSavesTab(const LayoutMetrics& m, float topY, float botY, float alpha) {
    const std::vector<int> filtered = FilteredSaveIndices();
    const int totalFiltered = static_cast<int>(filtered.size());
    const int cols = m.portrait ? 3 : 4;
    const int rows = 3;
    const int perPage = cols * rows;
    const int totalPages = std::max(1, (totalFiltered + perPage - 1) / perPage);
    save_page_ = std::clamp(save_page_, 0, totalPages - 1);
    save_slot_index_ = std::clamp(save_slot_index_, 0, perPage - 1);

    const float contentH = std::max(120.0f, botY - topY);
    const int curPos = save_page_ * perPage + save_slot_index_;
    const bool hasSave = (curPos >= 0 && curPos < totalFiltered);

    float gridX = m.padX;
    float gridY = topY;
    float gridW = m.bodyW;
    float gridH = contentH - 42.0f;

    float inspX = m.padX;
    float inspY = topY;
    float inspW = m.bodyW;
    float inspH = contentH;

    if (!m.portrait) {
        gridW = m.bodyW * 0.57f;
        inspX = gridX + gridW + 14.0f;
        inspW = m.padX + m.bodyW - inspX;
    } else {
        inspH = std::min(176.0f, contentH * 0.36f);
        gridY = topY + inspH + 10.0f;
        gridH = std::max(120.0f, botY - gridY - 40.0f);
    }

    // Save Inspector Panel
    DrawPanel(inspX, inspY, inspW, inspH, 18.0f, 1.4f,
              PanelTopColor(0.90f * alpha), PanelBotColor(0.95f * alpha), PanelBorderColor(0.80f * alpha));

    const float pad = 14.0f;
    if (hasSave) {
        const SaveEntry& sv = saves_[filtered[curPos]];
        if (m.portrait) {
            const float prevH = inspH - pad * 2.0f;
            const float prevW = std::min(inspW * 0.38f, prevH * 1.25f);
            DrawPanel(inspX + pad, inspY + pad, prevW, prevH, 10.0f, 1.0f,
                      PanelTopColor(0.75f * alpha), PanelBotColor(0.88f * alpha), PanelBorderColor(0.6f * alpha));
            const auto& img = GetOrCreateImage(sv.previewPath, 320);
            if (img.tex) {
                DrawImageAspectFit(img, inspX + pad + 3.0f, inspY + pad + 3.0f, prevW - 6.0f, prevH - 6.0f, alpha * ImageAlpha(img));
            } else {
                DrawShaderIcon(ICON_SAVE_CRYSTAL, inspX + pad + prevW * 0.5f, inspY + pad + prevH * 0.5f,
                               std::min(prevW, prevH) * 0.36f, spin_angle_, alpha, SecondaryAccentColor(1.0f));
            }
            const float tx = inspX + pad + prevW + 12.0f;
            const float tw = inspW - (tx - inspX) - pad;
            DrawText(sv.title, tx, inspY + pad, 16.5f, STYLE_ORB_TITLE, PrimaryTextColor(alpha), tw, 1, 0);
            DrawText(sv.slotLabel + " · " + sv.platformLabel, tx, inspY + pad + 26.0f, 12.5f, STYLE_BODY_BOLD,
                     SwirlAccentColor(alpha), tw, 1, 0);
            DrawText(sv.dateStr + (sv.sizeStr.empty() ? "" : (" · " + sv.sizeStr)), tx, inspY + pad + 45.0f,
                     12.0f, STYLE_MONO, SecondaryTextColor(alpha), tw, 1, 0);

            const float btnY = inspY + inspH - pad - 34.0f;
            const float btnW = (tw - 8.0f) * 0.5f;
            const RgbaColor ac = SwirlAccentColor(1.0f);
            DrawPanel(tx, btnY, btnW, 34.0f, 9.0f, 1.2f,
                      {ac.r, ac.g, ac.b, 0.94f * alpha}, {ac.r * 0.8f, ac.g * 0.8f, ac.b * 0.8f, alpha},
                      {1.0f, 1.0f, 1.0f, 0.5f * alpha});
            float bw = 0.0f, bh = 0.0f;
            const std::string lTxt = TextOr("dcui_mc_load", "Load");
            MeasureText(lTxt, 13.5f, STYLE_BODY_BOLD, btnW, 1, bw, bh, 1);
            DrawText(lTxt, tx, btnY + (34.0f - bh) * 0.5f, 13.5f, STYLE_BODY_BOLD, {1, 1, 1, alpha}, btnW, 1, 1, btnW, 1);
            if (overlay_ == OVERLAY_NONE) RegisterHit(tx, btnY, btnW, 34.0f, HIT_SAVE_ACTION, 0);

            const float dx = tx + btnW + 8.0f;
            DrawPanel(dx, btnY, btnW, 34.0f, 9.0f, 1.2f,
                      {0.84f, 0.22f, 0.24f, 0.90f * alpha}, {0.66f, 0.14f, 0.16f, 0.94f * alpha},
                      {1.0f, 1.0f, 1.0f, 0.4f * alpha});
            const std::string dTxt = TextOr("dcui_mc_delete", "Delete");
            MeasureText(dTxt, 13.5f, STYLE_BODY_BOLD, btnW, 1, bw, bh, 1);
            DrawText(dTxt, dx, btnY + (34.0f - bh) * 0.5f, 13.5f, STYLE_BODY_BOLD, {1, 1, 1, alpha}, btnW, 1, 1, btnW, 1);
            if (overlay_ == OVERLAY_NONE) RegisterHit(dx, btnY, btnW, 34.0f, HIT_SAVE_ACTION, 1);
        } else {
            const float prevH = std::clamp(inspH * 0.44f, 110.0f, 185.0f);
            const float prevW = inspW - pad * 2.0f;
            DrawPanel(inspX + pad, inspY + pad, prevW, prevH, 12.0f, 1.0f,
                      PanelTopColor(0.75f * alpha), PanelBotColor(0.88f * alpha), PanelBorderColor(0.6f * alpha));
            const auto& img = GetOrCreateImage(sv.previewPath, 384);
            if (img.tex) {
                DrawImageAspectFit(img, inspX + pad + 4.0f, inspY + pad + 4.0f, prevW - 8.0f, prevH - 8.0f, alpha * ImageAlpha(img));
            } else {
                DrawShaderIcon(ICON_SAVE_CRYSTAL, inspX + pad + prevW * 0.5f, inspY + pad + prevH * 0.5f,
                               prevH * 0.36f, spin_angle_, alpha, SecondaryAccentColor(1.0f));
            }
            const float ty = inspY + pad + prevH + 10.0f;
            const float tw = inspW - pad * 2.0f;
            DrawText(sv.title, inspX + pad, ty, 17.0f, STYLE_ORB_TITLE, PrimaryTextColor(alpha), tw, 2, 0);
            DrawText(sv.slotLabel + " · " + sv.platformLabel, inspX + pad, ty + 46.0f, 13.5f, STYLE_BODY_BOLD,
                     SwirlAccentColor(alpha), tw, 1, 0);
            DrawText(sv.dateStr + (sv.sizeStr.empty() ? "" : (" · " + sv.sizeStr)), inspX + pad, ty + 68.0f,
                     13.0f, STYLE_MONO, SecondaryTextColor(alpha), tw, 1, 0);

            const float btnY = inspY + inspH - pad - 36.0f;
            const float gap = 8.0f;
            const float btnW = (tw - gap * 2.0f) / 3.0f;
            const std::string labels[3] = {
                TextOr("dcui_mc_load", "Load"),
                TextOr("dcui_mc_delete", "Delete"),
                TextOr("dcui_mc_exit", "Back"),
            };
            for (int b = 0; b < 3; ++b) {
                const float bx = inspX + pad + static_cast<float>(b) * (btnW + gap);
                const bool foc = (file_focus_pane_ == 1 && file_action_idx_ == b);
                RgbaColor topC = PanelTopColor(0.88f * alpha);
                RgbaColor botC = PanelBotColor(0.94f * alpha);
                RgbaColor txtC = PrimaryTextColor(alpha);
                int sMode = DefaultShadowMode();
                if (b == 0) {
                    const RgbaColor ac = SwirlAccentColor(1.0f);
                    topC = {ac.r, ac.g, ac.b, 0.94f * alpha};
                    botC = {ac.r * 0.78f, ac.g * 0.78f, ac.b * 0.78f, 0.98f * alpha};
                    txtC = {1, 1, 1, alpha};
                    sMode = 1;
                } else if (b == 1) {
                    topC = {0.84f, 0.22f, 0.24f, 0.90f * alpha};
                    botC = {0.66f, 0.14f, 0.16f, 0.95f * alpha};
                    txtC = {1, 1, 1, alpha};
                    sMode = 1;
                }
                DrawPanel(bx, btnY, btnW, 36.0f, 10.0f, foc ? 2.2f : 1.0f,
                          topC, botC, foc ? RgbaColor{1, 1, 1, alpha} : PanelBorderColor(0.75f * alpha));
                float bw = 0.0f, bh = 0.0f;
                MeasureText(labels[b], 13.5f, STYLE_BODY_BOLD, btnW - 6.0f, 1, bw, bh, sMode);
                DrawText(labels[b], bx, btnY + (36.0f - bh) * 0.5f, 13.5f, STYLE_BODY_BOLD,
                         txtC, btnW - 6.0f, 1, 1, btnW, sMode);
                if (overlay_ == OVERLAY_NONE) RegisterHit(bx, btnY, btnW, 36.0f, HIT_SAVE_ACTION, b);
            }
        }
    } else {
        const std::string emptyMsg = saves_loading_
            ? TextOr("dcui_loading_saves", "Loading quick saves...")
            : TextOr("dcui_mc_no_saves", "No quick saves yet.");
        float ew = 0.0f, eh = 0.0f;
        MeasureText(emptyMsg, 15.5f, STYLE_BODY_BOLD, inspW - 28.0f, 2, ew, eh);
        DrawText(emptyMsg, inspX + 14.0f, inspY + (inspH - eh) * 0.5f, 15.5f, STYLE_BODY_BOLD,
                 SecondaryTextColor(alpha), inspW - 28.0f, 2, 1, inspW - 28.0f);
    }

    // Quick-Save Slot Grid
    const float gap = 8.0f;
    const float slotW = (gridW - gap * static_cast<float>(cols - 1)) / static_cast<float>(cols);
    const float slotH = (gridH - gap * static_cast<float>(rows - 1)) / static_cast<float>(rows);

    for (int s = 0; s < perPage; ++s) {
        const int r = s / cols;
        const int c = s % cols;
        const float sx = gridX + static_cast<float>(c) * (slotW + gap);
        const float sy = gridY + static_cast<float>(r) * (slotH + gap);
        const int fIdx = save_page_ * perPage + s;
        const bool exists = (fIdx < totalFiltered);
        const bool sel = (file_focus_pane_ == 0 && save_slot_index_ == s);

        DrawPanel(sx, sy, slotW, slotH, 12.0f, sel ? 2.0f : 1.0f,
                  PanelTopColor((sel ? 0.94f : (exists ? 0.82f : 0.52f)) * alpha),
                  PanelBotColor((sel ? 0.98f : (exists ? 0.90f : 0.60f)) * alpha),
                  sel ? SelectedBorderColor(alpha) : PanelBorderColor((exists ? 0.72f : 0.38f) * alpha));

        if (exists) {
            const SaveEntry& sv = saves_[filtered[fIdx]];
            const float padS = 5.0f;
            const float imgH = std::max(28.0f, slotH - 28.0f);
            const auto& img = GetOrCreateImage(sv.previewPath, 256);
            if (img.tex) {
                DrawImageAspectFit(img, sx + padS, sy + padS, slotW - padS * 2.0f, imgH, alpha * ImageAlpha(img), true);
            } else {
                DrawShaderIcon(ICON_SAVE_CRYSTAL, sx + slotW * 0.5f, sy + padS + imgH * 0.5f,
                               std::min(slotW, imgH) * 0.36f, sel ? spin_angle_ : 0.0f, alpha, SecondaryAccentColor(1.0f));
            }
            DrawText(sv.slotLabel, sx + 6.0f, sy + slotH - 21.0f, 11.5f, STYLE_BODY_BOLD,
                     PrimaryTextColor(alpha), slotW - 12.0f, 1, 1, slotW - 12.0f);
        } else {
            DrawText(TextOr("dcui_mc_empty_block", "Empty"), sx + 4.0f, sy + (slotH - 14.0f) * 0.5f,
                     11.5f, STYLE_BODY, SecondaryTextColor(0.55f * alpha), slotW - 8.0f, 1, 1, slotW - 8.0f);
        }
        if (overlay_ == OVERLAY_NONE) RegisterHit(sx, sy, slotW, slotH, HIT_SAVE_SLOT, s);
    }

    // Page Controls under Grid
    const float pgY = gridY + gridH + 8.0f;
    const float pgH = 30.0f;
    char pgBuf[32];
    std::snprintf(pgBuf, sizeof(pgBuf), "%d / %d", save_page_ + 1, totalPages);
    float pw = 0.0f, ph = 0.0f;
    MeasureText(pgBuf, 13.5f, STYLE_MONO, 100.0f, 1, pw, ph);
    const float btnW = 46.0f;
    const float groupW = btnW * 2.0f + pw + 28.0f;
    const float gx = gridX + (gridW - groupW) * 0.5f;

    DrawPanel(gx, pgY, btnW, pgH, 10.0f, 1.0f,
              PanelTopColor(0.84f * alpha), PanelBotColor(0.92f * alpha), PanelBorderColor(0.75f * alpha));
    DrawText("<", gx, pgY + 6.0f, 14.0f, STYLE_MONO, PrimaryTextColor(save_page_ > 0 ? alpha : 0.35f * alpha), btnW, 1, 1, btnW);
    if (overlay_ == OVERLAY_NONE) RegisterHit(gx, pgY, btnW, pgH, HIT_SAVE_PAGE_PREV, 0);

    DrawText(pgBuf, gx + btnW + 14.0f, pgY + (pgH - ph) * 0.5f, 13.5f, STYLE_MONO,
             PrimaryTextColor(alpha), pw + 4.0f, 1, 0);

    const float nx = gx + btnW + pw + 28.0f;
    DrawPanel(nx, pgY, btnW, pgH, 10.0f, 1.0f,
              PanelTopColor(0.84f * alpha), PanelBotColor(0.92f * alpha), PanelBorderColor(0.75f * alpha));
    DrawText(">", nx, pgY + 6.0f, 14.0f, STYLE_MONO, PrimaryTextColor(save_page_ < totalPages - 1 ? alpha : 0.35f * alpha), btnW, 1, 1, btnW);
    if (overlay_ == OVERLAY_NONE) RegisterHit(nx, pgY, btnW, pgH, HIT_SAVE_PAGE_NEXT, 0);
}

void DcRenderer::DrawVmuCardsTab(const LayoutMetrics& m, float topY, float botY, float alpha) {
    const float contentH = std::max(140.0f, botY - topY);

    float dockX = m.padX;
    float dockY = topY;
    float dockW = m.bodyW;
    float dockH = contentH;

    float filesX = m.padX;
    float filesY = topY;
    float filesW = m.bodyW;
    float filesH = contentH;

    if (!m.portrait) {
        dockW = m.bodyW * 0.38f;
        filesX = dockX + dockW + 14.0f;
        filesW = m.padX + m.bodyW - filesX;
    } else {
        dockH = std::min(212.0f, contentH * 0.42f);
        filesY = topY + dockH + 10.0f;
        filesH = std::max(120.0f, botY - filesY);
    }

    // Left/Top Panel: VMU Unit LCD + Card Slot Selector + Create / Format
    DrawPanel(dockX, dockY, dockW, dockH, 18.0f, 1.4f,
              PanelTopColor(0.90f * alpha), PanelBotColor(0.95f * alpha), PanelBorderColor(0.80f * alpha));

    const float pad = 12.0f;
    const bool hasCards = !vmu_cards_.empty();
    const VmuCardEntry emptyCard;
    const VmuCardEntry& activeCard = hasCards ? vmu_cards_[vmu_card_index_] : emptyCard;

    // Sculpted VMU LCD Status Banner
    const float lcdH = m.portrait ? 54.0f : 72.0f;
    DrawPanel(dockX + pad, dockY + pad, dockW - pad * 2.0f, lcdH, 12.0f, 1.4f,
              {0.58f, 0.78f, 0.67f, 0.95f * alpha},
              {0.46f, 0.68f, 0.56f, 0.98f * alpha},
              {0.18f, 0.34f, 0.28f, 0.90f * alpha});

    if (hasCards) {
        char lcdTop[64];
        std::snprintf(lcdTop, sizeof(lcdTop), "%s · %s",
                      activeCard.portName.c_str(),
                      activeCard.formatted ? TextOr("dcui_vmu_formatted", "FORMATTED").c_str()
                                           : TextOr("dcui_vmu_unformatted", "UNFORMATTED").c_str());
        char lcdBot[64];
        std::snprintf(lcdBot, sizeof(lcdBot), "%d / %d %s",
                      activeCard.freeBlocks, activeCard.totalBlocks,
                      TextOr("dcui_vmu_blocks_free", "FREE BLOCKS").c_str());
        DrawText(lcdTop, dockX + pad + 12.0f, dockY + pad + 9.0f, 13.5f, STYLE_MONO,
                 {0.06f, 0.18f, 0.14f, alpha}, dockW - pad * 2.0f - 24.0f, 1, 0, 0.0f, 0);
        DrawText(lcdBot, dockX + pad + 12.0f, dockY + pad + (m.portrait ? 29.0f : 36.0f), 14.5f, STYLE_MONO,
                 {0.06f, 0.18f, 0.14f, alpha}, dockW - pad * 2.0f - 24.0f, 1, 0, 0.0f, 0);
    } else {
        DrawText(TextOr("dcui_vmu_none", "NO VMU DETECTED"), dockX + pad + 12.0f, dockY + pad + 18.0f,
                 14.5f, STYLE_MONO, {0.06f, 0.18f, 0.14f, alpha}, dockW - pad * 2.0f - 24.0f, 1, 1,
                 dockW - pad * 2.0f - 24.0f, 0);
    }

    // Card Selector Grid (A1..D2)
    const float cardsTop = dockY + pad + lcdH + 10.0f;
    const float btnBotH = 34.0f;
    const float cardsAvailH = std::max(40.0f, (dockY + dockH - pad - btnBotH - 8.0f) - cardsTop);
    if (hasCards) {
        const int cardCols = m.portrait ? 4 : 2;
        const int numCards = std::min(8, static_cast<int>(vmu_cards_.size()));
        const int cardRows = std::max(1, (numCards + cardCols - 1) / cardCols);
        const float cg = 6.0f;
        const float cw = (dockW - pad * 2.0f - cg * static_cast<float>(cardCols - 1)) / static_cast<float>(cardCols);
        const float ch = std::min(38.0f, (cardsAvailH - cg * static_cast<float>(cardRows - 1)) / static_cast<float>(cardRows));

        for (int i = 0; i < numCards; ++i) {
            const int r = i / cardCols;
            const int c = i % cardCols;
            const float cx = dockX + pad + static_cast<float>(c) * (cw + cg);
            const float cy = cardsTop + static_cast<float>(r) * (ch + cg);
            const bool sel = (i == vmu_card_index_);
            const RgbaColor ac = SwirlAccentColor(1.0f);
            DrawPanel(cx, cy, cw, ch, 9.0f, sel ? 1.8f : 1.0f,
                      sel ? RgbaColor{ac.r, ac.g, ac.b, 0.94f * alpha} : PanelTopColor(0.84f * alpha),
                      sel ? RgbaColor{ac.r * 0.8f, ac.g * 0.8f, ac.b * 0.8f, 0.98f * alpha} : PanelBotColor(0.92f * alpha),
                      sel ? RgbaColor{1, 1, 1, 0.65f * alpha} : PanelBorderColor(0.68f * alpha));

            char cLbl[32];
            std::snprintf(cLbl, sizeof(cLbl), "%s (%d)", vmu_cards_[i].portName.c_str(), vmu_cards_[i].freeBlocks);
            float bw = 0.0f, bh = 0.0f;
            const int sMode = sel ? 1 : DefaultShadowMode();
            MeasureText(cLbl, 12.0f, STYLE_MONO, cw - 8.0f, 1, bw, bh, sMode);
            DrawText(cLbl, cx, cy + (ch - bh) * 0.5f, 12.0f, STYLE_MONO,
                     sel ? RgbaColor{1, 1, 1, alpha} : PrimaryTextColor(alpha), cw - 8.0f, 1, 1, cw, sMode);
            if (overlay_ == OVERLAY_NONE) RegisterHit(cx, cy, cw, ch, HIT_VMU_CARD, i);
        }
    }

    // Bottom Dock Buttons: Create VMUs + Format Card
    const float bY = dockY + dockH - pad - btnBotH;
    const float bGap = 8.0f;
    const float bW = (dockW - pad * 2.0f - bGap) * 0.5f;
    const RgbaColor secAc = SecondaryAccentColor(1.0f);
    DrawPanel(dockX + pad, bY, bW, btnBotH, 9.0f, 1.1f,
              {secAc.r, secAc.g, secAc.b, 0.92f * alpha}, {secAc.r * 0.78f, secAc.g * 0.78f, secAc.b * 0.78f, 0.96f * alpha},
              {1, 1, 1, 0.45f * alpha});
    float tw = 0.0f, th = 0.0f;
    const std::string crTxt = TextOr("dcui_vmu_create_btn", "Create VMUs");
    MeasureText(crTxt, 12.5f, STYLE_BODY_BOLD, bW - 6.0f, 1, tw, th, 1);
    DrawText(crTxt, dockX + pad, bY + (btnBotH - th) * 0.5f, 12.5f, STYLE_BODY_BOLD,
             {1, 1, 1, alpha}, bW - 6.0f, 1, 1, bW, 1);
    if (overlay_ == OVERLAY_NONE) RegisterHit(dockX + pad, bY, bW, btnBotH, HIT_VMU_ACTION, 0);

    const float fx = dockX + pad + bW + bGap;
    DrawPanel(fx, bY, bW, btnBotH, 9.0f, 1.1f,
              PanelTopColor(hasCards ? 0.88f * alpha : 0.45f * alpha),
              PanelBotColor(hasCards ? 0.94f * alpha : 0.50f * alpha),
              PanelBorderColor(0.75f * alpha));
    const std::string fmTxt = TextOr("dcui_vmu_format_btn", "Format");
    MeasureText(fmTxt, 12.5f, STYLE_BODY_BOLD, bW - 6.0f, 1, tw, th);
    DrawText(fmTxt, fx, bY + (btnBotH - th) * 0.5f, 12.5f, STYLE_BODY_BOLD,
             PrimaryTextColor(hasCards ? alpha : 0.40f * alpha), bW - 6.0f, 1, 1, bW);
    if (hasCards && overlay_ == OVERLAY_NONE) RegisterHit(fx, bY, bW, btnBotH, HIT_VMU_ACTION, 1);

    // Right/Bottom Panel: VMU Files List
    DrawPanel(filesX, filesY, filesW, filesH, 18.0f, 1.4f,
              PanelTopColor(0.90f * alpha), PanelBotColor(0.95f * alpha), PanelBorderColor(0.80f * alpha));

    const float listHeaderH = 38.0f;
    if (hasCards) {
        char hdr[128];
        std::snprintf(hdr, sizeof(hdr), "%s · %d %s",
                      activeCard.fileName.c_str(),
                      static_cast<int>(vmu_files_.size()),
                      TextOr("dcui_vmu_files_word", "Files").c_str());
        DrawText(hdr, filesX + 14.0f, filesY + 11.0f, 14.5f, STYLE_BODY_BOLD,
                 PrimaryTextColor(alpha), filesW - 28.0f, 1, 0);
    }

    const float innerTop = filesY + listHeaderH;
    const float innerH = std::max(60.0f, filesH - listHeaderH - 12.0f);

    if (!hasCards) {
        DrawText(TextOr("dcui_vmu_empty_hint", "Tap Create VMUs to initialize shared Dreamcast memory cards."),
                 filesX + 18.0f, innerTop + innerH * 0.42f, 14.5f, STYLE_BODY_BOLD,
                 SecondaryTextColor(alpha), filesW - 36.0f, 2, 1, filesW - 36.0f);
        return;
    }

    if (vmu_loading_) {
        DrawText(TextOr("dcui_vmu_loading", "Reading VMU blocks..."),
                 filesX + 18.0f, innerTop + innerH * 0.44f, 15.0f, STYLE_BODY_BOLD,
                 SecondaryTextColor(alpha), filesW - 36.0f, 1, 1, filesW - 36.0f);
        return;
    }

    if (!activeCard.formatted) {
        DrawText(TextOr("dcui_vmu_need_format", "This VMU is unformatted. Format it to initialize 200 blocks."),
                 filesX + 18.0f, innerTop + innerH * 0.40f, 14.5f, STYLE_BODY_BOLD,
                 SecondaryTextColor(alpha), filesW - 36.0f, 2, 1, filesW - 36.0f);
        return;
    }

    if (vmu_files_.empty()) {
        DrawText(TextOr("dcui_vmu_no_files", "No saved files on this VMU card."),
                 filesX + 18.0f, innerTop + innerH * 0.44f, 14.5f, STYLE_BODY_BOLD,
                 SecondaryTextColor(alpha), filesW - 36.0f, 2, 1, filesW - 36.0f);
        return;
    }

    const float fRowH = 56.0f;
    const float fStep = 62.0f;
    const int numFiles = static_cast<int>(vmu_files_.size());
    const float maxFScroll = std::max(0.0f, static_cast<float>(numFiles) * fStep - innerH + 4.0f);
    vmu_files_scroll_y_ = std::clamp(vmu_files_scroll_y_, 0.0f, maxFScroll);

    {
        FlushQuadBatch();
        ScopedScissor scissor(view_x_, view_y_, view_width_, view_height_,
                              screen_width_, screen_height_, filesX + 8.0f, innerTop, filesW - 16.0f, innerH);
        for (int i = 0; i < numFiles; ++i) {
            const float fy = innerTop + static_cast<float>(i) * fStep - vmu_files_scroll_y_;
            if (fy + fRowH < innerTop - 4.0f || fy > innerTop + innerH + 4.0f) continue;

            const VmuFileEntry& vf = vmu_files_[i];
            const bool sel = (i == vmu_file_index_);
            DrawPanel(filesX + 10.0f, fy, filesW - 20.0f, fRowH, 11.0f, sel ? 1.8f : 1.0f,
                      PanelTopColor((sel ? 0.95f : 0.80f) * alpha),
                      PanelBotColor((sel ? 0.98f : 0.88f) * alpha),
                      sel ? SelectedBorderColor(alpha) : PanelBorderColor(0.65f * alpha));

            const float iconS = 40.0f;
            const float iconX = filesX + 18.0f;
            const float iconY = fy + (fRowH - iconS) * 0.5f;
            const auto& img = GetOrCreateImage(vf.iconPath, 128);
            if (img.tex) {
                DrawImageAspectFit(img, iconX, iconY, iconS, iconS, alpha * ImageAlpha(img));
            } else {
                DrawShaderIcon(ICON_VMU, iconX + iconS * 0.5f, iconY + iconS * 0.5f, iconS * 0.44f,
                               0.0f, alpha, SwirlAccentColor(1.0f));
            }

            // Delete button on right of VMU file row
            const float delW = 68.0f;
            const float delH = 30.0f;
            const float delX = filesX + filesW - 18.0f - delW;
            const float delY = fy + (fRowH - delH) * 0.5f;
            DrawPanel(delX, delY, delW, delH, 8.0f, 1.0f,
                      {0.84f, 0.22f, 0.24f, 0.90f * alpha}, {0.66f, 0.14f, 0.16f, 0.95f * alpha},
                      {1, 1, 1, 0.4f * alpha});
            float dw = 0.0f, dh = 0.0f;
            const std::string delTxt = TextOr("dcui_mc_delete", "Delete");
            MeasureText(delTxt, 12.0f, STYLE_BODY_BOLD, delW - 4.0f, 1, dw, dh, 1);
            DrawText(delTxt, delX, delY + (delH - dh) * 0.5f, 12.0f, STYLE_BODY_BOLD,
                     {1, 1, 1, alpha}, delW - 4.0f, 1, 1, delW, 1);

            const float tx = iconX + iconS + 10.0f;
            const float maxTW = std::max(60.0f, delX - tx - 8.0f);
            DrawText(vf.description.empty() ? vf.fileName : vf.description, tx, fy + 8.0f,
                     14.5f, STYLE_BODY_BOLD, PrimaryTextColor(alpha), maxTW, 1, 0);

            char meta[64];
            std::snprintf(meta, sizeof(meta), "%s · %d BLK · %s", vf.fileName.c_str(), vf.blocks, vf.dateStr.c_str());
            DrawText(meta, tx, fy + 31.0f, 12.0f, STYLE_MONO, SecondaryTextColor(alpha), maxTW, 1, 0);

            if (overlay_ == OVERLAY_NONE && fy + fRowH >= innerTop && fy <= innerTop + innerH) {
                RegisterHit(filesX + 10.0f, fy, delX - (filesX + 10.0f), fRowH, HIT_VMU_FILE, i);
                RegisterHit(delX, delY, delW, delH, HIT_VMU_ACTION, 100 + i); // >=100 encodes delete file index
            }
        }
        FlushQuadBatch();
    }
}

void DcRenderer::DrawFileScreen(const LayoutMetrics& m, float alpha) {
    DrawTopBar(m, TextOr("dcui_menu_file_mgr", "File & VMU Manager"), false, alpha);

    // 3 Top Tabs: All Quick Saves | Game Saves | VMU Cards
    const float tabY = m.bodyTop;
    const float tabH = 38.0f;
    const float gap = 8.0f;
    const float tabW = (m.bodyW - gap * 2.0f) / 3.0f;
    const std::string tabNames[3] = {
        TextOr("dcui_mc_tab_all", "Quick Saves"),
        TextOr("dcui_mc_tab_game", "Game Saves"),
        TextOr("dcui_mc_tab_vmu", "VMU Cards"),
    };
    const RgbaColor ac = SwirlAccentColor(1.0f);
    for (int i = 0; i < 3; ++i) {
        const float tx = m.padX + static_cast<float>(i) * (tabW + gap);
        const bool sel = (i == file_tab_);
        DrawPanel(tx, tabY, tabW, tabH, 14.0f, sel ? 1.6f : 1.0f,
                  sel ? RgbaColor{ac.r, ac.g, ac.b, 0.94f * alpha} : PanelTopColor(0.82f * alpha),
                  sel ? RgbaColor{ac.r * 0.80f, ac.g * 0.80f, ac.b * 0.80f, 0.98f * alpha} : PanelBotColor(0.90f * alpha),
                  sel ? RgbaColor{1, 1, 1, 0.55f * alpha} : PanelBorderColor(0.70f * alpha));
        float lw = 0.0f, lh = 0.0f;
        const int sMode = sel ? 1 : DefaultShadowMode();
        MeasureText(tabNames[i], 13.5f, STYLE_BODY_BOLD, tabW - 12.0f, 1, lw, lh, sMode);
        DrawText(tabNames[i], tx, tabY + (tabH - lh) * 0.5f, 13.5f, STYLE_BODY_BOLD,
                 sel ? RgbaColor{1, 1, 1, alpha} : PrimaryTextColor(alpha), tabW - 12.0f, 1, 1, tabW, sMode);
        if (overlay_ == OVERLAY_NONE) RegisterHit(tx, tabY, tabW, tabH, HIT_FILE_TAB, i);
    }

    const float subTop = tabY + tabH + 10.0f;
    if (file_tab_ == 2) {
        DrawVmuCardsTab(m, subTop, m.bodyBot, alpha);
    } else {
        DrawQuickSavesTab(m, subTop, m.bodyBot, alpha);
    }
}

void DcRenderer::DrawSettingsScreen(const LayoutMetrics& m, float alpha) {
    DrawTopBar(m, TextOr("dcui_menu_settings", "Settings"), false, alpha);

    const std::string themes[5] = {
        TextOr("dcui_theme_pearl", "Dreamcast Pearl"),
        TextOr("dcui_theme_pal_blue", "Dreamcast PAL Blue"),
        TextOr("dcui_theme_sports", "Sega Sports Black"),
        TextOr("dcui_theme_katana", "Katana Devkit"),
        TextOr("dcui_theme_saturn", "Saturn Midnight"),
    };
    const std::string aspects[4] = {
        TextOr("dcui_aspect_auto", "Auto Full Screen"),
        TextOr("dcui_aspect_16_9", "16:9"),
        TextOr("dcui_aspect_16_10", "16:10"),
        TextOr("dcui_aspect_4_3", "4:3"),
    };
    const std::string motions[4] = {
        TextOr("dcui_motion_smooth", "Smooth (60Hz)"),
        TextOr("dcui_motion_calm", "Calm"),
        TextOr("dcui_motion_eco", "Power Saver (30Hz)"),
        TextOr("dcui_motion_static", "Static"),
    };
    const std::string scans[3] = {
        TextOr("dcui_off", "Off"),
        TextOr("dcui_scan_vga", "Subtle VGA"),
        TextOr("dcui_scan_crt", "Classic CRT"),
    };
    const std::string boots[3] = {
        TextOr("dcui_boot_full", "Full"),
        TextOr("dcui_boot_fast", "Fast"),
        TextOr("dcui_off", "Off"),
    };
    const std::string dates[3] = {"DD/MM/YYYY", "MM/DD/YYYY", "YYYY/MM/DD"};

    const std::string onTxt = TextOr("dcui_on", "On");
    const std::string offTxt = TextOr("dcui_off", "Off");

    struct SettingRowData {
        std::string label;
        std::string value;
        bool hasLeftRight;
    };

    const SettingRowData rows[kSettingsCount] = {
        {TextOr("dcui_set_theme", "Theme"), themes[std::clamp(settings_.theme, 0, 4)], true},
        {TextOr("dcui_set_aspect", "Aspect Ratio"), aspects[std::clamp(settings_.aspectMode, 0, 3)], true},
        {TextOr("dcui_set_motion", "Motion & Performance"), motions[std::clamp(settings_.motionMode, 0, 3)], true},
        {TextOr("dcui_set_scanlines", "Display Filter"), scans[std::clamp(settings_.scanlinesMode, 0, 2)], true},
        {TextOr("dcui_set_boot_anim", "Boot Animation"), boots[std::clamp(settings_.bootAnimMode, 0, 2)], true},
        {TextOr("dcui_set_key_tone", "Menu Sound Effects"), settings_.keyTone ? onTxt : offTxt, true},
        {TextOr("dcui_set_ambient", "Ambient Sound"), settings_.ambientSound ? onTxt : offTxt, true},
        {TextOr("dcui_set_confirm_btn", "Confirm Button"), settings_.swapConfirm ? "B" : "A", true},
        {TextOr("dcui_set_touch", "Touch Controls"), settings_.touchControls ? onTxt : offTxt, true},
        {TextOr("dcui_set_touch_guide", "Touch Guide"), TextOr("dcui_open", "Open"), false},
        {TextOr("dcui_set_time_fmt", "Clock Format"), settings_.time24h ? TextOr("dcui_time_24h", "24-Hour") : TextOr("dcui_time_12h", "12-Hour"), true},
        {TextOr("dcui_set_date_fmt", "Date Format"), dates[std::clamp(settings_.dateFormat, 0, 2)], true},
        {TextOr("dcui_set_keep_screen", "Keep Screen On"), settings_.keepScreenOn ? onTxt : offTxt, true},
        {TextOr("dcui_set_sysinfo", "System & BIOS Info"), TextOr("dcui_view", "View"), false},
        {TextOr("dcui_set_restore", "Restore Defaults"), TextOr("dcui_reset", "Reset"), false},
    };

    const float viewX = m.padX;
    const float viewY = m.bodyTop;
    const float viewW = m.bodyW;
    const float viewH = m.bodyH;
    const float rowH = 48.0f;
    const float rowStep = 55.0f;
    const float maxScroll = std::max(0.0f, static_cast<float>(kSettingsCount) * rowStep - viewH + 4.0f);
    settings_scroll_target_y_ = std::clamp(settings_scroll_target_y_, 0.0f, maxScroll);
    settings_scroll_y_ = std::clamp(settings_scroll_y_, 0.0f, maxScroll);

    {
        FlushQuadBatch();
        ScopedScissor scissor(view_x_, view_y_, view_width_, view_height_,
                              screen_width_, screen_height_, viewX, viewY, viewW, viewH);
        const RgbaColor ac = SwirlAccentColor(1.0f);

        for (int i = 0; i < kSettingsCount; ++i) {
            const float ry = viewY + static_cast<float>(i) * rowStep - settings_scroll_y_;
            if (ry + rowH < viewY - 4.0f || ry > viewY + viewH + 4.0f) continue;

            const bool sel = (i == settings_index_);
            DrawPanel(viewX, ry, viewW, rowH, 13.0f, sel ? 1.8f : 1.0f,
                      PanelTopColor((sel ? 0.95f : 0.80f) * alpha),
                      PanelBotColor((sel ? 0.98f : 0.88f) * alpha),
                      sel ? SelectedBorderColor(alpha) : PanelBorderColor(0.65f * alpha),
                      sel ? RgbaColor{ac.r, ac.g, ac.b, 0.12f} : RgbaColor{0, 0, 0, 0});

            const float valBoxW = m.portrait ? 168.0f : 230.0f;
            const float lblMaxW = std::max(80.0f, viewW - valBoxW - 36.0f);
            float lw = 0.0f, lh = 0.0f;
            MeasureText(rows[i].label, 15.0f, STYLE_BODY_BOLD, lblMaxW, 1, lw, lh);
            DrawText(rows[i].label, viewX + 16.0f, ry + (rowH - lh) * 0.5f, 15.0f, STYLE_BODY_BOLD,
                     PrimaryTextColor(alpha), lblMaxW, 1, 0);

            const float vx = viewX + viewW - valBoxW - 10.0f;
            if (rows[i].hasLeftRight) {
                const float arrowW = 30.0f;
                const float midW = valBoxW - arrowW * 2.0f;
                DrawText("<", vx, ry + 13.0f, 15.0f, STYLE_MONO, SecondaryAccentColor(alpha), arrowW, 1, 1, arrowW);
                float vw = 0.0f, vh = 0.0f;
                MeasureText(rows[i].value, 14.0f, STYLE_BODY_BOLD, midW, 1, vw, vh);
                DrawText(rows[i].value, vx + arrowW, ry + (rowH - vh) * 0.5f, 14.0f, STYLE_BODY_BOLD,
                         sel ? SwirlAccentColor(alpha) : PrimaryTextColor(alpha), midW, 1, 1, midW);
                DrawText(">", vx + arrowW + midW, ry + 13.0f, 15.0f, STYLE_MONO, SecondaryAccentColor(alpha), arrowW, 1, 1, arrowW);
                if (overlay_ == OVERLAY_NONE && ry + rowH >= viewY && ry <= viewY + viewH) {
                    RegisterHit(viewX, ry, vx - viewX, rowH, HIT_SETTING_ROW, i);
                    RegisterHit(vx, ry, arrowW + midW * 0.45f, rowH, HIT_SETTING_LEFT, i);
                    RegisterHit(vx + arrowW + midW * 0.45f, ry, arrowW + midW * 0.55f, rowH, HIT_SETTING_RIGHT, i);
                }
            } else {
                const float pillW = std::min(126.0f, valBoxW - 12.0f);
                const float px = viewX + viewW - pillW - 12.0f;
                const float py = ry + (rowH - 32.0f) * 0.5f;
                DrawPanel(px, py, pillW, 32.0f, 9.0f, 1.0f,
                          {ac.r, ac.g, ac.b, 0.90f * alpha}, {ac.r * 0.78f, ac.g * 0.78f, ac.b * 0.78f, 0.95f * alpha},
                          {1, 1, 1, 0.4f * alpha});
                float vw = 0.0f, vh = 0.0f;
                MeasureText(rows[i].value, 13.5f, STYLE_BODY_BOLD, pillW - 8.0f, 1, vw, vh, 1);
                DrawText(rows[i].value, px, py + (32.0f - vh) * 0.5f, 13.5f, STYLE_BODY_BOLD,
                         {1, 1, 1, alpha}, pillW - 8.0f, 1, 1, pillW, 1);
                if (overlay_ == OVERLAY_NONE && ry + rowH >= viewY && ry <= viewY + viewH) {
                    RegisterHit(viewX, ry, viewW, rowH, HIT_SETTING_ROW, i);
                }
            }
        }
        FlushQuadBatch();
    }
}

void DcRenderer::DrawLaunchStage() {
    const float t = Clamp01(launch_t_);
    const float cx = screen_width_ * 0.5f;
    const float cy = screen_height_ * 0.44f;
    const float zoom = EaseOutCubic(t);
    const float fadeOut = (t > 0.78f) ? (1.0f - SmoothStep(0.78f, 1.0f, t)) : 1.0f;
    const float a = SmoothStep(0.0f, 0.16f, t) * fadeOut;

    const float artSize = 150.0f + zoom * 38.0f;
    const RgbaColor ac = SwirlAccentColor(1.0f);
    DrawSwirl(cx, cy, artSize * 0.85f, 1.0f, -t * 3.8f, a * 0.42f, ac);

    const auto& img = GetOrCreateImage(launch_cover_, 320);
    if (img.tex) {
        DrawImageAspectFit(img, cx - artSize * 0.5f, cy - artSize * 0.5f, artSize, artSize, a * ImageAlpha(img));
    } else {
        DrawShaderIcon(ShaderIconForPlatform(launch_platform_id_), cx, cy, artSize * 0.48f, spin_angle_ * 3.2f, a, ac);
    }

    const std::string lbl = launch_is_save_
        ? TextOr("dcui_launching_save", "Loading Quick Save...")
        : TextOr("dcui_launching_game", "Launching...");
    float lw = 0.0f, lh = 0.0f;
    MeasureText(lbl, 19.0f, STYLE_ORB_TITLE, 520.0f, 1, lw, lh);
    DrawText(lbl, cx - lw * 0.5f, cy + artSize * 0.5f + 22.0f, 19.0f, STYLE_ORB_TITLE,
             PrimaryTextColor(a), 520.0f, 1, 0);
    if (!launch_title_.empty()) {
        float tw = 0.0f, th = 0.0f;
        MeasureText(launch_title_, 15.0f, STYLE_BODY_BOLD, 560.0f, 1, tw, th);
        DrawText(launch_title_, cx - tw * 0.5f, cy + artSize * 0.5f + 50.0f, 15.0f, STYLE_BODY_BOLD,
                 SecondaryTextColor(a), 560.0f, 1, 0);
    }
}

void DcRenderer::DrawConfirmDialog(
    const LayoutMetrics& m,
    const std::string& title,
    const std::string& message,
    const std::string& yesText,
    const std::string& noText,
    int focusedBtn) {
    const float dlgW = std::min(m.bodyW - 16.0f, 440.0f);
    const float dlgH = 210.0f;
    const float dx = (screen_width_ - dlgW) * 0.5f;
    const float dy = (screen_height_ - dlgH) * 0.5f;

    DrawPanel(dx, dy, dlgW, dlgH, 20.0f, 1.8f,
              PanelTopColor(0.97f), PanelBotColor(0.99f), SelectedBorderColor(0.92f));

    DrawText(title, dx + 20.0f, dy + 22.0f, 20.0f, STYLE_ORB_TITLE,
             PrimaryTextColor(1.0f), dlgW - 40.0f, 1, 1, dlgW - 40.0f);
    DrawText(message, dx + 22.0f, dy + 62.0f, 14.5f, STYLE_BODY,
             SecondaryTextColor(1.0f), dlgW - 44.0f, 3, 1, dlgW - 44.0f);

    const float btnW = (dlgW - 56.0f) * 0.5f;
    const float btnH = 40.0f;
    const float by = dy + dlgH - 58.0f;
    const RgbaColor ac = SwirlAccentColor(1.0f);

    for (int i = 0; i < 2; ++i) {
        const float bx = dx + 20.0f + static_cast<float>(i) * (btnW + 16.0f);
        const bool foc = (focusedBtn == i);
        const std::string& txt = (i == 0) ? yesText : noText;
        DrawPanel(bx, by, btnW, btnH, 11.0f, foc ? 2.0f : 1.0f,
                  foc ? RgbaColor{ac.r, ac.g, ac.b, 0.96f} : PanelTopColor(0.88f),
                  foc ? RgbaColor{ac.r * 0.8f, ac.g * 0.8f, ac.b * 0.8f, 0.98f} : PanelBotColor(0.94f),
                  foc ? RgbaColor{1, 1, 1, 0.7f} : PanelBorderColor(0.75f));
        float bw = 0.0f, bh = 0.0f;
        const int sMode = foc ? 1 : DefaultShadowMode();
        MeasureText(txt, 15.0f, STYLE_BODY_BOLD, btnW - 8.0f, 1, bw, bh, sMode);
        DrawText(txt, bx, by + (btnH - bh) * 0.5f, 15.0f, STYLE_BODY_BOLD,
                 foc ? RgbaColor{1, 1, 1, 1} : PrimaryTextColor(1.0f), btnW - 8.0f, 1, 1, btnW, sMode);
        RegisterHit(bx, by, btnW, btnH, HIT_OVERLAY_ITEM, i);
    }
}

void DcRenderer::DrawOverlay(const LayoutMetrics& m) {
    PushQuad(white_tex_, 0.0f, 0.0f, screen_width_, screen_height_, {0.01f, 0.02f, 0.06f, 0.62f});
    RegisterHit(0.0f, 0.0f, screen_width_, screen_height_, HIT_OVERLAY_BACKDROP, 0);

    if (overlay_ == OVERLAY_EXIT_CONFIRM) {
        DrawConfirmDialog(m, TextOr("dcui_exit_title", "Exit BIOS Mode"),
                          TextOr("dcui_exit_msg", "Return to standard EmuCoreH library?"),
                          TextOr("dcui_yes", "Yes"), TextOr("dcui_no", "No"), dialog_btn_);
    } else if (overlay_ == OVERLAY_DELETE_SAVE_CONFIRM) {
        DrawConfirmDialog(m, TextOr("dcui_del_title", "Delete Quick Save"),
                          TextOr("dcui_del_msg", "Permanently delete this quick save state?"),
                          TextOr("dcui_yes", "Yes"), TextOr("dcui_no", "No"), dialog_btn_);
    } else if (overlay_ == OVERLAY_DELETE_VMU_FILE_CONFIRM) {
        DrawConfirmDialog(m, TextOr("dcui_vmu_del_title", "Delete VMU File"),
                          TextOr("dcui_vmu_del_msg", "Remove this saved file from the VMU card?"),
                          TextOr("dcui_yes", "Yes"), TextOr("dcui_no", "No"), dialog_btn_);
    } else if (overlay_ == OVERLAY_FORMAT_VMU_CONFIRM) {
        DrawConfirmDialog(m, TextOr("dcui_vmu_fmt_title", "Format VMU Card"),
                          TextOr("dcui_vmu_fmt_msg", "Erase all save files on this VMU and initialize 200 free blocks?"),
                          TextOr("dcui_yes", "Yes"), TextOr("dcui_no", "No"), dialog_btn_);
    } else if (overlay_ == OVERLAY_RESTORE_CONFIRM) {
        DrawConfirmDialog(m, TextOr("dcui_restore_title", "Restore Defaults"),
                          TextOr("dcui_restore_msg", "Reset all BIOS mode settings to defaults?"),
                          TextOr("dcui_yes", "Yes"), TextOr("dcui_no", "No"), dialog_btn_);
    } else if (overlay_ == OVERLAY_GAME_OPTIONS) {
        const float dlgW = std::min(m.bodyW - 16.0f, 400.0f);
        const float rowH = 44.0f;
        const float dlgH = 74.0f + 5.0f * 50.0f;
        const float dx = (screen_width_ - dlgW) * 0.5f;
        const float dy = (screen_height_ - dlgH) * 0.5f;
        DrawPanel(dx, dy, dlgW, dlgH, 20.0f, 1.8f, PanelTopColor(0.97f), PanelBotColor(0.99f), SelectedBorderColor(0.9f));
        DrawText(TextOr("dcui_btn_options", "Game Options"), dx + 18.0f, dy + 18.0f, 19.0f, STYLE_ORB_TITLE,
                 PrimaryTextColor(1.0f), dlgW - 36.0f, 1, 1, dlgW - 36.0f);

        const std::string opts[5] = {
            TextOr("dcui_opt_start", "Start Game"),
            TextOr("dcui_opt_continue", "Continue Latest Save"),
            TextOr("dcui_opt_saves", "Game Quick Saves"),
            TextOr("dcui_opt_info", "Game Information"),
            TextOr("dcui_opt_settings", "Per-Game Core Settings"),
        };
        const RgbaColor ac = SwirlAccentColor(1.0f);
        for (int i = 0; i < 5; ++i) {
            const float ry = dy + 56.0f + static_cast<float>(i) * 50.0f;
            const bool foc = (options_index_ == i);
            DrawPanel(dx + 16.0f, ry, dlgW - 32.0f, rowH, 11.0f, foc ? 1.8f : 1.0f,
                      foc ? RgbaColor{ac.r, ac.g, ac.b, 0.94f} : PanelTopColor(0.86f),
                      foc ? RgbaColor{ac.r * 0.8f, ac.g * 0.8f, ac.b * 0.8f, 0.98f} : PanelBotColor(0.92f),
                      foc ? RgbaColor{1, 1, 1, 0.6f} : PanelBorderColor(0.7f));
            float ow = 0.0f, oh = 0.0f;
            const int sMode = foc ? 1 : DefaultShadowMode();
            MeasureText(opts[i], 14.5f, STYLE_BODY_BOLD, dlgW - 48.0f, 1, ow, oh, sMode);
            DrawText(opts[i], dx + 16.0f, ry + (rowH - oh) * 0.5f, 14.5f, STYLE_BODY_BOLD,
                     foc ? RgbaColor{1, 1, 1, 1} : PrimaryTextColor(1.0f), dlgW - 48.0f, 1, 1, dlgW - 32.0f, sMode);
            RegisterHit(dx + 16.0f, ry, dlgW - 32.0f, rowH, HIT_OVERLAY_ITEM, i);
        }
    } else if (overlay_ == OVERLAY_DISC_PICKER) {
        const int absIdx = ActiveTabAbsGameIndex(game_index_);
        if (absIdx < 0 || absIdx >= static_cast<int>(games_.size())) {
            overlay_ = OVERLAY_NONE;
            return;
        }
        const GameEntry& g = games_[absIdx];
        const int nDiscs = std::max(1, static_cast<int>(g.discLabels.size()));
        const float dlgW = std::min(m.bodyW - 16.0f, 380.0f);
        const float dlgH = std::min(m.bodyH, 74.0f + static_cast<float>(std::min(nDiscs, 5)) * 50.0f);
        const float dx = (screen_width_ - dlgW) * 0.5f;
        const float dy = (screen_height_ - dlgH) * 0.5f;
        DrawPanel(dx, dy, dlgW, dlgH, 20.0f, 1.8f, PanelTopColor(0.97f), PanelBotColor(0.99f), SelectedBorderColor(0.9f));
        DrawText(TextOr("dcui_select_disc", "Select Disc"), dx + 18.0f, dy + 18.0f, 19.0f, STYLE_ORB_TITLE,
                 PrimaryTextColor(1.0f), dlgW - 36.0f, 1, 1, dlgW - 36.0f);
        const RgbaColor ac = SwirlAccentColor(1.0f);
        for (int i = 0; i < std::min(nDiscs, 5); ++i) {
            const float ry = dy + 56.0f + static_cast<float>(i) * 50.0f;
            const bool foc = (disc_picker_index_ == i);
            DrawPanel(dx + 16.0f, ry, dlgW - 32.0f, 44.0f, 11.0f, foc ? 1.8f : 1.0f,
                      foc ? RgbaColor{ac.r, ac.g, ac.b, 0.94f} : PanelTopColor(0.86f),
                      foc ? RgbaColor{ac.r * 0.8f, ac.g * 0.8f, ac.b * 0.8f, 0.98f} : PanelBotColor(0.92f),
                      foc ? RgbaColor{1, 1, 1, 0.6f} : PanelBorderColor(0.7f));
            const std::string& dLbl = g.discLabels[i];
            float lw = 0.0f, lh = 0.0f;
            const int sMode = foc ? 1 : DefaultShadowMode();
            MeasureText(dLbl, 14.5f, STYLE_BODY_BOLD, dlgW - 48.0f, 1, lw, lh, sMode);
            DrawText(dLbl, dx + 16.0f, ry + (44.0f - lh) * 0.5f, 14.5f, STYLE_BODY_BOLD,
                     foc ? RgbaColor{1, 1, 1, 1} : PrimaryTextColor(1.0f), dlgW - 48.0f, 1, 1, dlgW - 32.0f, sMode);
            RegisterHit(dx + 16.0f, ry, dlgW - 32.0f, 44.0f, HIT_OVERLAY_ITEM, i);
        }
    } else if (overlay_ == OVERLAY_GAME_INFO) {
        const int absIdx = ActiveTabAbsGameIndex(game_index_);
        const GameEntry g = (absIdx >= 0 && absIdx < static_cast<int>(games_.size())) ? games_[absIdx] : GameEntry{};
        const float dlgW = std::min(m.bodyW - 16.0f, 460.0f);
        const float dlgH = 270.0f;
        const float dx = (screen_width_ - dlgW) * 0.5f;
        const float dy = (screen_height_ - dlgH) * 0.5f;
        DrawPanel(dx, dy, dlgW, dlgH, 20.0f, 1.8f, PanelTopColor(0.97f), PanelBotColor(0.99f), SelectedBorderColor(0.9f));
        DrawText(TextOr("dcui_info_title", "Game Information"), dx + 20.0f, dy + 18.0f, 19.0f, STYLE_ORB_TITLE,
                 PrimaryTextColor(1.0f), dlgW - 40.0f, 1, 1, dlgW - 40.0f);
        DrawText(g.title, dx + 20.0f, dy + 54.0f, 16.0f, STYLE_BODY_BOLD, PrimaryTextColor(1.0f), dlgW - 40.0f, 2, 0);

        char l1[128], l2[128], l3[128];
        std::snprintf(l1, sizeof(l1), "%s: %s", TextOr("dcui_info_console", "System").c_str(), g.platformLabel.c_str());
        std::snprintf(l2, sizeof(l2), "%s: %s", TextOr("dcui_info_serial", "Serial").c_str(), g.serial.empty() ? "-" : g.serial.c_str());
        std::snprintf(l3, sizeof(l3), "%s: %s   ·   %s: %d",
                      TextOr("dcui_info_size", "Size").c_str(), g.sizeStr.empty() ? "-" : g.sizeStr.c_str(),
                      TextOr("dcui_info_discs", "Discs").c_str(), g.discCount);
        DrawText(l1, dx + 20.0f, dy + 106.0f, 14.0f, STYLE_BODY, SecondaryTextColor(1.0f), dlgW - 40.0f, 1, 0);
        DrawText(l2, dx + 20.0f, dy + 132.0f, 14.0f, STYLE_MONO, SecondaryTextColor(1.0f), dlgW - 40.0f, 1, 0);
        DrawText(l3, dx + 20.0f, dy + 158.0f, 14.0f, STYLE_BODY, SecondaryTextColor(1.0f), dlgW - 40.0f, 1, 0);

        const float btnW = 140.0f;
        const float by = dy + dlgH - 54.0f;
        const RgbaColor ac = SwirlAccentColor(1.0f);
        DrawPanel(dx + (dlgW - btnW) * 0.5f, by, btnW, 38.0f, 10.0f, 1.4f,
                  {ac.r, ac.g, ac.b, 0.96f}, {ac.r * 0.8f, ac.g * 0.8f, ac.b * 0.8f, 0.98f}, {1, 1, 1, 0.55f});
        float cw = 0.0f, ch = 0.0f;
        const std::string closeTxt = TextOr("dcui_close", "Close");
        MeasureText(closeTxt, 14.5f, STYLE_BODY_BOLD, btnW, 1, cw, ch, 1);
        DrawText(closeTxt, dx + (dlgW - btnW) * 0.5f, by + (38.0f - ch) * 0.5f, 14.5f, STYLE_BODY_BOLD,
                 {1, 1, 1, 1}, btnW, 1, 1, btnW, 1);
        RegisterHit(dx + (dlgW - btnW) * 0.5f, by, btnW, 38.0f, HIT_OVERLAY_ITEM, 0);
    } else if (overlay_ == OVERLAY_SYSTEM_INFO) {
        const float dlgW = std::min(m.bodyW - 16.0f, 480.0f);
        const float dlgH = 290.0f;
        const float dx = (screen_width_ - dlgW) * 0.5f;
        const float dy = (screen_height_ - dlgH) * 0.5f;
        DrawPanel(dx, dy, dlgW, dlgH, 20.0f, 1.8f, PanelTopColor(0.97f), PanelBotColor(0.99f), SelectedBorderColor(0.9f));
        DrawText(TextOr("dcui_set_sysinfo", "System & BIOS Info"), dx + 20.0f, dy + 18.0f, 19.0f, STYLE_ORB_TITLE,
                 PrimaryTextColor(1.0f), dlgW - 40.0f, 1, 1, dlgW - 40.0f);

        const std::string l1 = TextOr("dcui_sys_app", "Version") + ": EmuCoreH " + status_.appVersion;
        const std::string l2 = TextOr("dcui_sys_device", "Device") + ": " + status_.deviceName;
        const std::string l3 = TextOr("dcui_sys_storage", "Free Storage") + ": " + status_.freeStorage;
        const std::string l4 = TextOr("dcui_sys_bios", "BIOS Status") + ": " + status_.biosSummary;
        DrawText(l1, dx + 20.0f, dy + 64.0f, 14.5f, STYLE_BODY_BOLD, PrimaryTextColor(1.0f), dlgW - 40.0f, 1, 0);
        DrawText(l2, dx + 20.0f, dy + 94.0f, 14.0f, STYLE_BODY, SecondaryTextColor(1.0f), dlgW - 40.0f, 1, 0);
        DrawText(l3, dx + 20.0f, dy + 122.0f, 14.0f, STYLE_BODY, SecondaryTextColor(1.0f), dlgW - 40.0f, 1, 0);
        DrawText(l4, dx + 20.0f, dy + 150.0f, 13.5f, STYLE_MONO, SwirlAccentColor(1.0f), dlgW - 40.0f, 3, 0);

        const float btnW = 140.0f;
        const float by = dy + dlgH - 54.0f;
        const RgbaColor ac = SwirlAccentColor(1.0f);
        DrawPanel(dx + (dlgW - btnW) * 0.5f, by, btnW, 38.0f, 10.0f, 1.4f,
                  {ac.r, ac.g, ac.b, 0.96f}, {ac.r * 0.8f, ac.g * 0.8f, ac.b * 0.8f, 0.98f}, {1, 1, 1, 0.55f});
        float cw = 0.0f, ch = 0.0f;
        const std::string closeTxt = TextOr("dcui_close", "Close");
        MeasureText(closeTxt, 14.5f, STYLE_BODY_BOLD, btnW, 1, cw, ch, 1);
        DrawText(closeTxt, dx + (dlgW - btnW) * 0.5f, by + (38.0f - ch) * 0.5f, 14.5f, STYLE_BODY_BOLD,
                 {1, 1, 1, 1}, btnW, 1, 1, btnW, 1);
        RegisterHit(dx + (dlgW - btnW) * 0.5f, by, btnW, 38.0f, HIT_OVERLAY_ITEM, 0);
    } else if (overlay_ == OVERLAY_TOUCH_GUIDE) {
        const float dlgW = std::min(m.bodyW - 16.0f, 480.0f);
        const float dlgH = 296.0f;
        const float dx = (screen_width_ - dlgW) * 0.5f;
        const float dy = (screen_height_ - dlgH) * 0.5f;
        DrawPanel(dx, dy, dlgW, dlgH, 20.0f, 1.8f, PanelTopColor(0.97f), PanelBotColor(0.99f), SelectedBorderColor(0.9f));
        DrawText(TextOr("dcui_touch_guide_title", "Touch Gestures"), dx + 20.0f, dy + 18.0f, 19.0f, STYLE_ORB_TITLE,
                 PrimaryTextColor(1.0f), dlgW - 40.0f, 1, 1, dlgW - 40.0f);

        const std::string tips[4] = {
            TextOr("dcui_touch_tip_1", "Tap an orb or row to select; tap again to launch or open."),
            TextOr("dcui_touch_tip_2", "Swipe horizontally on the Hub or console tabs to switch."),
            TextOr("dcui_touch_tip_3", "Long-press a game or quick save for options & delete."),
            TextOr("dcui_touch_tip_4", "Two-finger tap anywhere returns to the previous screen."),
        };
        for (int i = 0; i < 4; ++i) {
            DrawText("• " + tips[i], dx + 22.0f, dy + 60.0f + static_cast<float>(i) * 42.0f, 13.5f, STYLE_BODY,
                     SecondaryTextColor(1.0f), dlgW - 44.0f, 2, 0);
        }

        const float btnW = 160.0f;
        const float by = dy + dlgH - 54.0f;
        const RgbaColor ac = SwirlAccentColor(1.0f);
        DrawPanel(dx + (dlgW - btnW) * 0.5f, by, btnW, 38.0f, 10.0f, 1.4f,
                  {ac.r, ac.g, ac.b, 0.96f}, {ac.r * 0.8f, ac.g * 0.8f, ac.b * 0.8f, 0.98f}, {1, 1, 1, 0.55f});
        float gw = 0.0f, gh = 0.0f;
        const std::string gotTxt = TextOr("dcui_got_it", "Got it");
        MeasureText(gotTxt, 14.5f, STYLE_BODY_BOLD, btnW, 1, gw, gh, 1);
        DrawText(gotTxt, dx + (dlgW - btnW) * 0.5f, by + (38.0f - gh) * 0.5f, 14.5f, STYLE_BODY_BOLD,
                 {1, 1, 1, 1}, btnW, 1, 1, btnW, 1);
        RegisterHit(dx + (dlgW - btnW) * 0.5f, by, btnW, 38.0f, HIT_OVERLAY_ITEM, 0);
    }
}

} // namespace dcui
