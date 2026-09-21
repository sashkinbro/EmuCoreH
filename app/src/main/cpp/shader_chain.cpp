// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary

#include "shader_chain.h"

#include <mutex>

namespace emucoreh::shader_chain {
namespace {

std::mutex g_mutex;
std::string g_path;
bool g_enabled = false;
uint64_t g_generation = 0;

}  // namespace

void SetPreset(std::string path, bool enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_path = std::move(path);
    g_enabled = enabled && !g_path.empty();
    ++g_generation;
}

bool IsEnabled() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_enabled;
}

std::string PresetPath() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_path;
}

uint64_t Generation() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_generation;
}

}  // namespace emucoreh::shader_chain
