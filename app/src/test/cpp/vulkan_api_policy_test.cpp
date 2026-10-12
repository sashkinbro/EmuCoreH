// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-2.0-or-later

#include "../../main/cpp/vulkan_api_policy.h"

#include <cassert>

int main() {
    using emucoreh::vulkan::kMinimumCoreVulkanApi;
    using emucoreh::vulkan::SelectCoreVulkanApi;
    constexpr uint32_t api10 = 1u << 22;
    constexpr uint32_t api11 = api10 | (1u << 12);
    constexpr uint32_t api12 = api10 | (2u << 12);
    constexpr uint32_t api13 = api10 | (3u << 12);
    static_assert(kMinimumCoreVulkanApi == api11);

    // A 1.0 loader cannot satisfy the 1.1 minimum.
    assert(SelectCoreVulkanApi(api10, api10) == 0);
    // A 1.1 loader always yields at least 1.1, even for a 1.0 request.
    assert(SelectCoreVulkanApi(api11, api10) == api11);
    // No request is clamped down either.
    assert(SelectCoreVulkanApi(api13, 0) == api11);
    assert(SelectCoreVulkanApi(api13, api12) == api12);
    // The loader is never promised more than it supports.
    assert(SelectCoreVulkanApi(api11, api12) == 0);
    return 0;
}
