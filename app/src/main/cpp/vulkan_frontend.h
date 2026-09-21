// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary

#pragma once

#include <android/native_window.h>
#include <cstdint>

#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR
#endif

#include "libretro.h"
#include "libretro_vulkan.h"

namespace emucoreh::vulkan {

struct PresentRect {
    int x;
    int y;
    int width;
    int height;
};

bool Prepare(ANativeWindow* window, uint32_t window_generation);

bool AcceptHardwareRender(retro_hw_render_callback* callback);
bool AcceptNegotiationInterface(const retro_hw_render_context_negotiation_interface* negotiation);
bool FillHardwareRenderInterface(retro_hw_render_interface** out_interface);

bool IsRequested();
bool IsActive();

void NotifyContextDestroy();
void SetShaderEffect(int effect);
void SetDisplayCrop(int left, int top, int right, int bottom);

bool EnsureContext(ANativeWindow* window, uint32_t window_generation);
bool Present(uint32_t source_width, uint32_t source_height, double display_aspect, bool stretch,
             bool adjust_aspect_for_crop);

void Destroy();

}  // namespace emucoreh::vulkan

