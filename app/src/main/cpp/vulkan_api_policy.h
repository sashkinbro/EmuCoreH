// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <cstdint>

namespace emucoreh::vulkan {

// Flycast's Vulkan backend and its VMA allocator use Vulkan 1.1 core entry
// points (vkGetBufferMemoryRequirements2, vkBindMemory2, ...) whenever the
// physical device advertises 1.1. The device API version is capped by the
// instance API version at vkCreateDevice time, so creating the instance as
// Vulkan 1.0 leaves those dispatch pointers null and crashes the emulator on
// startup (reported on Pixel devices). Create the instance with at least 1.1
// whenever the loader supports it.
constexpr uint32_t kMinimumCoreVulkanApi = (1u << 22) | (1u << 12); // VK_MAKE_VERSION(1, 1, 0)

// Picks the instance API version: never below [kMinimumCoreVulkanApi], never
// above what the loader supports. Returns 0 when the loader cannot satisfy the
// minimum.
constexpr uint32_t SelectCoreVulkanApi(uint32_t loaderApi, uint32_t requestedApi) {
    const uint32_t api = requestedApi < kMinimumCoreVulkanApi ? kMinimumCoreVulkanApi : requestedApi;
    return loaderApi >= api ? api : 0;
}

} // namespace emucoreh::vulkan
