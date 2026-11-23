#include "dc_renderer.h"
#include "dc_shaders.h"
#include "dc_utils.h"

#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <ctime>
#include <vector>

#define LOG_TAG "DcRenderer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace dcui {

namespace {

constexpr size_t kMaxTextCache = 320;
constexpr size_t kMaxImageCache = 64;

std::string MakeTextCacheKey(
    const std::string& text,
    float fontSize,
    int style,
    float maxWidth,
    int maxLines,
    int align,
    int shadowMode) {
    char buf[96];
    std::snprintf(
        buf,
        sizeof(buf),
        "|%.1f|%d|%.0f|%d|%d|%d",
        fontSize,
        style,
        maxWidth,
        maxLines,
        align,
        shadowMode);
    return text + buf;
}

} // namespace

bool DcRenderer::Init() {
    if (gl_ready_) {
        Shutdown();
    }
    if (!CompileShaders()) {
        LOGE("Failed to compile Dreamcast BIOS UI shaders");
        return false;
    }

    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, 4096 * sizeof(Vertex), nullptr, GL_STREAM_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(2 * sizeof(float)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(4 * sizeof(float)));

    const float fsQuad[8] = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f,
    };
    glGenVertexArrays(1, &fs_vao_);
    glGenBuffers(1, &fs_vbo_);
    glBindVertexArray(fs_vao_);
    glBindBuffer(GL_ARRAY_BUFFER, fs_vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(fsQuad), fsQuad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float), nullptr);
    glBindVertexArray(0);

    const uint8_t whitePx[4] = {255, 255, 255, 255};
    glGenTextures(1, &white_tex_);
    glBindTexture(GL_TEXTURE_2D, white_tex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, whitePx);

    has_frame_tp_ = false;
    static_idle_frames_ = 0;
    gl_ready_ = true;
    LOGI("Dreamcast BIOS GLES3 renderer initialized");
    return true;
}

void DcRenderer::Shutdown() {
    ClearTextCache();
    ClearImageCache();
    {
        std::lock_guard<std::mutex> lock(decoded_mutex_);
        pending_decoded_.clear();
    }
    if (white_tex_) {
        glDeleteTextures(1, &white_tex_);
        white_tex_ = 0;
    }
    if (vbo_) {
        glDeleteBuffers(1, &vbo_);
        vbo_ = 0;
    }
    if (vao_) {
        glDeleteVertexArrays(1, &vao_);
        vao_ = 0;
    }
    if (fs_vbo_) {
        glDeleteBuffers(1, &fs_vbo_);
        fs_vbo_ = 0;
    }
    if (fs_vao_) {
        glDeleteVertexArrays(1, &fs_vao_);
        fs_vao_ = 0;
    }
    auto delProg = [](GLuint& p) {
        if (p) {
            glDeleteProgram(p);
            p = 0;
        }
    };
    delProg(prog_quad_);
    delProg(prog_bg_);
    delProg(prog_swirl_);
    delProg(prog_panel_);
    delProg(prog_icon_);
    delProg(prog_scan_);
    gl_ready_ = false;
}

GLuint DcRenderer::CompileProgram(const char* vsSrc, const char* fsSrc) {
    auto compileShader = [](GLenum type, const char* src) -> GLuint {
        GLuint s = glCreateShader(type);
        glShaderSource(s, 1, &src, nullptr);
        glCompileShader(s);
        GLint ok = 0;
        glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            char logBuf[1024] = {};
            glGetShaderInfoLog(s, sizeof(logBuf), nullptr, logBuf);
            LOGE("Shader compile error: %s", logBuf);
            glDeleteShader(s);
            return 0;
        }
        return s;
    };

    GLuint vs = compileShader(GL_VERTEX_SHADER, vsSrc);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fsSrc);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return 0;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char logBuf[1024] = {};
        glGetProgramInfoLog(prog, sizeof(logBuf), nullptr, logBuf);
        LOGE("Program link error: %s", logBuf);
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

bool DcRenderer::CompileShaders() {
    prog_quad_ = CompileProgram(kQuadVertexShader, kQuadFragmentShader);
    prog_bg_ = CompileProgram(kFullQuadVertexShader, kDcBgFragmentShader);
    prog_swirl_ = CompileProgram(kRectVertexShader, kDcSwirlFragmentShader);
    prog_panel_ = CompileProgram(kRectVertexShader, kDcPanelFragmentShader);
    prog_icon_ = CompileProgram(kRectVertexShader, kDcIconFragmentShader);
    prog_scan_ = CompileProgram(kFullQuadVertexShader, kDcScanlineFragmentShader);

    if (!prog_quad_ || !prog_bg_ || !prog_swirl_ || !prog_panel_ || !prog_icon_ || !prog_scan_) {
        return false;
    }

    quad_u_screen_ = glGetUniformLocation(prog_quad_, "u_screen");
    quad_u_texture_ = glGetUniformLocation(prog_quad_, "u_texture");

    bg_u_screen_ = glGetUniformLocation(prog_bg_, "u_screen");
    bg_u_theme_ = glGetUniformLocation(prog_bg_, "u_theme");
    bg_u_time_ = glGetUniformLocation(prog_bg_, "u_time");
    bg_u_spheres_ = glGetUniformLocation(prog_bg_, "u_spheres");

    swirl_u_screen_ = glGetUniformLocation(prog_swirl_, "u_screen");
    swirl_u_rect_ = glGetUniformLocation(prog_swirl_, "u_rect");
    swirl_u_progress_ = glGetUniformLocation(prog_swirl_, "u_progress");
    swirl_u_rot_ = glGetUniformLocation(prog_swirl_, "u_rot");
    swirl_u_alpha_ = glGetUniformLocation(prog_swirl_, "u_alpha");
    swirl_u_color_ = glGetUniformLocation(prog_swirl_, "u_color");

    pan_u_screen_ = glGetUniformLocation(prog_panel_, "u_screen");
    pan_u_rect_ = glGetUniformLocation(prog_panel_, "u_rect");
    pan_u_size_ = glGetUniformLocation(prog_panel_, "u_size");
    pan_u_radius_ = glGetUniformLocation(prog_panel_, "u_radius");
    pan_u_border_px_ = glGetUniformLocation(prog_panel_, "u_border_px");
    pan_u_top_col_ = glGetUniformLocation(prog_panel_, "u_top_col");
    pan_u_bot_col_ = glGetUniformLocation(prog_panel_, "u_bot_col");
    pan_u_border_col_ = glGetUniformLocation(prog_panel_, "u_border_col");
    pan_u_glow_col_ = glGetUniformLocation(prog_panel_, "u_glow_col");
    pan_u_shimmer_ = glGetUniformLocation(prog_panel_, "u_shimmer");

    icon_u_screen_ = glGetUniformLocation(prog_icon_, "u_screen");
    icon_u_rect_ = glGetUniformLocation(prog_icon_, "u_rect");
    icon_u_kind_ = glGetUniformLocation(prog_icon_, "u_kind");
    icon_u_angle_ = glGetUniformLocation(prog_icon_, "u_angle");
    icon_u_alpha_ = glGetUniformLocation(prog_icon_, "u_alpha");
    icon_u_accent_ = glGetUniformLocation(prog_icon_, "u_accent");
    icon_u_light_ui_ = glGetUniformLocation(prog_icon_, "u_light_ui");

    scan_u_viewport_ = glGetUniformLocation(prog_scan_, "u_viewport_px");
    scan_u_strength_ = glGetUniformLocation(prog_scan_, "u_strength");

    return true;
}

