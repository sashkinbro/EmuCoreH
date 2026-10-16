// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-2.0-or-later
//
// On-device probe for the Android "lying driver" workaround in
// core/core/rend/vulkan/vk_context_lr.cpp. It replicates the libretro core's
// device creation step by step:
//
//   1. creates a 1.1 instance (like the frontend),
//   2. picks a physical device and queries the base features plus the
//      provoking-vertex / buffer-device-address feature structs,
//   3. calls vkCreateDevice with the same feature profiles as the core's
//      retry ladder (driven by vk_device_retry_policy.h),
//   4. when the full profile is rejected with VK_ERROR_FEATURE_NOT_PRESENT,
//      bisects the reported features to name the exact liar and verifies that
//      the reduced profile succeeds.
//
// Build (arm64 device, NDK):
//   aarch64-linux-android26-clang++ -std=c++17 -O2 -static-libstdc++ \
//       vulkan_device_feature_probe.cpp -lvulkan -o vulkan_device_feature_probe
// Run:
//   adb push vulkan_device_feature_probe /data/local/tmp/
//   adb shell /data/local/tmp/vulkan_device_feature_probe
#include "../../../../core/core/rend/vulkan/vk_device_retry_policy.h"

#include <vulkan/vulkan.h>

#include <cstddef>
#include <cstdio>
#include <cstring>

namespace {

constexpr int kMaxDeviceExtensions = 256;

const char* ResultName(VkResult result) {
    switch (result) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_INCOMPLETE: return "VK_INCOMPLETE";
        case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
        case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        default: return "VK_ERROR_OTHER";
    }
}

struct FeatureEntry {
    const char* name;
    size_t offset;
};

#define FEATURE_ENTRY(field) {#field, offsetof(VkPhysicalDeviceFeatures, field)}

const FeatureEntry kBaseFeatures[] = {
    FEATURE_ENTRY(robustBufferAccess),
    FEATURE_ENTRY(fullDrawIndexUint32),
    FEATURE_ENTRY(imageCubeArray),
    FEATURE_ENTRY(independentBlend),
    FEATURE_ENTRY(geometryShader),
    FEATURE_ENTRY(tessellationShader),
    FEATURE_ENTRY(sampleRateShading),
    FEATURE_ENTRY(dualSrcBlend),
    FEATURE_ENTRY(logicOp),
    FEATURE_ENTRY(multiDrawIndirect),
    FEATURE_ENTRY(drawIndirectFirstInstance),
    FEATURE_ENTRY(depthClamp),
    FEATURE_ENTRY(depthBiasClamp),
    FEATURE_ENTRY(fillModeNonSolid),
    FEATURE_ENTRY(depthBounds),
    FEATURE_ENTRY(wideLines),
    FEATURE_ENTRY(largePoints),
    FEATURE_ENTRY(alphaToOne),
    FEATURE_ENTRY(multiViewport),
    FEATURE_ENTRY(samplerAnisotropy),
    FEATURE_ENTRY(textureCompressionETC2),
    FEATURE_ENTRY(textureCompressionASTC_LDR),
    FEATURE_ENTRY(textureCompressionBC),
    FEATURE_ENTRY(occlusionQueryPrecise),
    FEATURE_ENTRY(pipelineStatisticsQuery),
    FEATURE_ENTRY(vertexPipelineStoresAndAtomics),
    FEATURE_ENTRY(fragmentStoresAndAtomics),
    FEATURE_ENTRY(shaderTessellationAndGeometryPointSize),
    FEATURE_ENTRY(shaderImageGatherExtended),
    FEATURE_ENTRY(shaderStorageImageExtendedFormats),
    FEATURE_ENTRY(shaderStorageImageMultisample),
    FEATURE_ENTRY(shaderStorageImageReadWithoutFormat),
    FEATURE_ENTRY(shaderStorageImageWriteWithoutFormat),
    FEATURE_ENTRY(shaderUniformBufferArrayDynamicIndexing),
    FEATURE_ENTRY(shaderSampledImageArrayDynamicIndexing),
    FEATURE_ENTRY(shaderStorageBufferArrayDynamicIndexing),
    FEATURE_ENTRY(shaderStorageImageArrayDynamicIndexing),
    FEATURE_ENTRY(shaderClipDistance),
    FEATURE_ENTRY(shaderCullDistance),
    FEATURE_ENTRY(shaderFloat64),
    FEATURE_ENTRY(shaderInt64),
    FEATURE_ENTRY(shaderInt16),
    FEATURE_ENTRY(shaderResourceResidency),
    FEATURE_ENTRY(shaderResourceMinLod),
    FEATURE_ENTRY(sparseBinding),
    FEATURE_ENTRY(sparseResidencyBuffer),
    FEATURE_ENTRY(sparseResidencyImage2D),
    FEATURE_ENTRY(sparseResidencyImage3D),
    FEATURE_ENTRY(sparseResidency2Samples),
    FEATURE_ENTRY(sparseResidency4Samples),
    FEATURE_ENTRY(sparseResidency8Samples),
    FEATURE_ENTRY(sparseResidency16Samples),
    FEATURE_ENTRY(sparseResidencyAliased),
    FEATURE_ENTRY(variableMultisampleRate),
    FEATURE_ENTRY(inheritedQueries),
};

