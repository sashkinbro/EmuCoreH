#include "dc_renderer.h"

#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <jni.h>
#include <memory>
#include <string>
#include <vector>

#define LOG_TAG "DcShellJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM* g_vm = nullptr;
jclass g_host_cls = nullptr;
jmethodID g_on_game_launch = nullptr;
jmethodID g_on_save_launch = nullptr;
jmethodID g_on_delete_save = nullptr;
jmethodID g_on_vmu_action = nullptr;
jmethodID g_on_open_game_settings = nullptr;
jmethodID g_on_settings_changed = nullptr;
jmethodID g_on_exit_requested = nullptr;

jclass g_text_cls = nullptr;
jmethodID g_rasterize_text = nullptr;

jclass g_img_cls = nullptr;
jmethodID g_request_decode = nullptr;

jclass g_audio_cls = nullptr;
jmethodID g_play_sfx = nullptr;

std::unique_ptr<dcui::DcRenderer> g_renderer;

JNIEnv* GetEnv() {
    if (!g_vm) return nullptr;
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
    }
    return env;
}

jstring Utf8ToJstring(JNIEnv* env, const std::string& utf8) {
    if (utf8.empty()) {
        return env->NewString(reinterpret_cast<const jchar*>(u""), 0);
    }
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    size_t i = 0;
    const size_t n = utf8.size();
    while (i < n) {
        uint32_t cp = 0;
        const uint8_t c = static_cast<uint8_t>(utf8[i]);
        if (c < 0x80) {
            cp = c;
            i += 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < n) {
            cp = ((c & 0x1F) << 6) | (static_cast<uint8_t>(utf8[i + 1]) & 0x3F);
            i += 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < n) {
            cp = ((c & 0x0F) << 12) |
                 ((static_cast<uint8_t>(utf8[i + 1]) & 0x3F) << 6) |
                 (static_cast<uint8_t>(utf8[i + 2]) & 0x3F);
            i += 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < n) {
            cp = ((c & 0x07) << 18) |
                 ((static_cast<uint8_t>(utf8[i + 1]) & 0x3F) << 12) |
                 ((static_cast<uint8_t>(utf8[i + 2]) & 0x3F) << 6) |
                 (static_cast<uint8_t>(utf8[i + 3]) & 0x3F);
            i += 4;
        } else {
            cp = 0xFFFD;
            i += 1;
        }
        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + ((cp >> 10) & 0x3FF)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::string JstringToUtf8(JNIEnv* env, jstring jstr) {
    if (!jstr) return {};
    const jchar* chars = env->GetStringChars(jstr, nullptr);
    const jsize len = env->GetStringLength(jstr);
    if (!chars || len <= 0) {
        if (chars) env->ReleaseStringChars(jstr, chars);
        return {};
    }
    std::string out;
    out.reserve(static_cast<size_t>(len) * 2);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
                ++i;
            }
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(jstr, chars);
    return out;
}

std::vector<std::string> ReadStringArray(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto* elem = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        out.push_back(JstringToUtf8(env, elem));
        if (elem) env->DeleteLocalRef(elem);
    }
    return out;
}

std::vector<int> ReadIntArray(JNIEnv* env, jintArray arr) {
    std::vector<int> out;
    if (!arr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.resize(static_cast<size_t>(n));
    if (n > 0) {
        env->GetIntArrayRegion(arr, 0, n, reinterpret_cast<jint*>(out.data()));
    }
    return out;
}

std::vector<bool> ReadBoolArray(JNIEnv* env, jbooleanArray arr) {
    std::vector<bool> out;
    if (!arr) return out;
    const jsize n = env->GetArrayLength(arr);
    if (n <= 0) return out;
    std::vector<jboolean> tmp(static_cast<size_t>(n));
    env->GetBooleanArrayRegion(arr, 0, n, tmp.data());
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        out.push_back(tmp[i] == JNI_TRUE);
    }
    return out;
}

std::vector<std::string> SplitLines(const std::string& packed) {
    std::vector<std::string> out;
    if (packed.empty()) return out;
    size_t start = 0;
    while (start <= packed.size()) {
        size_t nl = packed.find('\n', start);
        if (nl == std::string::npos) {
            out.push_back(packed.substr(start));
            break;
        }
        out.push_back(packed.substr(start, nl - start));
        start = nl + 1;
    }
    return out;
}