void DcRenderer::Resize(int width, int height) {
    surface_width_ = std::max(1, width);
    surface_height_ = std::max(1, height);
    static_idle_frames_ = 0;
}

void DcRenderer::SetSafeInsets(float leftPx, float topPx, float rightPx, float bottomPx) {
    inset_left_px_ = std::max(0.0f, leftPx);
    inset_top_px_ = std::max(0.0f, topPx);
    inset_right_px_ = std::max(0.0f, rightPx);
    inset_bottom_px_ = std::max(0.0f, bottomPx);
    static_idle_frames_ = 0;
}

void DcRenderer::UpdateViewport() {
    const float sw = static_cast<float>(std::max(1, surface_width_));
    const float sh = static_cast<float>(std::max(1, surface_height_));
    const bool portrait = (sh > sw);

    view_x_ = 0;
    view_y_ = 0;
    view_width_ = surface_width_;
    view_height_ = surface_height_;

    if (settings_.aspectMode != 0) {
        float targetRatio = 16.0f / 9.0f;
        if (settings_.aspectMode == 2) targetRatio = 16.0f / 10.0f;
        else if (settings_.aspectMode == 3) targetRatio = 4.0f / 3.0f;
        if (portrait) targetRatio = 1.0f / targetRatio;

        const float curRatio = sw / sh;
        if (curRatio > targetRatio + 0.005f) {
            view_width_ = std::max(1, static_cast<int>(std::round(sh * targetRatio)));
            view_x_ = (surface_width_ - view_width_) / 2;
        } else if (curRatio < targetRatio - 0.005f) {
            view_height_ = std::max(1, static_cast<int>(std::round(sw / targetRatio)));
            view_y_ = (surface_height_ - view_height_) / 2;
        }
    }

    const float vw = static_cast<float>(std::max(1, view_width_));
    const float vh = static_cast<float>(std::max(1, view_height_));
    if (!portrait) {
        screen_height_ = 540.0f;
        screen_width_ = std::clamp(540.0f * (vw / vh), 680.0f, 1280.0f);
    } else {
        screen_width_ = 540.0f;
        screen_height_ = std::clamp(540.0f * (vh / vw), 680.0f, 1280.0f);
    }

    const float pxToVirtualX = screen_width_ / vw;
    const float pxToVirtualY = screen_height_ / vh;
    const float barX = static_cast<float>(view_x_);
    const float barY = static_cast<float>(view_y_);
    safe_left_ = std::clamp((inset_left_px_ - barX) * pxToVirtualX, 0.0f, 52.0f);
    safe_right_ = std::clamp((inset_right_px_ - barX) * pxToVirtualX, 0.0f, 52.0f);
    safe_top_ = std::clamp((inset_top_px_ - barY) * pxToVirtualY, 0.0f, 44.0f);
    safe_bottom_ = std::clamp((inset_bottom_px_ - barY) * pxToVirtualY, 0.0f, 44.0f);

    raster_scale_ = std::clamp(vh / screen_height_, 1.25f, 2.30f);
    if (last_raster_scale_ > 0.0f && std::fabs(raster_scale_ - last_raster_scale_) > 0.16f) {
        ClearTextCache();
    }
    last_raster_scale_ = raster_scale_;
}

void DcRenderer::ClearTextCache() {
    for (auto& kv : text_cache_) {
        if (kv.second.tex) glDeleteTextures(1, &kv.second.tex);
    }
    text_cache_.clear();
    text_lru_.clear();
}

void DcRenderer::ClearImageCache() {
    for (auto& kv : image_cache_) {
        if (kv.second.tex) glDeleteTextures(1, &kv.second.tex);
    }
    image_cache_.clear();
    image_lru_.clear();
}

void DcRenderer::EvictTextCacheIfNeeded() {
    while (text_cache_.size() >= kMaxTextCache && !text_lru_.empty()) {
        const std::string oldest = text_lru_.back();
        text_lru_.pop_back();
        auto it = text_cache_.find(oldest);
        if (it != text_cache_.end()) {
            if (it->second.tex) glDeleteTextures(1, &it->second.tex);
            text_cache_.erase(it);
        }
    }
}

void DcRenderer::EvictImageCacheIfNeeded() {
    while (image_cache_.size() >= kMaxImageCache && !image_lru_.empty()) {
        const std::string oldest = image_lru_.back();
        image_lru_.pop_back();
        auto it = image_cache_.find(oldest);
        if (it != image_cache_.end()) {
            if (it->second.tex) glDeleteTextures(1, &it->second.tex);
            image_cache_.erase(it);
        }
    }
}

void DcRenderer::NotifyImageDecoded(
    const std::string& path,
    int width,
    int height,
    std::vector<uint8_t> rgba) {
    std::lock_guard<std::mutex> lock(decoded_mutex_);
    if (pending_decoded_.size() >= 48) {
        pending_decoded_.pop_front();
    }
    pending_decoded_.push_back(PendingDecodedImage{path, width, height, std::move(rgba)});
    static_idle_frames_ = 0;
}

bool DcRenderer::FlushPendingDecodedImages() {
    // Also evict any dirty paths marked for refresh (e.g. overwritten quick-save screenshot)
    if (!dirty_image_paths_.empty()) {
        for (const auto& path : dirty_image_paths_) {
            auto it = image_cache_.find(path);
            if (it != image_cache_.end()) {
                if (it->second.tex) glDeleteTextures(1, &it->second.tex);
                image_lru_.erase(it->second.lruIt);
                image_cache_.erase(it);
            }
        }
        dirty_image_paths_.clear();
    }

    bool uploadedAny = false;
    constexpr int kMaxUploadsPerFrame = 3;
    for (int i = 0; i < kMaxUploadsPerFrame; ++i) {
        PendingDecodedImage item;
        {
            std::lock_guard<std::mutex> lock(decoded_mutex_);
            if (pending_decoded_.empty()) break;
            item = std::move(pending_decoded_.front());
            pending_decoded_.pop_front();
        }
        uploadedAny = true;

        auto existing = image_cache_.find(item.path);
        if (existing != image_cache_.end()) {
            if (existing->second.tex) glDeleteTextures(1, &existing->second.tex);
            image_lru_.erase(existing->second.lruIt);
            image_cache_.erase(existing);
        }

        EvictImageCacheIfNeeded();

        ImageCacheEntry entry{};
        entry.requested = true;
        entry.w = item.w;
        entry.h = item.h;
        entry.loadedAtSec = time_sec_;
        if (item.w > 0 && item.h > 0 && item.rgba.size() >= static_cast<size_t>(item.w) * item.h * 4) {
            glGenTextures(1, &entry.tex);
            glBindTexture(GL_TEXTURE_2D, entry.tex);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(
                GL_TEXTURE_2D, 0, GL_RGBA8, entry.w, entry.h, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, item.rgba.data());
            glGenerateMipmap(GL_TEXTURE_2D);
        } else {
            entry.failed = true;
        }
        image_lru_.push_front(item.path);
        entry.lruIt = image_lru_.begin();
        image_cache_.emplace(item.path, entry);
    }

    std::lock_guard<std::mutex> lock(decoded_mutex_);
    return uploadedAny || !pending_decoded_.empty();
}