#undef FEATURE_ENTRY

VkBool32* FeatureAt(VkPhysicalDeviceFeatures& features, const FeatureEntry& entry) {
    return reinterpret_cast<VkBool32*>(reinterpret_cast<char*>(&features) + entry.offset);
}

VkInstance g_instance = VK_NULL_HANDLE;
VkPhysicalDevice g_gpu = VK_NULL_HANDLE;
uint32_t g_queue_family = 0;
char g_extensions[kMaxDeviceExtensions][VK_MAX_EXTENSION_NAME_SIZE];
int g_extension_count = 0;
bool g_has_buffer_device_address = false;
bool g_has_provoking_vertex = false;
PFN_vkGetPhysicalDeviceFeatures2 g_get_features2 = nullptr;

bool HasExtension(const char* name) {
    for (int i = 0; i < g_extension_count; ++i) {
        if (std::strcmp(g_extensions[i], name) == 0) return true;
    }
    return false;
}

// Mirrors the core's tryAddDeviceExtension: every supported extension is
// enabled for the device regardless of the feature profile.
const char* const* EnabledExtensions(uint32_t& count) {
    static const char* names[kMaxDeviceExtensions];
    count = 0;
    if (HasExtension(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
        names[count++] = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
    }
    if (g_has_buffer_device_address) names[count++] = VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME;
    if (g_has_provoking_vertex) names[count++] = VK_EXT_PROVOKING_VERTEX_EXTENSION_NAME;
    return names;
}

// Creates and immediately destroys a device with the given feature profile,
// exactly like VkCreateDevice in vk_context_lr.cpp does.
VkResult TryCreateDevice(const VkPhysicalDeviceFeatures& features,
                         const VkPhysicalDeviceBufferDeviceAddressFeaturesKHR* bda,
                         const VkPhysicalDeviceProvokingVertexFeaturesEXT* provoking_vertex) {
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info{};
    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queueFamilyIndex = g_queue_family;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &priority;

    uint32_t extension_count = 0;
    const char* const* extensions = EnabledExtensions(extension_count);

    VkDeviceCreateInfo create_info{};
    create_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    create_info.queueCreateInfoCount = 1;
    create_info.pQueueCreateInfos = &queue_info;
    create_info.enabledExtensionCount = extension_count;
    create_info.ppEnabledExtensionNames = extensions;

    VkPhysicalDeviceFeatures2 features2{};
    features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features2.features = features;
    void* tail = nullptr;
    if (bda != nullptr) {
        features2.pNext = const_cast<VkPhysicalDeviceBufferDeviceAddressFeaturesKHR*>(bda);
        tail = const_cast<VkPhysicalDeviceBufferDeviceAddressFeaturesKHR*>(bda);
    }
    if (provoking_vertex != nullptr) {
        auto* pv = const_cast<VkPhysicalDeviceProvokingVertexFeaturesEXT*>(provoking_vertex);
        if (tail != nullptr) {
            static_cast<VkBaseOutStructure*>(tail)->pNext = reinterpret_cast<VkBaseOutStructure*>(pv);
        } else {
            features2.pNext = pv;
        }
    }
    create_info.pNext = &features2;

    VkDevice device = VK_NULL_HANDLE;
    const VkResult result = vkCreateDevice(g_gpu, &create_info, nullptr, &device);
    if (result == VK_SUCCESS) vkDestroyDevice(device, nullptr);
    return result;
}

VkResult TryProfile(vk_retry_policy::FeatureProfile profile,
                    const VkPhysicalDeviceFeatures& reported,
                    const VkPhysicalDeviceBufferDeviceAddressFeaturesKHR& reported_bda,
                    const VkPhysicalDeviceProvokingVertexFeaturesEXT& reported_pv) {
    switch (profile) {
        case vk_retry_policy::FeatureProfile::Full: {
            // Everything the device reported, extension structs included.
            // Unsupported extension structs stay out of the chain, exactly
            // like the core's unlink() calls at setup.
            return TryCreateDevice(reported,
                                   g_has_buffer_device_address ? &reported_bda : nullptr,
                                   g_has_provoking_vertex ? &reported_pv : nullptr);
        }
        case vk_retry_policy::FeatureProfile::Core: {
            // Only the two features the renderer toggles, extension structs off.
            VkPhysicalDeviceFeatures features{};
            features.samplerAnisotropy = reported.samplerAnisotropy;
            features.fragmentStoresAndAtomics = reported.fragmentStoresAndAtomics;
            return TryCreateDevice(features, nullptr, nullptr);
        }
        case vk_retry_policy::FeatureProfile::None: {
            VkPhysicalDeviceFeatures features{};
            return TryCreateDevice(features, nullptr, nullptr);
        }
    }
    return VK_ERROR_UNKNOWN;
}

const char* ProfileName(vk_retry_policy::FeatureProfile profile) {
    switch (profile) {
        case vk_retry_policy::FeatureProfile::Full: return "Full";
        case vk_retry_policy::FeatureProfile::Core: return "Core";
        case vk_retry_policy::FeatureProfile::None: return "None";
    }
    return "?";
}

bool CreateInstance() {
    PFN_vkEnumerateInstanceVersion enumerate_version =
        reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    uint32_t loader_api = VK_API_VERSION_1_0;
    if (enumerate_version != nullptr && enumerate_version(&loader_api) != VK_SUCCESS) {
        std::printf("probe: unable to query the loader version\n");
        return false;
    }
    if (loader_api < VK_API_VERSION_1_1) {
        std::printf("probe: loader only provides %u.%u\n", VK_VERSION_MAJOR(loader_api),
                    VK_VERSION_MINOR(loader_api));
        return false;
    }

    VkApplicationInfo app_info{};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "EmuCoreH feature probe";
    app_info.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo create_info{};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;

    const VkResult result = vkCreateInstance(&create_info, nullptr, &g_instance);
    if (result != VK_SUCCESS) {
        std::printf("probe: vkCreateInstance -> %s (%d)\n", ResultName(result), result);
        return false;
    }
    g_get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
        vkGetInstanceProcAddr(g_instance, "vkGetPhysicalDeviceFeatures2"));
    return g_get_features2 != nullptr;
}

