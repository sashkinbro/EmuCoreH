// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once

#include <vulkan/vulkan.hpp>
#include <limits>
#include <map>
#include <stdexcept>
#include <vector>

// OIT screen and RTT allocators must not compete for the context's tiny
// fixed storage/input-attachment budget. Keep each chunk's pool alive until
// every descriptor allocated from it has been freed.
template<typename Bindings>
std::vector<vk::UniqueDescriptorSet> allocateDescriptorPoolChunk(vk::Device device,
        const std::vector<vk::DescriptorSetLayout>& layouts, const Bindings& bindings,
        std::vector<vk::UniqueDescriptorPool>& pools)
{
    if (layouts.empty()) return {};
    if (layouts.size() > std::numeric_limits<uint32_t>::max())
        throw std::overflow_error("Descriptor pool set count overflow");
    const auto count = static_cast<uint32_t>(layouts.size());
    std::map<vk::DescriptorType, uint64_t> counts;
    for (const auto& binding : bindings)
        counts[binding.descriptorType] += uint64_t(binding.descriptorCount) * count;
    std::vector<vk::DescriptorPoolSize> sizes;
    for (const auto& [type, descriptors] : counts) {
        if (descriptors > std::numeric_limits<uint32_t>::max())
            throw std::overflow_error("Descriptor pool type count overflow");
        if (descriptors != 0) sizes.emplace_back(type, static_cast<uint32_t>(descriptors));
    }
    auto pool = device.createDescriptorPoolUnique(vk::DescriptorPoolCreateInfo(
        vk::DescriptorPoolCreateFlagBits::eFreeDescriptorSet, count, sizes));
    auto sets = device.allocateDescriptorSetsUnique(vk::DescriptorSetAllocateInfo(*pool, layouts));
    pools.push_back(std::move(pool));
    return sets;
}