dcui::RgbaColor ArgbToRgbaColor(int32_t argb) {
    const float a = static_cast<float>((argb >> 24) & 0xFF) / 255.0f;
    const float r = static_cast<float>((argb >> 16) & 0xFF) / 255.0f;
    const float g = static_cast<float>((argb >> 8) & 0xFF) / 255.0f;
    const float b = static_cast<float>(argb & 0xFF) / 255.0f;
    return {r, g, b, a <= 0.0f ? 1.0f : a};
}

} // namespace

namespace dcui {

void HostNotifyGameLaunch(const std::string& gamePath) {
    JNIEnv* env = GetEnv();
    if (!env || !g_host_cls || !g_on_game_launch) return;
    jstring jpath = Utf8ToJstring(env, gamePath);
    env->CallStaticVoidMethod(g_host_cls, g_on_game_launch, jpath);
    env->DeleteLocalRef(jpath);
}

void HostNotifySaveLaunch(const std::string& gamePath, int slot) {
    JNIEnv* env = GetEnv();
    if (!env || !g_host_cls || !g_on_save_launch) return;
    jstring jpath = Utf8ToJstring(env, gamePath);
    env->CallStaticVoidMethod(g_host_cls, g_on_save_launch, jpath, static_cast<jint>(slot));
    env->DeleteLocalRef(jpath);
}

void HostNotifyDeleteSave(int saveIndex) {
    JNIEnv* env = GetEnv();
    if (!env || !g_host_cls || !g_on_delete_save) return;
    env->CallStaticVoidMethod(g_host_cls, g_on_delete_save, static_cast<jint>(saveIndex));
}

void HostNotifyVmuAction(int action, int cardIndex, int fileIndex) {
    JNIEnv* env = GetEnv();
    if (!env || !g_host_cls || !g_on_vmu_action) return;
    env->CallStaticVoidMethod(g_host_cls, g_on_vmu_action,
                              static_cast<jint>(action), static_cast<jint>(cardIndex), static_cast<jint>(fileIndex));
}

void HostNotifyOpenGameSettings(int gameIndex) {
    JNIEnv* env = GetEnv();
    if (!env || !g_host_cls || !g_on_open_game_settings) return;
    env->CallStaticVoidMethod(g_host_cls, g_on_open_game_settings, static_cast<jint>(gameIndex));
}

void HostNotifySettingsChanged(const int32_t* values, int count) {
    JNIEnv* env = GetEnv();
    if (!env || !g_host_cls || !g_on_settings_changed || !values || count <= 0) return;
    jintArray jarr = env->NewIntArray(count);
    if (!jarr) return;
    env->SetIntArrayRegion(jarr, 0, count, reinterpret_cast<const jint*>(values));
    env->CallStaticVoidMethod(g_host_cls, g_on_settings_changed, jarr);
    env->DeleteLocalRef(jarr);
}

void HostNotifyExitRequested() {
    JNIEnv* env = GetEnv();
    if (!env || !g_host_cls || !g_on_exit_requested) return;
    env->CallStaticVoidMethod(g_host_cls, g_on_exit_requested);
}

void HostPlaySfx(int kind) {
    JNIEnv* env = GetEnv();
    if (!env || !g_audio_cls || !g_play_sfx) return;
    env->CallStaticVoidMethod(g_audio_cls, g_play_sfx, static_cast<jint>(kind));
}

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
    std::vector<uint8_t>& outRgba) {
    JNIEnv* env = GetEnv();
    if (!env || !g_text_cls || !g_rasterize_text) return false;
    jstring jtext = Utf8ToJstring(env, text);
    auto* bytes = static_cast<jbyteArray>(env->CallStaticObjectMethod(
        g_text_cls,
        g_rasterize_text,
        jtext,
        static_cast<jfloat>(virtualFontSize),
        static_cast<jint>(style),
        static_cast<jfloat>(maxVirtualWidth),
        static_cast<jint>(maxLines),
        static_cast<jint>(align),
        static_cast<jfloat>(rasterScale),
        static_cast<jint>(shadowMode)));
    env->DeleteLocalRef(jtext);
    if (!bytes) return false;

    const jsize len = env->GetArrayLength(bytes);
    if (len < 16) {
        env->DeleteLocalRef(bytes);
        return false;
    }

    std::vector<uint8_t> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    env->DeleteLocalRef(bytes);

    int32_t hdr[4] = {};
    std::memcpy(hdr, buf.data(), 16);
    outTexW = hdr[0];
    outTexH = hdr[1];
    outLogW = static_cast<float>(hdr[2]) / 100.0f;
    outLogH = static_cast<float>(hdr[3]) / 100.0f;

    const size_t expected = 16 + static_cast<size_t>(outTexW) * static_cast<size_t>(outTexH) * 4;
    if (outTexW <= 0 || outTexH <= 0 || buf.size() < expected) return false;

    outRgba.assign(buf.begin() + 16, buf.begin() + static_cast<ptrdiff_t>(expected));
    return true;
}