const DcRenderer::TextCacheEntry& DcRenderer::GetOrCreateText(
    const std::string& text,
    float fontSize,
    int style,
    float maxWidth,
    int maxLines,
    int align,
    int shadowMode) {
    static const TextCacheEntry kEmpty{};
    if (text.empty()) return kEmpty;

    const int effAlign = (maxLines <= 1) ? 0 : align;
    const int effShadow = (shadowMode < 0) ? DefaultShadowMode() : shadowMode;
    const std::string key = MakeTextCacheKey(text, fontSize, style, maxWidth, maxLines, effAlign, effShadow);
    auto it = text_cache_.find(key);
    if (it != text_cache_.end()) {
        if (it->second.lruIt != text_lru_.begin()) {
            text_lru_.splice(text_lru_.begin(), text_lru_, it->second.lruIt);
        }
        return it->second;
    }

    int texW = 0, texH = 0;
    float logW = 0.0f, logH = 0.0f;
    std::vector<uint8_t> rgba;
    if (!HostRasterizeText(
            text, fontSize, style, maxWidth, maxLines, effAlign, raster_scale_, effShadow,
            texW, texH, logW, logH, rgba)) {
        return kEmpty;
    }

    EvictTextCacheIfNeeded();

    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, texW, texH, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());

    text_lru_.push_front(key);
    TextCacheEntry entry{tex, logW, logH, text_lru_.begin()};
    auto [insIt, _] = text_cache_.emplace(key, entry);
    return insIt->second;
}

const DcRenderer::ImageCacheEntry& DcRenderer::GetOrCreateImage(const std::string& path, int maxDim) {
    static const ImageCacheEntry kEmpty{};
    if (path.empty()) return kEmpty;

    auto it = image_cache_.find(path);
    if (it != image_cache_.end()) {
        if (it->second.lruIt != image_lru_.begin()) {
            image_lru_.splice(image_lru_.begin(), image_lru_, it->second.lruIt);
        }
        return it->second;
    }

    EvictImageCacheIfNeeded();

    ImageCacheEntry entry{};
    entry.requested = true;
    image_lru_.push_front(path);
    entry.lruIt = image_lru_.begin();
    auto [insIt, _] = image_cache_.emplace(path, entry);

    HostRequestDecodeImage(path, std::clamp(maxDim, 64, 512));
    return insIt->second;
}

float DcRenderer::ImageAlpha(const ImageCacheEntry& entry) const {
    if (!entry.tex) return 0.0f;
    if (settings_.motionMode == 3) return 1.0f;
    return Clamp01((time_sec_ - entry.loadedAtSec) / 0.22f);
}

void DcRenderer::MeasureText(
    const std::string& text,
    float fontSize,
    int style,
    float maxWidth,
    int maxLines,
    float& outW,
    float& outH,
    int shadowMode) {
    const auto& entry = GetOrCreateText(text, fontSize, style, maxWidth, maxLines, 0, shadowMode);
    outW = entry.logW;
    outH = entry.logH;
}

void DcRenderer::DrawText(
    const std::string& text,
    float x,
    float y,
    float fontSize,
    int style,
    const RgbaColor& color,
    float maxWidth,
    int maxLines,
    int align,
    float boxW,
    int shadowMode) {
    if (text.empty() || color.a <= 0.005f) return;
    const auto& entry = GetOrCreateText(text, fontSize, style, maxWidth, maxLines, align, shadowMode);
    if (!entry.tex || entry.logW <= 0.0f || entry.logH <= 0.0f) return;

    float drawX = x;
    if (align == 1 && boxW > 0.0f) {
        drawX = x + (boxW - entry.logW) * 0.5f;
    } else if (align == 2 && boxW > 0.0f) {
        drawX = x + (boxW - entry.logW);
    }
    PushQuad(entry.tex, drawX, y, entry.logW, entry.logH, color);
}

void DcRenderer::BeginQuadBatch() {
    batch_verts_.clear();
    batch_tex_ = 0;
}

void DcRenderer::FlushQuadBatch() {
    if (batch_verts_.empty() || !batch_tex_) {
        batch_verts_.clear();
        return;
    }
    glUseProgram(prog_quad_);
    glUniform2f(quad_u_screen_, screen_width_, screen_height_);
    glUniform1i(quad_u_texture_, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, batch_tex_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    const size_t byteSize = batch_verts_.size() * sizeof(Vertex);
    glBufferSubData(GL_ARRAY_BUFFER, 0, static_cast<GLsizeiptr>(byteSize), batch_verts_.data());
    glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(batch_verts_.size()));
    batch_verts_.clear();
}

void DcRenderer::PushQuad(
    GLuint tex,
    float x, float y, float w, float h,
    const RgbaColor& cTop,
    const RgbaColor& cBot,
    float u0, float v0, float u1, float v1) {
    if (!tex || w <= 0.0f || h <= 0.0f) return;
    if (batch_tex_ != tex || batch_verts_.size() >= 3900) {
        FlushQuadBatch();
        batch_tex_ = tex;
    }
    const float x2 = x + w;
    const float y2 = y + h;
    batch_verts_.push_back({x,  y,  u0, v0, cTop.r, cTop.g, cTop.b, cTop.a});
    batch_verts_.push_back({x2, y,  u1, v0, cTop.r, cTop.g, cTop.b, cTop.a});
    batch_verts_.push_back({x2, y2, u1, v1, cBot.r, cBot.g, cBot.b, cBot.a});
    batch_verts_.push_back({x,  y,  u0, v0, cTop.r, cTop.g, cTop.b, cTop.a});
    batch_verts_.push_back({x2, y2, u1, v1, cBot.r, cBot.g, cBot.b, cBot.a});
    batch_verts_.push_back({x,  y2, u0, v1, cBot.r, cBot.g, cBot.b, cBot.a});
}

void DcRenderer::PushQuad(GLuint tex, float x, float y, float w, float h, const RgbaColor& c) {
    PushQuad(tex, x, y, w, h, c, c);
}

void DcRenderer::PushTriangle(
    float x0, float y0, const RgbaColor& c0,
    float x1, float y1, const RgbaColor& c1,
    float x2, float y2, const RgbaColor& c2) {
    if (batch_tex_ != white_tex_ || batch_verts_.size() >= 3900) {
        FlushQuadBatch();
        batch_tex_ = white_tex_;
    }
    batch_verts_.push_back({x0, y0, 0.5f, 0.5f, c0.r, c0.g, c0.b, c0.a});
    batch_verts_.push_back({x1, y1, 0.5f, 0.5f, c1.r, c1.g, c1.b, c1.a});
    batch_verts_.push_back({x2, y2, 0.5f, 0.5f, c2.r, c2.g, c2.b, c2.a});
}