bool PickDevice() {
    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(g_instance, &count, nullptr) != VK_SUCCESS || count == 0) {
        std::printf("probe: no physical devices\n");
        return false;
    }
    VkPhysicalDevice devices[8];
    if (count > 8) count = 8;
    vkEnumeratePhysicalDevices(g_instance, &count, devices);
    g_gpu = devices[0];

    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(g_gpu, &properties);
    std::printf("probe: device '%s' api %u.%u.%u driver 0x%x\n", properties.deviceName,
                VK_VERSION_MAJOR(properties.apiVersion), VK_VERSION_MINOR(properties.apiVersion),
                VK_VERSION_PATCH(properties.apiVersion), properties.driverVersion);

    uint32_t queue_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(g_gpu, &queue_count, nullptr);
    VkQueueFamilyProperties queue_properties[16];
    if (queue_count > 16) queue_count = 16;
    vkGetPhysicalDeviceQueueFamilyProperties(g_gpu, &queue_count, queue_properties);
    g_queue_family = queue_count;
    for (uint32_t i = 0; i < queue_count; ++i) {
        const auto flags = queue_properties[i].queueFlags;
        if ((flags & VK_QUEUE_GRAPHICS_BIT) != 0 && (flags & VK_QUEUE_COMPUTE_BIT) != 0) {
            g_queue_family = i;
            break;
        }
    }
    if (g_queue_family >= queue_count) {
        std::printf("probe: no graphics+compute queue family\n");
        return false;
    }

    uint32_t extension_count = 0;
    if (vkEnumerateDeviceExtensionProperties(g_gpu, nullptr, &extension_count, nullptr) != VK_SUCCESS) {
        return false;
    }
    VkExtensionProperties* extensions =
        new VkExtensionProperties[extension_count < kMaxDeviceExtensions ? extension_count
                                                                          : kMaxDeviceExtensions];
    uint32_t readable = extension_count < kMaxDeviceExtensions ? extension_count : kMaxDeviceExtensions;
    vkEnumerateDeviceExtensionProperties(g_gpu, nullptr, &readable, extensions);
    g_extension_count = 0;
    for (uint32_t i = 0; i < readable; ++i) {
        std::strncpy(g_extensions[g_extension_count++], extensions[i].extensionName,
                     VK_MAX_EXTENSION_NAME_SIZE - 1);
    }
    delete[] extensions;

    g_has_buffer_device_address = HasExtension(VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME);
    g_has_provoking_vertex = HasExtension(VK_EXT_PROVOKING_VERTEX_EXTENSION_NAME);
    std::printf("probe: %d device extensions; BDA=%d provokingVertex=%d\n", g_extension_count,
                g_has_buffer_device_address ? 1 : 0, g_has_provoking_vertex ? 1 : 0);
    return true;
}

}  // namespace