void HostRequestDecodeImage(const std::string& path, int maxDim) {
    JNIEnv* env = GetEnv();
    if (!env || !g_img_cls || !g_request_decode || path.empty()) return;
    jstring jpath = Utf8ToJstring(env, path);
    env->CallStaticVoidMethod(g_img_cls, g_request_decode, jpath, static_cast<jint>(maxDim));
    env->DeleteLocalRef(jpath);
}

} // namespace dcui

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        return JNI_ERR;
    }

    auto cacheClass = [env](const char* name) -> jclass {
        jclass local = env->FindClass(name);
        if (!local) {
            LOGE("Failed to find class %s", name);
            return nullptr;
        }
        auto global = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        return global;
    };

    g_host_cls = cacheClass("com/sbro/emucoreh/dcui/DcShellHost");
    if (g_host_cls) {
        g_on_game_launch = env->GetStaticMethodID(g_host_cls, "onGameLaunch", "(Ljava/lang/String;)V");
        g_on_save_launch = env->GetStaticMethodID(g_host_cls, "onSaveLaunch", "(Ljava/lang/String;I)V");
        g_on_delete_save = env->GetStaticMethodID(g_host_cls, "onDeleteSave", "(I)V");
        g_on_vmu_action = env->GetStaticMethodID(g_host_cls, "onVmuAction", "(III)V");
        g_on_open_game_settings = env->GetStaticMethodID(g_host_cls, "onOpenGameSettings", "(I)V");
        g_on_settings_changed = env->GetStaticMethodID(g_host_cls, "onSettingsChanged", "([I)V");
        g_on_exit_requested = env->GetStaticMethodID(g_host_cls, "onExitRequested", "()V");
    }

    g_text_cls = cacheClass("com/sbro/emucoreh/dcui/DcTextRasterizer");
    if (g_text_cls) {
        g_rasterize_text = env->GetStaticMethodID(
            g_text_cls, "rasterizeText", "(Ljava/lang/String;FIFIIFI)[B");
    }

    g_img_cls = cacheClass("com/sbro/emucoreh/dcui/DcImageLoader");
    if (g_img_cls) {
        g_request_decode = env->GetStaticMethodID(
            g_img_cls, "requestDecode", "(Ljava/lang/String;I)V");
    }

    g_audio_cls = cacheClass("com/sbro/emucoreh/dcui/DcShellAudio");
    if (g_audio_cls) {
        g_play_sfx = env->GetStaticMethodID(g_audio_cls, "playSfx", "(I)V");
    }

    g_renderer = std::make_unique<dcui::DcRenderer>();
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeInit(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_renderer) g_renderer = std::make_unique<dcui::DcRenderer>();
    return g_renderer->Init() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeResize(
    JNIEnv* /*env*/, jobject /*thiz*/, jint width, jint height) {
    if (g_renderer) g_renderer->Resize(width, height);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeSetSafeInsets(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat leftPx, jfloat topPx, jfloat rightPx, jfloat bottomPx) {
    if (g_renderer) g_renderer->SetSafeInsets(leftPx, topPx, rightPx, bottomPx);
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeDraw(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_renderer) return JNI_FALSE;
    return g_renderer->Draw() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeShutdown(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_renderer) g_renderer->Shutdown();
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeKey(JNIEnv* /*env*/, jobject /*thiz*/, jint key) {
    if (g_renderer) g_renderer->OnKey(key);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeTouchDown(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat normX, jfloat normY) {
    if (g_renderer) g_renderer->OnTouchDown(normX, normY);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeTouchMove(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat normX, jfloat normY) {
    if (g_renderer) g_renderer->OnTouchMove(normX, normY);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeTouchUp(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat normX, jfloat normY) {
    if (g_renderer) g_renderer->OnTouchUp(normX, normY);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeTouchCancel(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_renderer) g_renderer->OnTouchCancel();
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeTouchTwoFingerBack(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_renderer) g_renderer->OnTouchTwoFingerBack();
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeTouchLongPress(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat normX, jfloat normY) {
    if (g_renderer) g_renderer->OnTouchLongPress(normX, normY);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeSetStrings(
    JNIEnv* env, jobject /*thiz*/, jobjectArray keys, jobjectArray values) {
    if (!g_renderer) return;
    auto k = ReadStringArray(env, keys);
    auto v = ReadStringArray(env, values);
    std::unordered_map<std::string, std::string> map;
    const size_t n = std::min(k.size(), v.size());
    map.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        if (!v[i].empty()) map[k[i]] = v[i];
    }
    g_renderer->SetStrings(std::move(map));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeSetConsoleTabs(
    JNIEnv* env, jobject /*thiz*/, jobjectArray tabIds, jobjectArray tabLabels, jintArray accentColors) {
    if (!g_renderer) return;
    auto ids = ReadStringArray(env, tabIds);
    auto lbls = ReadStringArray(env, tabLabels);
    auto cols = ReadIntArray(env, accentColors);
    const size_t n = ids.size();
    std::vector<dcui::ConsoleTabInfo> tabs;
    tabs.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        dcui::ConsoleTabInfo t;
        t.id = ids[i];
        t.label = (i < lbls.size() && !lbls[i].empty()) ? lbls[i] : ids[i];
        t.accent = (i < cols.size()) ? ArgbToRgbaColor(cols[i]) : dcui::RgbaColor{0.93f, 0.33f, 0.13f, 1.0f};
        tabs.push_back(std::move(t));
    }
    g_renderer->SetConsoleTabs(std::move(tabs));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeSetGames(
    JNIEnv* env,
    jobject /*thiz*/,
    jobjectArray titles,
    jobjectArray paths,
    jobjectArray serials,
    jobjectArray covers,
    jobjectArray sizes,
    jobjectArray dates,
    jobjectArray platformIds,
    jobjectArray platformLabels,
    jintArray saveCounts,
    jintArray latestSaveSlots,
    jintArray discCounts,
    jobjectArray discLabelsPacked,
    jobjectArray discPathsPacked,
    jintArray recentRanks) {
    if (!g_renderer) return;
    auto t = ReadStringArray(env, titles);
    auto p = ReadStringArray(env, paths);
    auto s = ReadStringArray(env, serials);
    auto c = ReadStringArray(env, covers);
    auto sz = ReadStringArray(env, sizes);
    auto dt = ReadStringArray(env, dates);
    auto pids = ReadStringArray(env, platformIds);
    auto plbls = ReadStringArray(env, platformLabels);
    auto dl = ReadStringArray(env, discLabelsPacked);
    auto dp = ReadStringArray(env, discPathsPacked);
    auto sc = ReadIntArray(env, saveCounts);
    auto ls = ReadIntArray(env, latestSaveSlots);
    auto dc = ReadIntArray(env, discCounts);
    auto rr = ReadIntArray(env, recentRanks);

    const size_t n = t.size();
    std::vector<dcui::GameEntry> games;
    games.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        dcui::GameEntry g;
        g.title = t[i];
        if (i < p.size()) g.path = p[i];
        if (i < s.size()) g.serial = s[i];
        if (i < c.size()) g.coverPath = c[i];
        if (i < sz.size()) g.sizeStr = sz[i];
        if (i < dt.size()) g.dateStr = dt[i];
        if (i < pids.size()) g.platformId = pids[i];
        if (i < plbls.size()) g.platformLabel = plbls[i];
        if (i < sc.size()) g.saveCount = sc[i];
        if (i < ls.size()) g.latestSaveSlot = ls[i];
        if (i < dc.size()) g.discCount = std::max(1, dc[i]);
        if (i < rr.size()) g.recentRank = rr[i];
        if (i < dl.size() && !dl[i].empty()) g.discLabels = SplitLines(dl[i]);
        if (i < dp.size() && !dp[i].empty()) g.discPaths = SplitLines(dp[i]);
        games.push_back(std::move(g));
    }
    g_renderer->SetGames(std::move(games));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeSetSaves(
    JNIEnv* env,
    jobject /*thiz*/,
    jobjectArray titles,
    jobjectArray slots,
    jintArray slotNums,
    jobjectArray dates,
    jobjectArray sizes,
    jobjectArray serials,
    jobjectArray platformLabels,
    jobjectArray previews,
    jobjectArray savePaths,
    jobjectArray gamePaths) {
    if (!g_renderer) return;
    auto t = ReadStringArray(env, titles);
    auto sl = ReadStringArray(env, slots);
    auto dt = ReadStringArray(env, dates);
    auto sz = ReadStringArray(env, sizes);
    auto ser = ReadStringArray(env, serials);
    auto pl = ReadStringArray(env, platformLabels);
    auto pv = ReadStringArray(env, previews);
    auto sp = ReadStringArray(env, savePaths);
    auto gp = ReadStringArray(env, gamePaths);
    auto sn = ReadIntArray(env, slotNums);

    const size_t n = t.size();
    std::vector<dcui::SaveEntry> saves;
    saves.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        dcui::SaveEntry e;
        e.title = t[i];
        if (i < sl.size()) e.slotLabel = sl[i];
        if (i < sn.size()) e.slotNum = sn[i];
        if (i < dt.size()) e.dateStr = dt[i];
        if (i < sz.size()) e.sizeStr = sz[i];
        if (i < ser.size()) e.serial = ser[i];
        if (i < pl.size()) e.platformLabel = pl[i];
        if (i < pv.size()) e.previewPath = pv[i];
        if (i < sp.size()) e.savePath = sp[i];
        if (i < gp.size()) e.gamePath = gp[i];
        saves.push_back(std::move(e));
    }
    g_renderer->SetSaves(std::move(saves));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeSetVmuCards(
    JNIEnv* env,
    jobject /*thiz*/,
    jobjectArray portNames,
    jobjectArray fileNames,
    jobjectArray sizes,
    jobjectArray dates,
    jintArray freeBlocks,
    jintArray totalBlocks,
    jbooleanArray formattedFlags,
    jintArray fileCounts) {
    if (!g_renderer) return;
    auto pn = ReadStringArray(env, portNames);
    auto fn = ReadStringArray(env, fileNames);
    auto sz = ReadStringArray(env, sizes);
    auto dt = ReadStringArray(env, dates);
    auto fb = ReadIntArray(env, freeBlocks);
    auto tb = ReadIntArray(env, totalBlocks);
    auto ff = ReadBoolArray(env, formattedFlags);
    auto fc = ReadIntArray(env, fileCounts);

    const size_t n = pn.size();
    std::vector<dcui::VmuCardEntry> cards;
    cards.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        dcui::VmuCardEntry c;
        c.portName = pn[i];
        if (i < fn.size()) c.fileName = fn[i];
        if (i < sz.size()) c.sizeStr = sz[i];
        if (i < dt.size()) c.dateStr = dt[i];
        if (i < fb.size()) c.freeBlocks = fb[i];
        if (i < tb.size()) c.totalBlocks = tb[i];
        if (i < ff.size()) c.formatted = ff[i];
        if (i < fc.size()) c.fileCount = fc[i];
        cards.push_back(std::move(c));
    }
    g_renderer->SetVmuCards(std::move(cards));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeSetVmuFiles(
    JNIEnv* env,
    jobject /*thiz*/,
    jint cardIndex,
    jobjectArray fileNames,
    jobjectArray descriptions,
    jobjectArray dates,
    jintArray blockCounts,
    jobjectArray iconPaths) {
    if (!g_renderer) return;
    auto fn = ReadStringArray(env, fileNames);
    auto desc = ReadStringArray(env, descriptions);
    auto dt = ReadStringArray(env, dates);
    auto bc = ReadIntArray(env, blockCounts);
    auto ip = ReadStringArray(env, iconPaths);

    const size_t n = fn.size();
    std::vector<dcui::VmuFileEntry> files;
    files.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        dcui::VmuFileEntry f;
        f.fileName = fn[i];
        if (i < desc.size()) f.description = desc[i];
        if (i < dt.size()) f.dateStr = dt[i];
        if (i < bc.size()) f.blocks = bc[i];
        if (i < ip.size()) f.iconPath = ip[i];
        files.push_back(std::move(f));
    }
    g_renderer->SetVmuFiles(cardIndex, std::move(files));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeSetSettings(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint theme,
    jint aspectMode,
    jint motionMode,
    jint scanlinesMode,
    jint bootAnimMode,
    jboolean keyTone,
    jboolean ambientSound,
    jboolean swapConfirm,
    jboolean touchControls,
    jboolean time24h,
    jint dateFormat,
    jboolean keepScreenOn) {
    if (!g_renderer) return;
    dcui::ShellSettings s;
    s.theme = theme;
    s.aspectMode = aspectMode;
    s.motionMode = motionMode;
    s.scanlinesMode = scanlinesMode;
    s.bootAnimMode = bootAnimMode;
    s.keyTone = (keyTone == JNI_TRUE);
    s.ambientSound = (ambientSound == JNI_TRUE);
    s.swapConfirm = (swapConfirm == JNI_TRUE);
    s.touchControls = (touchControls == JNI_TRUE);
    s.time24h = (time24h == JNI_TRUE);
    s.dateFormat = dateFormat;
    s.keepScreenOn = (keepScreenOn == JNI_TRUE);
    g_renderer->SetSettings(s);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeSetSystemStatus(
    JNIEnv* env,
    jobject /*thiz*/,
    jint batteryLevel,
    jboolean batteryCharging,
    jboolean wifiConnected,
    jboolean bluetoothConnected,
    jboolean audioMuted,
    jstring freeStorage,
    jstring deviceName,
    jstring appVersion,
    jstring biosSummary,
    jint biosReadyCount,
    jint biosTotalCount) {
    if (!g_renderer) return;
    dcui::SystemStatus st;
    st.batteryLevel = batteryLevel;
    st.batteryCharging = (batteryCharging == JNI_TRUE);
    st.wifiConnected = (wifiConnected == JNI_TRUE);
    st.bluetoothConnected = (bluetoothConnected == JNI_TRUE);
    st.audioMuted = (audioMuted == JNI_TRUE);
    st.freeStorage = JstringToUtf8(env, freeStorage);
    st.deviceName = JstringToUtf8(env, deviceName);
    st.appVersion = JstringToUtf8(env, appVersion);
    st.biosSummary = JstringToUtf8(env, biosSummary);
    st.biosReadyCount = biosReadyCount;
    st.biosTotalCount = biosTotalCount;
    g_renderer->SetSystemStatus(st);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeSetTouchMode(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean active, jboolean showTutorial) {
    if (g_renderer) g_renderer->SetTouchMode(active == JNI_TRUE, showTutorial == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeSetLoading(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean gamesLoading, jboolean savesLoading, jboolean vmuLoading) {
    if (g_renderer) g_renderer->SetLoading(gamesLoading == JNI_TRUE, savesLoading == JNI_TRUE, vmuLoading == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeTriggerTransition(
    JNIEnv* /*env*/, jobject /*thiz*/, jint kind) {
    if (g_renderer) g_renderer->TriggerTransition(kind);
}

JNIEXPORT jintArray JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeGetState(JNIEnv* env, jobject /*thiz*/) {
    std::vector<int> st = g_renderer ? g_renderer->GetState() : std::vector<int>{0, 0, 0, 0, 0, 0, 0};
    jintArray out = env->NewIntArray(static_cast<jsize>(st.size()));
    if (out && !st.empty()) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(st.size()), reinterpret_cast<const jint*>(st.data()));
    }
    return out;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeSetState(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint screen, jint mainIndex, jint tabIndex, jint gameIndex,
    jint fileTab, jint vmuCardIndex, jint saveIndex) {
    if (g_renderer) {
        g_renderer->SetState(screen, mainIndex, tabIndex, gameIndex, fileTab, vmuCardIndex, saveIndex);
    }
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_dcui_DcShellNative_nativeNotifyImageDecoded(
    JNIEnv* env, jobject /*thiz*/, jstring path, jint width, jint height, jbyteArray rgba) {
    if (!g_renderer || !path) return;
    const std::string p = JstringToUtf8(env, path);
    std::vector<uint8_t> bytes;
    if (rgba && width > 0 && height > 0) {
        const jsize len = env->GetArrayLength(rgba);
        if (len > 0) {
            bytes.resize(static_cast<size_t>(len));
            env->GetByteArrayRegion(rgba, 0, len, reinterpret_cast<jbyte*>(bytes.data()));
        }
    }
    g_renderer->NotifyImageDecoded(p, width, height, std::move(bytes));
}

} // extern "C"