void DcRenderer::PushLine(float x0, float y0, float x1, float y1, float thickness, const RgbaColor& c) {
    const float dx = x1 - x0;
    const float dy = y1 - y0;
    const float len = std::sqrt(dx * dx + dy * dy);
    if (len < 0.001f || thickness <= 0.01f) return;
    const float nx = (-dy / len) * (thickness * 0.5f);
    const float ny = (dx / len) * (thickness * 0.5f);
    PushTriangle(x0 - nx, y0 - ny, c, x1 - nx, y1 - ny, c, x1 + nx, y1 + ny, c);
    PushTriangle(x0 - nx, y0 - ny, c, x1 + nx, y1 + ny, c, x0 + nx, y0 + ny, c);
}

void DcRenderer::PushCircleOutline(float cx, float cy, float radius, float thickness, int segs, const RgbaColor& c) {
    if (radius <= 0.5f || segs < 6) return;
    const float rIn = std::max(0.0f, radius - thickness * 0.5f);
    const float rOut = radius + thickness * 0.5f;
    for (int i = 0; i < segs; ++i) {
        const float a0 = (static_cast<float>(i) / segs) * kTwoPi;
        const float a1 = (static_cast<float>(i + 1) / segs) * kTwoPi;
        const float c0 = std::cos(a0), s0 = std::sin(a0);
        const float c1 = std::cos(a1), s1 = std::sin(a1);
        PushTriangle(cx + c0 * rIn, cy + s0 * rIn, c, cx + c0 * rOut, cy + s0 * rOut, c, cx + c1 * rOut, cy + s1 * rOut, c);
        PushTriangle(cx + c0 * rIn, cy + s0 * rIn, c, cx + c1 * rOut, cy + s1 * rOut, c, cx + c1 * rIn, cy + s1 * rIn, c);
    }
}

