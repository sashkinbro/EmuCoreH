// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-2.0-or-later
// Compile with core/core/deps/Vulkan-Headers/include; no GPU or Vulkan loader.
#include "../../../../core/core/rend/vulkan/descriptor_pool_chunk.h"
#include "../../../../core/core/rend/vulkan/oit/oit_descriptor_layout.h"

#include <array>
#include <cassert>
#include <cstdio>
#include <map>
#include <type_traits>

namespace {
using Counts = std::map<VkDescriptorType, uint32_t>;
struct Pool { Counts remaining; uint32_t remainingSets; uint32_t maxSets; };
struct Set { VkDescriptorPool pool; Counts counts; bool pending = false; };
std::map<VkDescriptorPool, Pool> pools;
std::map<VkDescriptorSetLayout, Counts> layouts;
std::map<VkDescriptorSet, Set> sets;
unsigned createdPools;
unsigned resetPools;
bool failNextAllocation;
uintptr_t nextHandle = 1;

template<typename Handle>
Handle handle() {
    if constexpr (std::is_pointer_v<Handle>) return reinterpret_cast<Handle>(nextHandle++);
    else return static_cast<Handle>(nextHandle++);
}
}

extern "C" {
VKAPI_ATTR VkResult VKAPI_CALL vkCreateDescriptorSetLayout(VkDevice, const VkDescriptorSetLayoutCreateInfo* info,
        const VkAllocationCallbacks*, VkDescriptorSetLayout* layout) {
    *layout = handle<VkDescriptorSetLayout>();
    for (uint32_t i = 0; i < info->bindingCount; ++i)
        layouts[*layout][info->pBindings[i].descriptorType] += info->pBindings[i].descriptorCount;
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL vkDestroyDescriptorSetLayout(VkDevice, VkDescriptorSetLayout layout, const VkAllocationCallbacks*) {
    layouts.erase(layout);
}
VKAPI_ATTR VkResult VKAPI_CALL vkCreateDescriptorPool(VkDevice, const VkDescriptorPoolCreateInfo* info,
        const VkAllocationCallbacks*, VkDescriptorPool* pool) {
    assert((info->flags & VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT) != 0);
    *pool = handle<VkDescriptorPool>();
    pools[*pool] = {{}, info->maxSets, info->maxSets};
    for (uint32_t i = 0; i < info->poolSizeCount; ++i)
        pools[*pool].remaining[info->pPoolSizes[i].type] += info->pPoolSizes[i].descriptorCount;
    ++createdPools;
    return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL vkAllocateDescriptorSets(VkDevice, const VkDescriptorSetAllocateInfo* info, VkDescriptorSet* output) {
    if (failNextAllocation) {
        failNextAllocation = false;
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }
    Counts requested;
    for (uint32_t i = 0; i < info->descriptorSetCount; ++i)
        for (auto [type, count] : layouts.at(info->pSetLayouts[i])) requested[type] += count;
    auto& pool = pools.at(info->descriptorPool);
    if (info->descriptorSetCount > pool.remainingSets) return VK_ERROR_OUT_OF_POOL_MEMORY;
    for (auto [type, count] : requested)
        if (pool.remaining[type] < count) return VK_ERROR_OUT_OF_POOL_MEMORY;
    pool.remainingSets -= info->descriptorSetCount;
    for (auto [type, count] : requested) pool.remaining[type] -= count;
    for (uint32_t i = 0; i < info->descriptorSetCount; ++i) {
        output[i] = handle<VkDescriptorSet>();
        sets[output[i]] = {info->descriptorPool, layouts.at(info->pSetLayouts[i])};
    }
    return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL vkFreeDescriptorSets(VkDevice, VkDescriptorPool pool, uint32_t count, const VkDescriptorSet* freed) {
    for (uint32_t i = 0; i < count; ++i) {
        const auto& set = sets.at(freed[i]);
        assert(set.pool == pool && !set.pending);
        for (auto [type, used] : set.counts) pools.at(pool).remaining[type] += used;
        ++pools.at(pool).remainingSets;
        sets.erase(freed[i]);
    }
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL vkDestroyDescriptorPool(VkDevice, VkDescriptorPool pool, const VkAllocationCallbacks*) {
    // Vulkan-Hpp UniqueDescriptorSets retain the allocating pool in their
    // deleter. Destroying it first would make their subsequent free invalid.
    for (const auto& [id, set] : sets) { (void)id; assert(set.pool != pool); }
    pools.erase(pool);
}
VKAPI_ATTR VkResult VKAPI_CALL vkResetDescriptorPool(VkDevice, VkDescriptorPool, VkDescriptorPoolResetFlags) {
    ++resetPools;
    return VK_SUCCESS;
}
}

struct Fixture {
    vk::Device device{handle<VkDevice>()};
    vk::UniqueDescriptorSetLayout frameLayout;
    vk::UniqueDescriptorSetLayout colorLayout;
    vk::UniqueDescriptorPool shared;
    // Pools precede their sets in production as well, enforcing RAII order.
    std::vector<vk::UniqueDescriptorPool> retained;
    Fixture() {
        const auto frameBindings = oitPerFrameDescriptorBindings();
        const auto colorBindings = oitColorInputDescriptorBindings();
        frameLayout = device.createDescriptorSetLayoutUnique(vk::DescriptorSetLayoutCreateInfo({}, frameBindings));
        colorLayout = device.createDescriptorSetLayoutUnique(vk::DescriptorSetLayoutCreateInfo({}, colorBindings));
        // Exact relevant libretro context budgets, with ample other descriptors.
        const std::array<vk::DescriptorPoolSize, 4> sizes = {
            vk::DescriptorPoolSize(vk::DescriptorType::eStorageBuffer, 50),
            vk::DescriptorPoolSize(vk::DescriptorType::eInputAttachment, 50),
            vk::DescriptorPoolSize(vk::DescriptorType::eUniformBuffer, 80000),
            vk::DescriptorPoolSize(vk::DescriptorType::eCombinedImageSampler, 40000)
        };
        shared = device.createDescriptorPoolUnique(vk::DescriptorPoolCreateInfo(vk::DescriptorPoolCreateFlagBits::eFreeDescriptorSet, 40000, sizes));
    }
    auto frames(unsigned count = 10) {
        return allocateDescriptorPoolChunk(device, std::vector<vk::DescriptorSetLayout>(count, *frameLayout),
            oitPerFrameDescriptorBindings(), retained);
    }
    auto colors(unsigned count = 10) {
        return allocateDescriptorPoolChunk(device, std::vector<vk::DescriptorSetLayout>(count, *colorLayout),
            oitColorInputDescriptorBindings(), retained);
    }
};

static bool ScreenAndRttDoNotExhaustSharedBudget() {
    Fixture f;
    auto screen = f.frames();
    auto screenColors = f.colors();
    auto rtt = f.frames();
    auto rttColors = f.colors();
    return screen.size() == 10 && rtt.size() == 10 && screenColors.size() == 10 && rttColors.size() == 10 &&
        f.retained.size() == 4 && pools.at(static_cast<VkDescriptorPool>(*f.shared)).remainingSets == 40000;
}

static bool RepeatedRttPassesReuseSetsAfterThreeFramesWithoutResettingPools() {
    Fixture f;
    std::array<std::vector<vk::UniqueDescriptorSet>, 3> inFlightFrames;
    std::array<std::vector<vk::UniqueDescriptorSet>, 3> inFlightColors;
    std::vector<vk::UniqueDescriptorSet> freeFrames;
    std::vector<vk::UniqueDescriptorSet> freeColors;
    struct WaitIdleOnExit {
        ~WaitIdleOnExit() {
            for (auto& [id, set] : sets) { (void)id; set.pending = false; }
        }
    } idle;
    unsigned warmedPools = 0;
    for (unsigned frame = 0; frame < 500; ++frame) {
        const unsigned slot = frame % 3;
        auto retireAfterFence = [&](auto& flight, auto& reusable) {
            // Model the actual CommandPool::BeginFrame fence wait, which runs
            // before OIT NewImage/nextFrame recycle these descriptor sets.
            for (auto& set : flight) {
                sets.at(static_cast<VkDescriptorSet>(*set)).pending = false;
                reusable.push_back(std::move(set));
            }
            flight.clear();
        };
        retireAfterFence(inFlightFrames[slot], freeFrames);
        retireAfterFence(inFlightColors[slot], freeColors);
        auto acquire = [&](auto& free, auto& flight, bool color) {
            if (free.empty()) {
                auto chunk = color ? f.colors() : f.frames();
                for (auto& set : chunk) free.push_back(std::move(set));
            }
            flight.push_back(std::move(free.back()));
            free.pop_back();
            sets.at(static_cast<VkDescriptorSet>(*flight.back())).pending = true;
        };
        // One screen + seven RTT passes per frame, two color descriptors/pass.
        for (unsigned pass = 0; pass < 8; ++pass) {
            acquire(freeFrames, inFlightFrames[slot], false);
            acquire(freeColors, inFlightColors[slot], true);
            acquire(freeColors, inFlightColors[slot], true);
        }
        if (frame == 10) warmedPools = createdPools;
    }
    // Final device idle precedes descriptor destruction in renderer teardown.
    for (auto& [id, set] : sets) { (void)id; set.pending = false; }
    return resetPools == 0 && createdPools == warmedPools;
}

static bool ChunkBudgetMatchesRegisteredBindingsAndMaxSets() {
    Fixture f;
    auto frame = f.frames(23);
    if (f.retained.empty()) return false;
    const auto& pool = pools.at(static_cast<VkDescriptorPool>(*f.retained.back()));
    // Allocation consumed every descriptor in its exact per-type budget.
    if (pool.maxSets != 23 || pool.remainingSets != 0) return false;
    for (auto [type, remaining] : pool.remaining) { (void)type; if (remaining != 0) return false; }
    return frame.size() == 23 && sets.at(static_cast<VkDescriptorSet>(*frame[0])).counts == Counts {
        {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4}, {VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT, 2},
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 2}, {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2}
    };
}

static bool FailedAllocationDoesNotRetainOrLeakItsPool() {
    Fixture f;
    const auto before = pools.size();
    failNextAllocation = true;
    try { auto unused = f.frames(); (void)unused; }
    catch (const vk::SystemError&) { return f.retained.empty() && pools.size() == before && sets.empty(); }
    return false;
}

int main() {
    int failures = 0;
    auto check = [&](auto test, const char* name) {
        bool success = false;
        try { success = test(); } catch (const vk::SystemError& e) { std::printf("%s\n", e.what()); }
        std::printf("%s %s\n", success ? "PASS" : "FAIL", name);
        failures += !success;
        assert(sets.empty() && pools.empty());
    };
    check(ScreenAndRttDoNotExhaustSharedBudget, "screen and first RTT pass have independent complete OIT descriptor budgets");
    check(RepeatedRttPassesReuseSetsAfterThreeFramesWithoutResettingPools, "500 frames with eight passes and three in-flight frames keep pools valid and bounded");
    check(ChunkBudgetMatchesRegisteredBindingsAndMaxSets, "pool counts come from actual registered layout and allocation chunk maxSets");
    check(FailedAllocationDoesNotRetainOrLeakItsPool, "allocation error releases newly created pool without destroying other pools");
    return failures == 0 ? 0 : 1;
}