int main() {
    if (!CreateInstance() || !PickDevice()) return 1;

    VkPhysicalDeviceFeatures reported{};
    VkPhysicalDeviceBufferDeviceAddressFeaturesKHR reported_bda{};
    VkPhysicalDeviceProvokingVertexFeaturesEXT reported_pv{};
    reported_bda.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES_KHR;
    reported_pv.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROVOKING_VERTEX_FEATURES_EXT;

    VkPhysicalDeviceFeatures2 features2{};
    features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    if (g_has_buffer_device_address) {
        features2.pNext = &reported_bda;
    }
    if (g_has_provoking_vertex) {
        reported_bda.pNext = &reported_pv;
    }
    g_get_features2(g_gpu, &features2);
    reported = features2.features;  // includes the chained structs via pNext writes

    int liar_count = 0;
    std::printf("probe: --- retry ladder (vk_device_retry_policy.h) ---\n");
    for (int attempt = 0; attempt < vk_retry_policy::kFeatureAttempts; ++attempt) {
        const auto profile = vk_retry_policy::ProfileForAttempt(attempt);
        const VkResult result = TryProfile(profile, reported, reported_bda, reported_pv);
        std::printf("probe: attempt %d profile=%s -> %s (%d)\n", attempt, ProfileName(profile),
                    ResultName(result), result);
        if (result == VK_SUCCESS) break;
        if (result != VK_ERROR_FEATURE_NOT_PRESENT) {
            std::printf("probe: unexpected non-feature error, stopping\n");
            return 1;
        }
    }

    const VkResult full =
        TryProfile(vk_retry_policy::FeatureProfile::Full, reported, reported_bda, reported_pv);
    if (full != VK_ERROR_FEATURE_NOT_PRESENT) {
        std::printf("probe: full profile is honest; no liar to bisect\n");
        vkDestroyInstance(g_instance, nullptr);
        return 0;
    }

    std::printf("probe: --- bisecting rejected features ---\n");
    for (const FeatureEntry& entry : kBaseFeatures) {
        if (*FeatureAt(reported, entry) == VK_FALSE) continue;
        VkPhysicalDeviceFeatures single{};
        *FeatureAt(single, entry) = VK_TRUE;
        const VkResult result = TryCreateDevice(single, nullptr, nullptr);
        if (result != VK_SUCCESS) {
            ++liar_count;
            std::printf("probe: LIAR base feature '%s' -> %s (%d)\n", entry.name, ResultName(result),
                        result);
        }
    }
    if (reported_bda.bufferDeviceAddress == VK_TRUE) {
        VkPhysicalDeviceFeatures single{};
        const VkResult result = TryCreateDevice(single, &reported_bda, nullptr);
        if (result != VK_SUCCESS) {
            ++liar_count;
            std::printf("probe: LIAR bufferDeviceAddress -> %s (%d)\n", ResultName(result), result);
        }
    }
    if (reported_pv.provokingVertexLast == VK_TRUE) {
        VkPhysicalDeviceFeatures single{};
        const VkResult result = TryCreateDevice(single, nullptr, &reported_pv);
        if (result != VK_SUCCESS) {
            ++liar_count;
            std::printf("probe: LIAR provokingVertexLast -> %s (%d)\n", ResultName(result), result);
        }
    }

    const VkResult core =
        TryProfile(vk_retry_policy::FeatureProfile::Core, reported, reported_bda, reported_pv);
    const VkResult none =
        TryProfile(vk_retry_policy::FeatureProfile::None, reported, reported_bda, reported_pv);
    std::printf("probe: VERDICT liars=%d coreProfile=%s noneProfile=%s\n", liar_count,
                ResultName(core), ResultName(none));

    vkDestroyInstance(g_instance, nullptr);
    return 0;
}