void DcRenderer::DrawPanel(
    float x, float y, float w, float h,
    float radius,
    float borderPx,
    const RgbaColor& topCol,
    const RgbaColor& botCol,
    const RgbaColor& borderCol,
    const RgbaColor& glowCol,
    float shimmer) {
    if (w <= 0.5f || h <= 0.5f) return;
    FlushQuadBatch();

    glUseProgram(prog_panel_);
    glUniform2f(pan_u_screen_, screen_width_, screen_height_);
    glUniform4f(pan_u_rect_, x, y, w, h);
    glUniform2f(pan_u_size_, w, h);
    glUniform1f(pan_u_radius_, radius);
    glUniform1f(pan_u_border_px_, borderPx);
    glUniform4f(pan_u_top_col_, topCol.r, topCol.g, topCol.b, topCol.a);
    glUniform4f(pan_u_bot_col_, botCol.r, botCol.g, botCol.b, botCol.a);
    glUniform4f(pan_u_border_col_, borderCol.r, borderCol.g, borderCol.b, borderCol.a);
    glUniform4f(pan_u_glow_col_, glowCol.r, glowCol.g, glowCol.b, glowCol.a);
    glUniform1f(pan_u_shimmer_, shimmer);

    glBindVertexArray(fs_vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void DcRenderer::DrawSwirl(
    float cx, float cy, float radius, float progress, float rotRad, float alpha, const RgbaColor& col) {
    if (radius <= 1.0f || alpha <= 0.005f || progress <= 0.005f) return;
    FlushQuadBatch();

    const float d = radius * 2.0f;
    glUseProgram(prog_swirl_);
    glUniform2f(swirl_u_screen_, screen_width_, screen_height_);
    glUniform4f(swirl_u_rect_, cx - radius, cy - radius, d, d);
    glUniform1f(swirl_u_progress_, Clamp01(progress));
    glUniform1f(swirl_u_rot_, rotRad);
    glUniform1f(swirl_u_alpha_, Clamp01(alpha));
    glUniform4f(swirl_u_color_, col.r, col.g, col.b, col.a);

    glBindVertexArray(fs_vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void DcRenderer::DrawShaderIcon(
    int kind, float cx, float cy, float radius, float angleRad, float alpha, const RgbaColor& accent) {
    if (radius <= 1.0f || alpha <= 0.005f) return;
    FlushQuadBatch();

    const float d = radius * 2.0f;
    glUseProgram(prog_icon_);
    glUniform2f(icon_u_screen_, screen_width_, screen_height_);
    glUniform4f(icon_u_rect_, cx - radius, cy - radius, d, d);
    glUniform1i(icon_u_kind_, kind);
    glUniform1f(icon_u_angle_, angleRad);
    glUniform1f(icon_u_alpha_, Clamp01(alpha));
    glUniform4f(icon_u_accent_, accent.r, accent.g, accent.b, accent.a);
    glUniform1i(icon_u_light_ui_, IsLightTheme() ? 1 : 0);

    glBindVertexArray(fs_vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void DcRenderer::DrawImageAspectFit(
    const ImageCacheEntry& img,
    float x, float y, float w, float h,
    float alpha,
    bool fillCrop) {
    if (!img.tex || img.w <= 0 || img.h <= 0 || alpha <= 0.005f) return;
    const float srcAspect = static_cast<float>(img.w) / static_cast<float>(img.h);
    const float dstAspect = w / std::max(1.0f, h);

    if (!fillCrop) {
        float drawW = w;
        float drawH = h;
        if (srcAspect > dstAspect) {
            drawH = w / srcAspect;
        } else {
            drawW = h * srcAspect;
        }
        const float dx = x + (w - drawW) * 0.5f;
        const float dy = y + (h - drawH) * 0.5f;
        PushQuad(img.tex, dx, dy, drawW, drawH, {1.0f, 1.0f, 1.0f, alpha});
    } else {
        float u0 = 0.0f, v0 = 0.0f, u1 = 1.0f, v1 = 1.0f;
        if (srcAspect > dstAspect) {
            float keep = dstAspect / srcAspect;
            u0 = (1.0f - keep) * 0.5f;
            u1 = 1.0f - u0;
        } else {
            float keep = srcAspect / dstAspect;
            v0 = (1.0f - keep) * 0.5f;
            v1 = 1.0f - v0;
        }
        PushQuad(img.tex, x, y, w, h, {1.0f, 1.0f, 1.0f, alpha}, {1.0f, 1.0f, 1.0f, alpha}, u0, v0, u1, v1);
    }
}

void DcRenderer::DrawScanlinesPass() {
    if (settings_.scanlinesMode == 0) return;
    FlushQuadBatch();
    const float strength = (settings_.scanlinesMode == 1) ? 0.075f : 0.165f;
    glUseProgram(prog_scan_);
    glUniform2f(scan_u_viewport_, static_cast<float>(view_width_), static_cast<float>(view_height_));
    glUniform1f(scan_u_strength_, strength);
    glBindVertexArray(fs_vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

bool DcRenderer::IsLightTheme() const {
    return settings_.theme == 0 || settings_.theme == 1;
}

int DcRenderer::DefaultShadowMode() const {
    return IsLightTheme() ? 0 : 1;
}

RgbaColor DcRenderer::SwirlAccentColor(float alpha) const {
    switch (settings_.theme) {
        case 1: return {0.10f, 0.39f, 0.91f, alpha}; // Dreamcast PAL Royal Blue
        case 2: return {0.95f, 0.28f, 0.13f, alpha}; // Sega Sports Vermilion
        case 3: return {0.00f, 0.84f, 1.00f, alpha}; // Katana Devkit Electric Cyan
        case 4: return {0.24f, 0.55f, 1.00f, alpha}; // Saturn Midnight Sapphire
        default: return {0.93f, 0.33f, 0.13f, alpha}; // Classic Dreamcast Swirl Orange (#EE5522)
    }
}

RgbaColor DcRenderer::SecondaryAccentColor(float alpha) const {
    switch (settings_.theme) {
        case 1: return {0.25f, 0.72f, 1.00f, alpha};
        case 2: return {1.00f, 0.72f, 0.19f, alpha};
        case 3: return {1.00f, 0.72f, 0.25f, alpha};
        case 4: return {0.72f, 0.83f, 1.00f, alpha};
        default: return {0.13f, 0.40f, 0.80f, alpha}; // Classic Dreamcast Sky Cobalt (#2266CC)
    }
}

RgbaColor DcRenderer::PanelTopColor(float alpha) const {
    switch (settings_.theme) {
        case 0: return {0.97f, 0.985f, 1.00f, alpha};
        case 1: return {0.90f, 0.955f, 1.00f, alpha};
        case 2: return {0.10f, 0.13f, 0.19f, alpha};
        case 3: return {0.045f, 0.135f, 0.21f, alpha};
        default: return {0.075f, 0.11f, 0.27f, alpha};
    }
}

RgbaColor DcRenderer::PanelBotColor(float alpha) const {
    switch (settings_.theme) {
        case 0: return {0.87f, 0.915f, 0.975f, alpha};
        case 1: return {0.79f, 0.88f, 0.985f, alpha};
        case 2: return {0.045f, 0.060f, 0.098f, alpha};
        case 3: return {0.022f, 0.075f, 0.13f, alpha};
        default: return {0.035f, 0.055f, 0.165f, alpha};
    }
}

RgbaColor DcRenderer::PanelBorderColor(float alpha) const {
    switch (settings_.theme) {
        case 0: return {0.56f, 0.71f, 0.89f, alpha};
        case 1: return {0.34f, 0.59f, 0.92f, alpha};
        case 2: return {0.30f, 0.38f, 0.50f, alpha};
        case 3: return {0.16f, 0.65f, 0.85f, alpha};
        default: return {0.32f, 0.52f, 0.92f, alpha};
    }
}

RgbaColor DcRenderer::SelectedBorderColor(float alpha) const {
    return SwirlAccentColor(alpha);
}

RgbaColor DcRenderer::PrimaryTextColor(float alpha) const {
    if (IsLightTheme()) {
        return {0.06f, 0.13f, 0.24f, alpha};
    }
    return {0.96f, 0.98f, 1.00f, alpha};
}

RgbaColor DcRenderer::SecondaryTextColor(float alpha) const {
    if (IsLightTheme()) {
        return {0.26f, 0.36f, 0.50f, alpha};
    }
    return {0.72f, 0.82f, 0.94f, alpha};
}

bool DcRenderer::Draw() {
    if (!gl_ready_) return false;
    UpdateViewport();

    const auto now = std::chrono::steady_clock::now();
    float dt = 0.016f;
    if (has_frame_tp_) {
        dt = std::chrono::duration<float>(now - last_frame_tp_).count();
        dt = std::clamp(dt, 0.001f, 0.05f);
    }
    last_frame_tp_ = now;
    has_frame_tp_ = true;

    time_sec_ += dt;
    UpdateCachedClockStrings();

    const bool pendingImages = FlushPendingDecodedImages();

    const float motionScale = (settings_.motionMode == 1) ? 0.45f
                            : (settings_.motionMode == 2) ? 0.65f
                            : (settings_.motionMode == 3) ? 0.0f
                                                          : 1.0f;
    spin_angle_ += dt * 1.15f * motionScale;
    if (spin_angle_ > kTwoPi * 100.0f) {
        spin_angle_ -= kTwoPi * 100.0f;
    }

    if (screen_fade_t_ < 1.0f) {
        screen_fade_t_ = std::min(1.0f, screen_fade_t_ + dt * 4.2f);
    }

    // Spring-follow hub orbital angle (shortest arc modulo 4)
    {
        float diff = static_cast<float>(main_index_) - hub_visual_pos_;
        while (diff > 2.0f) diff -= 4.0f;
        while (diff < -2.0f) diff += 4.0f;
        if (settings_.motionMode == 3) {
            hub_visual_pos_ = static_cast<float>(main_index_);
        } else {
            const float step = std::min(1.0f, dt * 11.0f);
            hub_visual_pos_ += diff * step;
            if (hub_visual_pos_ < 0.0f) hub_visual_pos_ += 4.0f;
            if (hub_visual_pos_ >= 4.0f) hub_visual_pos_ -= 4.0f;
        }
    }

    // Smooth scroll easing for game list, console tabs, and settings
    const float scrollLerp = (settings_.motionMode == 3) ? 1.0f : std::min(1.0f, dt * 15.0f);
    game_scroll_y_ += (game_scroll_target_y_ - game_scroll_y_) * scrollLerp;
    tab_scroll_x_ += (tab_scroll_target_x_ - tab_scroll_x_) * scrollLerp;
    settings_scroll_y_ += (settings_scroll_target_y_ - settings_scroll_y_) * scrollLerp;

    if (boot_active_) {
        if (!boot_sfx_played_ && boot_t_ > 0.01f) {
            boot_sfx_played_ = true;
            HostPlaySfx(SFX_BOOT_INTRO);
        }
        boot_t_ += dt / std::max(0.25f, boot_duration_);
        if (boot_t_ >= 1.0f) {
            boot_active_ = false;
            boot_t_ = 1.0f;
            screen_fade_t_ = 0.0f;
        }
    }

    if (launching_) {
        launch_t_ += dt * 2.15f;
        if (launch_t_ >= 1.0f && !launch_dispatched_) {
            launch_dispatched_ = true;
            if (launch_is_save_) {
                HostNotifySaveLaunch(launch_path_, launch_save_slot_);
            } else {
                HostNotifyGameLaunch(launch_path_);
            }
        }
        if (launch_t_ > 2.5f) {
            launching_ = false;
        }
    }

    // Clear full letterbox surface black, then render inside active aspect viewport
    glDisable(GL_DEPTH_TEST);
    glViewport(0, 0, surface_width_, surface_height_);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glViewport(view_x_, view_y_, view_width_, view_height_);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    // 1. Background shader + 6 floating translucent Dreamcast crystal spheres
    {
        const float w = screen_width_;
        const float h = screen_height_;
        const float orbitScale = motionScale;
        const float ts = time_sec_ * orbitScale;
        const float baseR = std::min(w, h) * 0.145f;

        const float sx0 = w * 0.16f + std::sin(ts * 0.34f) * 32.0f;
        const float sy0 = h * 0.24f + std::cos(ts * 0.27f) * 24.0f;
        const float r0 = baseR * 1.15f;

        const float sx1 = w * 0.84f + std::cos(ts * 0.31f + 1.1f) * 38.0f;
        const float sy1 = h * 0.26f + std::sin(ts * 0.39f + 0.6f) * 28.0f;
        const float r1 = baseR * 0.92f;

        const float sx2 = w * 0.22f + std::cos(ts * 0.29f + 2.3f) * 28.0f;
        const float sy2 = h * 0.78f + std::sin(ts * 0.25f + 1.4f) * 26.0f;
        const float r2 = baseR * 0.78f;

        const float sx3 = w * 0.80f + std::sin(ts * 0.28f + 3.1f) * 34.0f;
        const float sy3 = h * 0.76f + std::cos(ts * 0.33f + 2.0f) * 24.0f;
        const float r3 = baseR * 1.05f;

        const float sx4 = w * 0.50f + std::sin(ts * 0.22f + 0.9f) * 46.0f;
        const float sy4 = h * 0.16f + std::cos(ts * 0.19f + 1.7f) * 18.0f;
        const float r4 = baseR * 0.62f;

        const float sx5 = w * 0.46f + std::cos(ts * 0.26f + 4.1f) * 40.0f;
        const float sy5 = h * 0.86f + std::sin(ts * 0.21f + 0.4f) * 18.0f;
        const float r5 = baseR * 0.68f;

        const GLfloat spheres[24] = {
            sx0, sy0, r0, 1.0f / std::max(1.0f, r0),
            sx1, sy1, r1, 1.0f / std::max(1.0f, r1),
            sx2, sy2, r2, 1.0f / std::max(1.0f, r2),
            sx3, sy3, r3, 1.0f / std::max(1.0f, r3),
            sx4, sy4, r4, 1.0f / std::max(1.0f, r4),
            sx5, sy5, r5, 1.0f / std::max(1.0f, r5),
        };

        glUseProgram(prog_bg_);
        glUniform2f(bg_u_screen_, w, h);
        glUniform1i(bg_u_theme_, settings_.theme);
        glUniform1f(bg_u_time_, ts);
        glUniform4fv(bg_u_spheres_, 6, spheres);
        glBindVertexArray(fs_vao_);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    hit_targets_.clear();
    BeginQuadBatch();

    const LayoutMetrics m = ComputeMetrics();
    const float uiAlpha = EaseOutCubic(screen_fade_t_);

    if (boot_active_) {
        DrawBootIntro(1.0f);
    } else if (launching_) {
        DrawLaunchStage();
    } else {
        switch (screen_) {
            case SCREEN_MAIN_MENU:
                DrawMainMenu(m, uiAlpha);
                break;
            case SCREEN_GAMES:
                DrawGamesScreen(m, uiAlpha);
                break;
            case SCREEN_FILE:
                DrawFileScreen(m, uiAlpha);
                break;
            case SCREEN_SETTINGS:
                DrawSettingsScreen(m, uiAlpha);
                break;
            default:
                DrawMainMenu(m, uiAlpha);
                break;
        }
        DrawBottomBar(m, uiAlpha);
        if (overlay_ != OVERLAY_NONE) {
            DrawOverlay(m);
        }
    }

    FlushQuadBatch();
    DrawScanlinesPass();

    // Determine whether another frame is immediately needed (for Static idle power saving)
    bool hasActiveFades = false;
    if (settings_.motionMode == 3) {
        for (const auto& kv : image_cache_) {
            if (kv.second.tex && (time_sec_ - kv.second.loadedAtSec) < 0.26f) {
                hasActiveFades = true;
                break;
            }
        }
    }

    const bool animatingNow =
        boot_active_ ||
        launching_ ||
        screen_fade_t_ < 0.995f ||
        pendingImages ||
        hasActiveFades ||
        std::fabs(game_scroll_y_ - game_scroll_target_y_) > 0.25f ||
        std::fabs(tab_scroll_x_ - tab_scroll_target_x_) > 0.25f ||
        std::fabs(settings_scroll_y_ - settings_scroll_target_y_) > 0.25f ||
        (settings_.motionMode != 3);

    if (animatingNow) {
        static_idle_frames_ = 0;
        return true;
    }
    // Keep 3 settle frames in Static mode so final text/image state commits cleanly before pausing
    if (static_idle_frames_ < 3) {
        static_idle_frames_++;
        return true;
    }
    return false;
}

void DcRenderer::RegisterHit(float x, float y, float w, float h, int kind, int index) {
    hit_targets_.push_back(HitTarget{x, y, w, h, kind, index});
}

const DcRenderer::HitTarget* DcRenderer::FindHit(float vx, float vy) const {
    for (auto it = hit_targets_.rbegin(); it != hit_targets_.rend(); ++it) {
        if (vx >= it->x && vx <= (it->x + it->w) && vy >= it->y && vy <= (it->y + it->h)) {
            return &(*it);
        }
    }
    return nullptr;
}

bool DcRenderer::SurfaceNormToVirtual(float normX, float normY, float& outVx, float& outVy) const {
    const float sx = normX * static_cast<float>(surface_width_);
    const float sy = normY * static_cast<float>(surface_height_);
    const float vx = (sx - static_cast<float>(view_x_)) / static_cast<float>(std::max(1, view_width_));
    const float vy = (sy - static_cast<float>(view_y_)) / static_cast<float>(std::max(1, view_height_));
    outVx = vx * screen_width_;
    outVy = vy * screen_height_;
    return vx >= 0.0f && vx <= 1.0f && vy >= 0.0f && vy <= 1.0f;
}

std::string DcRenderer::TextOr(const std::string& key, const char* fallback) const {
    auto it = strings_.find(key);
    if (it != strings_.end() && !it->second.empty()) {
        return it->second;
    }
    return fallback ? std::string(fallback) : std::string();
}

int DcRenderer::ShaderIconForPlatform(const std::string& platformId) const {
    if (platformId == "GENESIS" || platformId == "SEGA_32X" || platformId == "MASTER_SYSTEM" ||
        platformId == "GAME_GEAR" || platformId == "SG_1000") {
        return ICON_CARTRIDGE;
    }
    if (platformId == "NAOMI" || platformId == "NAOMI_2" || platformId == "ATOMISWAVE" ||
        platformId == "SYSTEM_SP" || platformId == "MODEL_3" || platformId == "ARCADE") {
        return ICON_ARCADE;
    }
    return ICON_GDROM;
}

RgbaColor DcRenderer::ColorForPlatform(const std::string& platformId) const {
    for (const auto& ct : console_tabs_) {
        if (ct.id == platformId) return ct.accent;
    }
    return SwirlAccentColor(1.0f);
}

void DcRenderer::RebuildDisplayTabs(bool forceSelectRecentIfPresent) {
    std::string prevTabId;
    std::string prevGamePath;
    if (tab_index_ >= 0 && tab_index_ < static_cast<int>(display_tabs_.size())) {
        prevTabId = display_tabs_[tab_index_].id;
        const auto& indices = display_tabs_[tab_index_].gameIndices;
        if (game_index_ >= 0 && game_index_ < static_cast<int>(indices.size())) {
            int absIdx = indices[game_index_];
            if (absIdx >= 0 && absIdx < static_cast<int>(games_.size())) {
                prevGamePath = games_[absIdx].path;
            }
        }
    }

    display_tabs_.clear();
    has_recent_tab_ = false;

    std::vector<int> recentIndices;
    for (int i = 0; i < static_cast<int>(games_.size()); ++i) {
        if (games_[i].recentRank >= 0) {
            recentIndices.push_back(i);
        }
    }
    if (!recentIndices.empty()) {
        std::sort(recentIndices.begin(), recentIndices.end(), [&](int a, int b) {
            if (games_[a].recentRank != games_[b].recentRank) {
                return games_[a].recentRank < games_[b].recentRank;
            }
            return games_[a].title < games_[b].title;
        });
        has_recent_tab_ = true;
        DisplayTab rt;
        rt.id = "_RECENT_";
        rt.label = TextOr("dcui_tab_recent", "Recent");
        rt.accent = SwirlAccentColor(1.0f);
        rt.isRecent = true;
        rt.gameIndices = std::move(recentIndices);
        display_tabs_.push_back(std::move(rt));
    }

    std::unordered_set<std::string> coveredIds;
    for (const auto& ct : console_tabs_) {
        DisplayTab dt;
        dt.id = ct.id;
        dt.label = ct.label;
        dt.accent = ct.accent;
        dt.isRecent = false;
        for (int i = 0; i < static_cast<int>(games_.size()); ++i) {
            if (games_[i].platformId == ct.id) {
                dt.gameIndices.push_back(i);
            }
        }
        if (!dt.gameIndices.empty()) {
            coveredIds.insert(ct.id);
            display_tabs_.push_back(std::move(dt));
        }
    }

    // Fallback for any platform IDs not pre-registered in console_tabs_
    for (int i = 0; i < static_cast<int>(games_.size()); ++i) {
        const std::string& pid = games_[i].platformId;
        if (coveredIds.count(pid)) continue;
        coveredIds.insert(pid);
        DisplayTab dt;
        dt.id = pid;
        dt.label = games_[i].platformLabel.empty() ? (pid.empty() ? "Sega" : pid) : games_[i].platformLabel;
        dt.accent = SwirlAccentColor(1.0f);
        for (int j = i; j < static_cast<int>(games_.size()); ++j) {
            if (games_[j].platformId == pid) {
                dt.gameIndices.push_back(j);
            }
        }
        display_tabs_.push_back(std::move(dt));
    }

    if (display_tabs_.empty()) {
        tab_index_ = 0;
        game_index_ = 0;
        return;
    }

    if (forceSelectRecentIfPresent && has_recent_tab_) {
        tab_index_ = 0;
        game_index_ = 0;
        game_scroll_y_ = 0.0f;
        game_scroll_target_y_ = 0.0f;
        tab_scroll_x_ = 0.0f;
        tab_scroll_target_x_ = 0.0f;
        return;
    }

    if (!prevTabId.empty()) {
        int foundTab = -1;
        for (int i = 0; i < static_cast<int>(display_tabs_.size()); ++i) {
            if (display_tabs_[i].id == prevTabId) {
                foundTab = i;
                break;
            }
        }
        if (foundTab >= 0) {
            tab_index_ = foundTab;
            if (!prevGamePath.empty()) {
                const auto& indices = display_tabs_[tab_index_].gameIndices;
                for (int r = 0; r < static_cast<int>(indices.size()); ++r) {
                    if (games_[indices[r]].path == prevGamePath) {
                        game_index_ = r;
                        break;
                    }
                }
            }
        }
    }
    ClampSelectionIndices();
}

void DcRenderer::SwitchScreen(int nextScreen) {
    screen_ = nextScreen;
    screen_fade_t_ = 0.0f;
    static_idle_frames_ = 0;

    if (nextScreen == SCREEN_GAMES) {
        if (has_recent_tab_) {
            tab_index_ = 0;
            game_index_ = 0;
            game_scroll_y_ = 0.0f;
            game_scroll_target_y_ = 0.0f;
            tab_scroll_x_ = 0.0f;
            tab_scroll_target_x_ = 0.0f;
        }
    }
    ClampSelectionIndices();
}

int DcRenderer::ActiveTabGameCount() const {
    if (tab_index_ < 0 || tab_index_ >= static_cast<int>(display_tabs_.size())) return 0;
    return static_cast<int>(display_tabs_[tab_index_].gameIndices.size());
}

int DcRenderer::ActiveTabAbsGameIndex(int rowIdx) const {
    if (tab_index_ < 0 || tab_index_ >= static_cast<int>(display_tabs_.size())) return -1;
    const auto& indices = display_tabs_[tab_index_].gameIndices;
    if (rowIdx < 0 || rowIdx >= static_cast<int>(indices.size())) return -1;
    return indices[rowIdx];
}

std::vector<int> DcRenderer::FilteredSaveIndices() const {
    std::vector<int> out;
    if (file_tab_ != 1 || file_filter_game_path_.empty()) {
        out.reserve(saves_.size());
        for (int i = 0; i < static_cast<int>(saves_.size()); ++i) {
            out.push_back(i);
        }
        return out;
    }
    for (int i = 0; i < static_cast<int>(saves_.size()); ++i) {
        if (saves_[i].gamePath == file_filter_game_path_) {
            out.push_back(i);
        }
    }
    return out;
}

void DcRenderer::ClampSelectionIndices() {
    main_index_ = std::clamp(main_index_, 0, 3);
    if (display_tabs_.empty()) {
        tab_index_ = 0;
        game_index_ = 0;
    } else {
        tab_index_ = std::clamp(tab_index_, 0, static_cast<int>(display_tabs_.size()) - 1);
        const int gCount = ActiveTabGameCount();
        if (gCount <= 0) {
            game_index_ = 0;
        } else {
            game_index_ = std::clamp(game_index_, 0, gCount - 1);
        }
    }
    file_tab_ = std::clamp(file_tab_, 0, 2);
    if (vmu_cards_.empty()) {
        vmu_card_index_ = 0;
    } else {
        vmu_card_index_ = std::clamp(vmu_card_index_, 0, static_cast<int>(vmu_cards_.size()) - 1);
    }
    if (vmu_files_.empty()) {
        vmu_file_index_ = 0;
    } else {
        vmu_file_index_ = std::clamp(vmu_file_index_, 0, static_cast<int>(vmu_files_.size()) - 1);
    }
    const int perPage = ComputeMetrics().portrait ? 9 : 12;
    const int totalPages = std::max(1, (static_cast<int>(FilteredSaveIndices().size()) + perPage - 1) / perPage);
    save_page_ = std::clamp(save_page_, 0, totalPages - 1);
    save_slot_index_ = std::clamp(save_slot_index_, 0, perPage - 1);
    settings_index_ = std::clamp(settings_index_, 0, kSettingsCount - 1);
}

void DcRenderer::UpdateCachedClockStrings() {
    const std::time_t nowSec = std::time(nullptr);
    if (nowSec == cached_clock_sec_ &&
        cached_clock_24h_ == settings_.time24h &&
        cached_clock_date_fmt_ == settings_.dateFormat &&
        !cached_time_str_.empty()) {
        return;
    }
    cached_clock_sec_ = nowSec;
    cached_clock_24h_ = settings_.time24h;
    cached_clock_date_fmt_ = settings_.dateFormat;

    std::tm tmv{};
    localtime_r(&nowSec, &tmv);
    clock_hour_ = tmv.tm_hour;
    clock_min_ = tmv.tm_min;
    clock_sec_ = tmv.tm_sec;
    cached_time_str_ = FormatClockFromTm(tmv);
    cached_date_str_ = FormatDateFromTm(tmv);
}

std::string DcRenderer::FormatClockFromTm(const std::tm& tmv) const {
    char buf[24];
    if (settings_.time24h) {
        std::snprintf(buf, sizeof(buf), "%02d:%02d:%02d", tmv.tm_hour, tmv.tm_min, tmv.tm_sec);
    } else {
        int h = tmv.tm_hour % 12;
        if (h == 0) h = 12;
        std::snprintf(buf, sizeof(buf), "%d:%02d:%02d %s", h, tmv.tm_min, tmv.tm_sec,
                      (tmv.tm_hour >= 12) ? "PM" : "AM");
    }
    return buf;
}

std::string DcRenderer::FormatDateFromTm(const std::tm& tmv) const {
    char buf[24];
    const int y = tmv.tm_year + 1900;
    const int m = tmv.tm_mon + 1;
    const int d = tmv.tm_mday;
    if (settings_.dateFormat == 1) {
        std::snprintf(buf, sizeof(buf), "%02d/%02d/%04d", m, d, y);
    } else if (settings_.dateFormat == 2) {
        std::snprintf(buf, sizeof(buf), "%04d/%02d/%02d", y, m, d);
    } else {
        std::snprintf(buf, sizeof(buf), "%02d/%02d/%04d", d, m, y);
    }
    return buf;
}

void DcRenderer::SetStrings(std::unordered_map<std::string, std::string> strings) {
    strings_ = std::move(strings);
    ClearTextCache();
    RebuildDisplayTabs(false);
    static_idle_frames_ = 0;
}

void DcRenderer::SetConsoleTabs(std::vector<ConsoleTabInfo> tabs) {
    console_tabs_ = std::move(tabs);
    RebuildDisplayTabs(false);
    static_idle_frames_ = 0;
}

void DcRenderer::SetGames(std::vector<GameEntry> games) {
    const bool hadRecent = has_recent_tab_;
    games_ = std::move(games);
    RebuildDisplayTabs(!hadRecent);
    static_idle_frames_ = 0;
}

void DcRenderer::SetSaves(std::vector<SaveEntry> saves) {
    for (const auto& newSave : saves) {
        if (newSave.previewPath.empty()) continue;
        for (const auto& oldSave : saves_) {
            if (oldSave.previewPath == newSave.previewPath &&
                (oldSave.dateStr != newSave.dateStr || oldSave.sizeStr != newSave.sizeStr)) {
                dirty_image_paths_.insert(newSave.previewPath);
                break;
            }
        }
    }
    saves_ = std::move(saves);
    ClampSelectionIndices();
    static_idle_frames_ = 0;
}

void DcRenderer::SetVmuCards(std::vector<VmuCardEntry> cards) {
    vmu_cards_ = std::move(cards);
    ClampSelectionIndices();
    static_idle_frames_ = 0;
}

void DcRenderer::SetVmuFiles(int cardIndex, std::vector<VmuFileEntry> files) {
    if (cardIndex == vmu_card_index_ || loaded_vmu_card_index_ < 0 || vmu_cards_.empty()) {
        loaded_vmu_card_index_ = cardIndex;
        for (const auto& f : files) {
            if (!f.iconPath.empty()) {
                dirty_image_paths_.insert(f.iconPath);
            }
        }
        vmu_files_ = std::move(files);
        ClampSelectionIndices();
        static_idle_frames_ = 0;
    }
}

void DcRenderer::SetSettings(const ShellSettings& settings) {
    const bool themeLightChanged = (IsLightTheme() != (settings.theme == 0 || settings.theme == 1));
    settings_ = settings;
    settings_.theme = std::clamp(settings_.theme, 0, 4);
    settings_.aspectMode = std::clamp(settings_.aspectMode, 0, 3);
    settings_.motionMode = std::clamp(settings_.motionMode, 0, 3);
    settings_.scanlinesMode = std::clamp(settings_.scanlinesMode, 0, 2);
    settings_.bootAnimMode = std::clamp(settings_.bootAnimMode, 0, 2);
    settings_.dateFormat = std::clamp(settings_.dateFormat, 0, 2);
    if (themeLightChanged) {
        ClearTextCache();
    }
    static_idle_frames_ = 0;
}

void DcRenderer::SetSystemStatus(const SystemStatus& status) {
    status_ = status;
    static_idle_frames_ = 0;
}

void DcRenderer::SetTouchMode(bool active, bool showTutorial) {
    touch_mode_active_ = active;
    if (showTutorial && overlay_ == OVERLAY_NONE && !boot_active_) {
        overlay_ = OVERLAY_TOUCH_GUIDE;
    }
    static_idle_frames_ = 0;
}

void DcRenderer::SetLoading(bool gamesLoading, bool savesLoading, bool vmuLoading) {
    games_loading_ = gamesLoading;
    saves_loading_ = savesLoading;
    vmu_loading_ = vmuLoading;
    static_idle_frames_ = 0;
}

void DcRenderer::TriggerTransition(int kind) {
    launching_ = false;
    launch_dispatched_ = false;
    launch_t_ = 0.0f;
    boot_sfx_played_ = false;
    static_idle_frames_ = 0;

    if (kind == 1 || settings_.bootAnimMode == 2) {
        boot_active_ = false;
        screen_fade_t_ = 1.0f;
        if (screen_ == SCREEN_GAMES && has_recent_tab_) {
            tab_index_ = 0;
            game_index_ = 0;
            game_scroll_y_ = 0.0f;
            game_scroll_target_y_ = 0.0f;
            tab_scroll_x_ = 0.0f;
            tab_scroll_target_x_ = 0.0f;
        }
    } else {
        boot_active_ = true;
        boot_t_ = 0.0f;
        boot_duration_ = (settings_.bootAnimMode == 1) ? 1.15f : 2.50f;
        screen_fade_t_ = 1.0f;
    }
}

std::vector<int> DcRenderer::GetState() const {
    return {screen_, main_index_, tab_index_, game_index_, file_tab_, vmu_card_index_, save_slot_index_};
}

void DcRenderer::SetState(
    int screen,
    int mainIndex,
    int tabIndex,
    int gameIndex,
    int fileTab,
    int vmuCardIndex,
    int saveIndex) {
    screen_ = std::clamp(screen, 0, 3);
    main_index_ = std::clamp(mainIndex, 0, 3);
    hub_visual_pos_ = static_cast<float>(main_index_);
    tab_index_ = std::max(0, tabIndex);
    game_index_ = std::max(0, gameIndex);
    file_tab_ = std::clamp(fileTab, 0, 2);
    vmu_card_index_ = std::max(0, vmuCardIndex);
    save_slot_index_ = std::max(0, saveIndex);
    ClampSelectionIndices();
    static_idle_frames_ = 0;
}

} // namespace dcui
