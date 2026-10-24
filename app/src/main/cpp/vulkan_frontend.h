// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary

#pragma once

#include <android/native_window.h>
#include <cstddef>
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
// True when context setup failed for the current window/session; transient
// "no window yet" states do not set it.
bool HasFailed();

void NotifyContextDestroy();
void SetShaderEffect(int effect);
void SetDisplayCrop(int left, int top, int right, int bottom);

bool EnsureContext(ANativeWindow* window, uint32_t window_generation);
bool Present(uint32_t source_width, uint32_t source_height, double display_aspect, bool stretch,
             bool adjust_aspect_for_crop);
// Upload an XRGB8888 software frame and present it through the same Vulkan
// blit/effect/shader-chain path as a hardware-rendered core.
bool PresentSoftware(const void* data, uint32_t width, uint32_t height, size_t pitch,
                     double display_aspect, bool stretch, bool adjust_aspect_for_crop);

void Destroy();

}  // namespace emucoreh::vulkan

