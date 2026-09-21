// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary

#include "vulkan_frontend.h"
#include "shader_chain.h"
#include "shader_effects_spv.h"

#include <android/log.h>
#include <vulkan/vulkan.h>

#if defined(EMUCOREA_HAVE_LIBRASHADER)
#include <librashader.h>
#endif

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <vector>

#define VK_LOG_TAG "EmuCoreH-Vk"
#ifdef NDEBUG
#define VK_LOGI(...) ((void)0)
#define VK_LOGW(...) ((void)0)
#define VK_LOGE(...) ((void)0)
#else
#define VK_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VK_LOG_TAG, __VA_ARGS__)
#define VK_LOGW(...) __android_log_print(ANDROID_LOG_WARN, VK_LOG_TAG, __VA_ARGS__)
#define VK_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VK_LOG_TAG, __VA_ARGS__)
#endif

namespace emucoreh::vulkan {
namespace {

constexpr uint32_t kPreferredQueueFamilyNone = UINT32_MAX;
constexpr uint64_t kFenceWaitTimeoutNs = 2'000'000'000ull;
constexpr int kMaxSwapchainFailures = 3;

// User-requested source-pixel crop.
struct CropRect {
    int left;
    int top;
    int right;
    int bottom;
};

bool IsCropActive(const CropRect& crop) {
    return crop.left > 0 || crop.top > 0 || crop.right > 0 || crop.bottom > 0;
}

// Keeps at least one pixel in each direction so blits never get an empty rect.
CropRect ClampCrop(CropRect crop, uint32_t width, uint32_t height) {
    if (crop.left < 0) crop.left = 0;
    if (crop.top < 0) crop.top = 0;
    if (crop.right < 0) crop.right = 0;
    if (crop.bottom < 0) crop.bottom = 0;
    if (width > 0) {
        if (crop.left >= static_cast<int>(width)) crop.left = static_cast<int>(width) - 1;
        if (crop.right > static_cast<int>(width) - 1 - crop.left) {
            crop.right = static_cast<int>(width) - 1 - crop.left;
        }
    }
    if (height > 0) {
        if (crop.top >= static_cast<int>(height)) crop.top = static_cast<int>(height) - 1;
        if (crop.bottom > static_cast<int>(height) - 1 - crop.top) {
            crop.bottom = static_cast<int>(height) - 1 - crop.top;
        }
    }
    return crop;
}

struct State {
    bool requested = false;
    bool failed = false;
    bool active = false;
    bool context_reset_pending = false;

    // Source-pixel crop applied by the plain present paths; shader chains own
    // their own scaling and receive the uncropped frame.
    int crop_left = 0;
    int crop_top = 0;
    int crop_right = 0;
    int crop_bottom = 0;

    // The core passes both structs by pointer, and Flycast's hardware-render
    // callback lives on the caller's stack, so the frontend must own copies.
    retro_hw_render_callback callback{};
    bool has_callback = false;
    retro_hw_render_context_negotiation_interface_vulkan negotiation{};
    bool has_negotiation = false;

    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice physical_device = VK_NULL_HANDLE;
    uint32_t queue_family = kPreferredQueueFamilyNone;

    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkQueue present_queue = VK_NULL_HANDLE;
    uint32_t present_queue_family = kPreferredQueueFamilyNone;

    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat swapchain_format = VK_FORMAT_UNDEFINED;
    VkExtent2D swapchain_extent{};
    VkSurfaceTransformFlagBitsKHR swapchain_transform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    std::vector<VkImage> swapchain_images;
    std::vector<VkImageView> swapchain_views;
    std::vector<VkFramebuffer> swapchain_framebuffers;
    std::vector<bool> swapchain_initialized;
    int swapchain_failures = 0;

    VkRenderPass effect_render_pass = VK_NULL_HANDLE;
    VkDescriptorSetLayout effect_descriptor_layout = VK_NULL_HANDLE;
    VkPipelineLayout effect_pipeline_layout = VK_NULL_HANDLE;
    VkPipeline effect_pipeline = VK_NULL_HANDLE;
    VkDescriptorPool effect_descriptor_pool = VK_NULL_HANDLE;
    VkDescriptorSet effect_descriptor_set = VK_NULL_HANDLE;
    VkShaderModule effect_vertex_module = VK_NULL_HANDLE;
    VkShaderModule effect_fragment_module = VK_NULL_HANDLE;
    VkSampler effect_point_sampler = VK_NULL_HANDLE;
    VkSampler effect_linear_sampler = VK_NULL_HANDLE;

#if defined(EMUCOREA_HAVE_LIBRASHADER)
    void* shader_chain = nullptr;
    std::string shader_chain_preset;
    uint64_t shader_chain_generation = 0;
    bool shader_chain_failed = false;
    uint64_t shader_frame_count = 0;
    VkImage chain_target_image = VK_NULL_HANDLE;
    VkDeviceMemory chain_target_memory = VK_NULL_HANDLE;
    VkImageView chain_target_view = VK_NULL_HANDLE;
    uint32_t chain_target_width = 0;
    uint32_t chain_target_height = 0;
#endif

    VkCommandPool command_pool = VK_NULL_HANDLE;
    VkCommandBuffer command_buffer = VK_NULL_HANDLE;
    VkSemaphore acquire_semaphore = VK_NULL_HANDLE;
    std::vector<VkSemaphore> present_semaphores;
    VkFence frame_fence = VK_NULL_HANDLE;

    ANativeWindow* preparation_window = nullptr;
    uint32_t window_generation = 0;
    uint32_t preparation_generation = 0;
    int window_width = 0;
    int window_height = 0;

    PFN_vkCreateAndroidSurfaceKHR pfn_create_android_surface = nullptr;
    PFN_vkDestroySurfaceKHR pfn_destroy_surface = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR pfn_surface_support = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR pfn_surface_caps = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR pfn_surface_formats = nullptr;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR pfn_surface_present_modes = nullptr;
    PFN_vkCreateSwapchainKHR pfn_create_swapchain = nullptr;
    PFN_vkDestroySwapchainKHR pfn_destroy_swapchain = nullptr;
    PFN_vkAcquireNextImageKHR pfn_acquire = nullptr;
    PFN_vkQueuePresentKHR pfn_queue_present = nullptr;

    retro_hw_render_interface_vulkan interface = {};
    retro_vulkan_image frame_image = {};
    bool has_frame_image = false;
    uint32_t sync_index = 0;
    uint32_t submitted_sync_index = UINT32_MAX;
};

State g_vk;
std::mutex g_queue_mutex;
std::atomic<int> g_shader_effect{0};

template <typename T>
T LoadInstanceFunction(const char* name) {
    return reinterpret_cast<T>(vkGetInstanceProcAddr(g_vk.instance, name));
}

template <typename T>
T LoadDeviceFunction(const char* name) {
    return reinterpret_cast<T>(vkGetDeviceProcAddr(g_vk.device, name));
}

void ResetState() {
    g_vk = State{};
}

bool HasDeviceExtension(VkPhysicalDevice device, const char* name) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS || count == 0) {
        return false;
    }
    std::vector<VkExtensionProperties> extensions(count);
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, extensions.data()) != VK_SUCCESS) {
        return false;
    }
    for (const VkExtensionProperties& extension : extensions) {
        if (std::strcmp(extension.extensionName, name) == 0) return true;
    }
    return false;
}

bool HasInstanceExtension(const char* name) {
    uint32_t count = 0;
    if (vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr) != VK_SUCCESS || count == 0) {
        return false;
    }
    std::vector<VkExtensionProperties> extensions(count);
    if (vkEnumerateInstanceExtensionProperties(nullptr, &count, extensions.data()) != VK_SUCCESS) {
        return false;
    }
    for (const VkExtensionProperties& extension : extensions) {
        if (std::strcmp(extension.extensionName, name) == 0) return true;
    }
    return false;
}

bool EnsureInstance() {
    if (g_vk.instance != VK_NULL_HANDLE) return true;

    if (!HasInstanceExtension(VK_KHR_SURFACE_EXTENSION_NAME) ||
        !HasInstanceExtension(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
        VK_LOGE("Vulkan instance surface extensions are unavailable");
        return false;
    }

    VkApplicationInfo app_info{};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "EmuCoreH";
    app_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.pEngineName = "EmuCoreH";
    app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.apiVersion = VK_API_VERSION_1_0;

    const retro_hw_render_context_negotiation_interface_vulkan* negotiation =
        g_vk.has_negotiation ? &g_vk.negotiation : nullptr;
    if (negotiation != nullptr && negotiation->get_application_info != nullptr) {
        const VkApplicationInfo* requested = negotiation->get_application_info();
        if (requested != nullptr) app_info = *requested;
        if (app_info.apiVersion < VK_API_VERSION_1_0) app_info.apiVersion = VK_API_VERSION_1_0;
    }

    const char* enabled_extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo create_info{};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;
    create_info.enabledExtensionCount = 2;
    create_info.ppEnabledExtensionNames = enabled_extensions;

    const VkResult result = vkCreateInstance(&create_info, nullptr, &g_vk.instance);
    if (result != VK_SUCCESS) {
        VK_LOGE("vkCreateInstance failed (0x%x)", result);
        return false;
    }

    g_vk.pfn_create_android_surface =
        LoadInstanceFunction<PFN_vkCreateAndroidSurfaceKHR>("vkCreateAndroidSurfaceKHR");
    g_vk.pfn_destroy_surface = LoadInstanceFunction<PFN_vkDestroySurfaceKHR>("vkDestroySurfaceKHR");
    g_vk.pfn_surface_support =
        LoadInstanceFunction<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>("vkGetPhysicalDeviceSurfaceSupportKHR");
    g_vk.pfn_surface_caps =
        LoadInstanceFunction<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>("vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    g_vk.pfn_surface_formats =
        LoadInstanceFunction<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>("vkGetPhysicalDeviceSurfaceFormatsKHR");
    g_vk.pfn_surface_present_modes =
        LoadInstanceFunction<PFN_vkGetPhysicalDeviceSurfacePresentModesKHR>("vkGetPhysicalDeviceSurfacePresentModesKHR");
    if (g_vk.pfn_create_android_surface == nullptr || g_vk.pfn_destroy_surface == nullptr ||
        g_vk.pfn_surface_support == nullptr || g_vk.pfn_surface_caps == nullptr ||
        g_vk.pfn_surface_formats == nullptr || g_vk.pfn_surface_present_modes == nullptr) {
        VK_LOGE("Vulkan surface entry points are missing");
        return false;
    }
    VK_LOGI("Vulkan instance created");
    return true;
}

bool EnsureSurface(ANativeWindow* window, uint32_t window_generation) {
    if (window == nullptr) return false;
    if (g_vk.surface != VK_NULL_HANDLE) {
        if (g_vk.preparation_window == window && g_vk.preparation_generation == window_generation) return true;
        if (g_vk.device != VK_NULL_HANDLE && g_vk.pfn_destroy_swapchain != nullptr &&
            g_vk.swapchain != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(g_vk.device);
            g_vk.pfn_destroy_swapchain(g_vk.device, g_vk.swapchain, nullptr);
            g_vk.swapchain = VK_NULL_HANDLE;
            g_vk.swapchain_images.clear();
            g_vk.swapchain_initialized.clear();
        }
        g_vk.pfn_destroy_surface(g_vk.instance, g_vk.surface, nullptr);
        g_vk.surface = VK_NULL_HANDLE;
    }

    VkAndroidSurfaceCreateInfoKHR create_info{};
    create_info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    create_info.window = window;
    const VkResult result = g_vk.pfn_create_android_surface(g_vk.instance, &create_info, nullptr, &g_vk.surface);
    if (result != VK_SUCCESS) {
        VK_LOGE("vkCreateAndroidSurfaceKHR failed (0x%x)", result);
        return false;
    }

    g_vk.preparation_window = window;
    g_vk.preparation_generation = window_generation;
    g_vk.window_width = ANativeWindow_getWidth(window);
    g_vk.window_height = ANativeWindow_getHeight(window);
    VK_LOGI("Vulkan surface created (%dx%d)", g_vk.window_width, g_vk.window_height);
    return true;
}

bool PickPhysicalDevice() {
    if (g_vk.physical_device != VK_NULL_HANDLE) return true;

    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(g_vk.instance, &count, nullptr) != VK_SUCCESS || count == 0) {
        VK_LOGE("No Vulkan physical devices found");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    if (vkEnumeratePhysicalDevices(g_vk.instance, &count, devices.data()) != VK_SUCCESS) {
        VK_LOGE("vkEnumeratePhysicalDevices failed");
        return false;
    }

    for (VkPhysicalDevice device : devices) {
        if (!HasDeviceExtension(device, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) continue;

        uint32_t queue_count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queue_count, nullptr);
        if (queue_count == 0) continue;
        std::vector<VkQueueFamilyProperties> queue_properties(queue_count);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queue_count, queue_properties.data());

        for (uint32_t i = 0; i < queue_count; ++i) {
            if ((queue_properties[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            VkBool32 present_supported = VK_FALSE;
            if (g_vk.pfn_surface_support(device, i, g_vk.surface, &present_supported) != VK_SUCCESS) continue;
            if (present_supported != VK_TRUE) continue;

            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(device, &properties);
            g_vk.physical_device = device;
            g_vk.queue_family = i;
            VK_LOGI("Vulkan physical device: %s (API %u.%u, queue family %u)", properties.deviceName,
                    VK_VERSION_MAJOR(properties.apiVersion), VK_VERSION_MINOR(properties.apiVersion), i);
            return true;
        }
    }

    VK_LOGE("No Vulkan device with graphics+present queue and swapchain support");
    return false;
}

bool CreateDevice() {
    if (g_vk.device != VK_NULL_HANDLE) return true;
    if (!g_vk.has_negotiation || g_vk.negotiation.create_device == nullptr) {
        VK_LOGE("Core did not provide a Vulkan context negotiation interface");
        return false;
    }

    retro_vulkan_context context{};
    const char* required_extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    const bool created =
        g_vk.negotiation.create_device(&context, g_vk.instance, g_vk.physical_device, g_vk.surface,
                                       &vkGetInstanceProcAddr, required_extensions, 1, nullptr, 0, nullptr);
    if (!created || context.device == VK_NULL_HANDLE) {
        VK_LOGE("Core failed to create the Vulkan device");
        return false;
    }

    g_vk.device = context.device;
    g_vk.queue = context.queue;
    g_vk.queue_family = context.queue_family_index;
    g_vk.present_queue = context.presentation_queue != VK_NULL_HANDLE ? context.presentation_queue : context.queue;
    g_vk.present_queue_family = context.presentation_queue != VK_NULL_HANDLE
                                    ? context.presentation_queue_family_index
                                    : context.queue_family_index;
    if (g_vk.present_queue_family != g_vk.queue_family) {
        VK_LOGE("Vulkan present queue family %u differs from graphics family %u", g_vk.present_queue_family,
                g_vk.queue_family);
        return false;
    }

    g_vk.pfn_create_swapchain = LoadDeviceFunction<PFN_vkCreateSwapchainKHR>("vkCreateSwapchainKHR");
    g_vk.pfn_destroy_swapchain = LoadDeviceFunction<PFN_vkDestroySwapchainKHR>("vkDestroySwapchainKHR");
    g_vk.pfn_acquire = LoadDeviceFunction<PFN_vkAcquireNextImageKHR>("vkAcquireNextImageKHR");
    g_vk.pfn_queue_present = LoadDeviceFunction<PFN_vkQueuePresentKHR>("vkQueuePresentKHR");
    if (g_vk.pfn_create_swapchain == nullptr || g_vk.pfn_destroy_swapchain == nullptr ||
        g_vk.pfn_acquire == nullptr || g_vk.pfn_queue_present == nullptr) {
        VK_LOGE("Vulkan swapchain entry points are missing");
        return false;
    }

    VkCommandPoolCreateInfo pool_info{};
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queueFamilyIndex = g_vk.queue_family;
    if (vkCreateCommandPool(g_vk.device, &pool_info, nullptr, &g_vk.command_pool) != VK_SUCCESS) {
        VK_LOGE("vkCreateCommandPool failed");
        return false;
    }

    VkCommandBufferAllocateInfo buffer_info{};
    buffer_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    buffer_info.commandPool = g_vk.command_pool;
    buffer_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    buffer_info.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(g_vk.device, &buffer_info, &g_vk.command_buffer) != VK_SUCCESS) {
        VK_LOGE("vkAllocateCommandBuffers failed");
        return false;
    }

    VkSemaphoreCreateInfo semaphore_info{};
    semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    if (vkCreateSemaphore(g_vk.device, &semaphore_info, nullptr, &g_vk.acquire_semaphore) != VK_SUCCESS) {
        VK_LOGE("vkCreateSemaphore failed");
        return false;
    }

    VkFenceCreateInfo fence_info{};
    fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fence_info.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (vkCreateFence(g_vk.device, &fence_info, nullptr, &g_vk.frame_fence) != VK_SUCCESS) {
        VK_LOGE("vkCreateFence failed");
        return false;
    }

    VkSamplerCreateInfo sampler_info{};
    sampler_info.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sampler_info.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler_info.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler_info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler_info.magFilter = VK_FILTER_NEAREST;
    sampler_info.minFilter = VK_FILTER_NEAREST;
    if (vkCreateSampler(g_vk.device, &sampler_info, nullptr, &g_vk.effect_point_sampler) != VK_SUCCESS) {
        VK_LOGE("vkCreateSampler (point) failed");
        return false;
    }
    sampler_info.magFilter = VK_FILTER_LINEAR;
    sampler_info.minFilter = VK_FILTER_LINEAR;
    if (vkCreateSampler(g_vk.device, &sampler_info, nullptr, &g_vk.effect_linear_sampler) != VK_SUCCESS) {
        VK_LOGE("vkCreateSampler (linear) failed");
        return false;
    }

    VkDescriptorSetLayoutBinding bindings[2]{};
    for (uint32_t i = 0; i < 2; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    }
    VkDescriptorSetLayoutCreateInfo dsl_info{};
    dsl_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dsl_info.bindingCount = 2;
    dsl_info.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(g_vk.device, &dsl_info, nullptr, &g_vk.effect_descriptor_layout) != VK_SUCCESS) {
        VK_LOGE("vkCreateDescriptorSetLayout failed");
        return false;
    }

    VkPushConstantRange push_range{};
    push_range.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    push_range.offset = 0;
    push_range.size = 48;
    VkPipelineLayoutCreateInfo pl_info{};
    pl_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pl_info.setLayoutCount = 1;
    pl_info.pSetLayouts = &g_vk.effect_descriptor_layout;
    pl_info.pushConstantRangeCount = 1;
    pl_info.pPushConstantRanges = &push_range;
    if (vkCreatePipelineLayout(g_vk.device, &pl_info, nullptr, &g_vk.effect_pipeline_layout) != VK_SUCCESS) {
        VK_LOGE("vkCreatePipelineLayout failed");
        return false;
    }

    VkDescriptorPoolSize pool_size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2};
    VkDescriptorPoolCreateInfo descriptor_pool_info{};
    descriptor_pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    descriptor_pool_info.maxSets = 1;
    descriptor_pool_info.poolSizeCount = 1;
    descriptor_pool_info.pPoolSizes = &pool_size;
    if (vkCreateDescriptorPool(g_vk.device, &descriptor_pool_info, nullptr, &g_vk.effect_descriptor_pool) !=
        VK_SUCCESS) {
        VK_LOGE("vkCreateDescriptorPool failed");
        return false;
    }
    VkDescriptorSetAllocateInfo ds_info{};
    ds_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ds_info.descriptorPool = g_vk.effect_descriptor_pool;
    ds_info.descriptorSetCount = 1;
    ds_info.pSetLayouts = &g_vk.effect_descriptor_layout;
    if (vkAllocateDescriptorSets(g_vk.device, &ds_info, &g_vk.effect_descriptor_set) != VK_SUCCESS) {
        VK_LOGE("vkAllocateDescriptorSets failed");
        return false;
    }

    VkShaderModuleCreateInfo module_info{};
    module_info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    module_info.codeSize = k_present_effects_vert_spv_size_bytes;
    module_info.pCode = k_present_effects_vert_spv;
    if (vkCreateShaderModule(g_vk.device, &module_info, nullptr, &g_vk.effect_vertex_module) != VK_SUCCESS) {
        VK_LOGE("vkCreateShaderModule (effect vertex) failed");
        return false;
    }
    module_info.codeSize = k_present_effects_frag_spv_size_bytes;
    module_info.pCode = k_present_effects_frag_spv;
    if (vkCreateShaderModule(g_vk.device, &module_info, nullptr, &g_vk.effect_fragment_module) != VK_SUCCESS) {
        VK_LOGE("vkCreateShaderModule (effect fragment) failed");
        return false;
    }

    VK_LOGI("Vulkan render device ready");
    return true;
}

VkExtent2D ChooseSwapchainExtent(const VkSurfaceCapabilitiesKHR& capabilities) {
    if (capabilities.currentExtent.width != UINT32_MAX) return capabilities.currentExtent;

    VkExtent2D extent{};
    extent.width = static_cast<uint32_t>(
        g_vk.window_width > 0 ? g_vk.window_width : static_cast<int>(capabilities.minImageExtent.width));
    extent.height = static_cast<uint32_t>(
        g_vk.window_height > 0 ? g_vk.window_height : static_cast<int>(capabilities.minImageExtent.height));
    extent.width = std::clamp(extent.width, capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
    extent.height = std::clamp(extent.height, capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
    return extent;
}

VkSurfaceFormatKHR ChooseSwapchainFormat(uint32_t count, const VkSurfaceFormatKHR* formats) {
    for (uint32_t i = 0; i < count; ++i) {
        if (formats[i].format == VK_FORMAT_R8G8B8A8_UNORM) return formats[i];
    }
    for (uint32_t i = 0; i < count; ++i) {
        if (formats[i].format == VK_FORMAT_B8G8R8A8_UNORM) return formats[i];
    }
    if (count == 1 && formats[0].format == VK_FORMAT_UNDEFINED) {
        return VkSurfaceFormatKHR{VK_FORMAT_R8G8B8A8_UNORM, formats[0].colorSpace};
    }
    return formats[0];
}

VkCompositeAlphaFlagBitsKHR ChooseCompositeAlpha(VkCompositeAlphaFlagsKHR supported) {
    const VkCompositeAlphaFlagBitsKHR preferred[] = {
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
        VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
    };
    for (VkCompositeAlphaFlagBitsKHR candidate : preferred) {
        if ((supported & candidate) != 0) return candidate;
    }
    return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
}

bool CreateEffectRenderPass() {
    if (g_vk.effect_render_pass != VK_NULL_HANDLE) return true;

    VkAttachmentDescription color{};
    color.format = g_vk.swapchain_format;
    color.samples = VK_SAMPLE_COUNT_1_BIT;
    color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    color.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkAttachmentReference color_ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &color_ref;

    VkRenderPassCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    info.attachmentCount = 1;
    info.pAttachments = &color;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    if (vkCreateRenderPass(g_vk.device, &info, nullptr, &g_vk.effect_render_pass) != VK_SUCCESS) {
        VK_LOGE("vkCreateRenderPass (effect) failed");
        return false;
    }
    return true;
}

bool CreateSwapchainTargets() {
    const size_t count = g_vk.swapchain_images.size();
    g_vk.swapchain_views.assign(count, VK_NULL_HANDLE);
    g_vk.swapchain_framebuffers.assign(count, VK_NULL_HANDLE);
    for (size_t i = 0; i < count; ++i) {
        VkImageViewCreateInfo view_info{};
        view_info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        view_info.image = g_vk.swapchain_images[i];
        view_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
        view_info.format = g_vk.swapchain_format;
        view_info.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        if (vkCreateImageView(g_vk.device, &view_info, nullptr, &g_vk.swapchain_views[i]) != VK_SUCCESS) {
            VK_LOGE("vkCreateImageView (swapchain) failed");
            return false;
        }

        VkFramebufferCreateInfo fb_info{};
        fb_info.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fb_info.renderPass = g_vk.effect_render_pass;
        fb_info.attachmentCount = 1;
        fb_info.pAttachments = &g_vk.swapchain_views[i];
        fb_info.width = g_vk.swapchain_extent.width;
        fb_info.height = g_vk.swapchain_extent.height;
        fb_info.layers = 1;
        if (vkCreateFramebuffer(g_vk.device, &fb_info, nullptr, &g_vk.swapchain_framebuffers[i]) != VK_SUCCESS) {
            VK_LOGE("vkCreateFramebuffer (swapchain) failed");
            return false;
        }
    }
    return true;
}

bool EnsureEffectPipeline() {
    if (g_vk.effect_pipeline != VK_NULL_HANDLE) return true;
    if (g_vk.effect_render_pass == VK_NULL_HANDLE || g_vk.effect_vertex_module == VK_NULL_HANDLE ||
        g_vk.effect_fragment_module == VK_NULL_HANDLE || g_vk.effect_pipeline_layout == VK_NULL_HANDLE) {
        return false;
    }

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = g_vk.effect_vertex_module;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = g_vk.effect_fragment_module;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vertex_input{};
    vertex_input.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

    VkPipelineInputAssemblyStateCreateInfo input_assembly{};
    input_assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    input_assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkPipelineViewportStateCreateInfo viewport_state{};
    viewport_state.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewport_state.viewportCount = 1;
    viewport_state.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo raster{};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo multisample{};
    multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState blend_attachment{};
    blend_attachment.blendEnable = VK_FALSE;
    blend_attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                      VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo blend{};
    blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blend.attachmentCount = 1;
    blend.pAttachments = &blend_attachment;

    const VkDynamicState dynamic_states[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{};
    dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamic.dynamicStateCount = 2;
    dynamic.pDynamicStates = dynamic_states;

    VkGraphicsPipelineCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    info.stageCount = 2;
    info.pStages = stages;
    info.pVertexInputState = &vertex_input;
    info.pInputAssemblyState = &input_assembly;
    info.pViewportState = &viewport_state;
    info.pRasterizationState = &raster;
    info.pMultisampleState = &multisample;
    info.pColorBlendState = &blend;
    info.pDynamicState = &dynamic;
    info.layout = g_vk.effect_pipeline_layout;
    info.renderPass = g_vk.effect_render_pass;
    info.subpass = 0;
    if (vkCreateGraphicsPipelines(g_vk.device, VK_NULL_HANDLE, 1, &info, nullptr, &g_vk.effect_pipeline) !=
        VK_SUCCESS) {
        VK_LOGE("vkCreateGraphicsPipelines (effect) failed");
        g_vk.effect_pipeline = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

#if defined(EMUCOREA_HAVE_LIBRASHADER)
void ReportShaderChainError(const char* what, libra_error_t err) {
    char* message = nullptr;
    if (libra_error_write(err, &message) == 0 && message != nullptr) {
        VK_LOGE("librashader %s failed: %s", what, message);
        libra_error_free_string(&message);
    } else {
        VK_LOGE("librashader %s failed (errno %d)", what, static_cast<int>(libra_error_errno(err)));
    }
    libra_error_free(&err);
}

uint32_t FindMemoryType(uint32_t type_bits, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memory_properties{};
    vkGetPhysicalDeviceMemoryProperties(g_vk.physical_device, &memory_properties);
    for (uint32_t i = 0; i < memory_properties.memoryTypeCount; ++i) {
        if ((type_bits & (1u << i)) != 0 &&
            (memory_properties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    return UINT32_MAX;
}

void DestroyShaderChainTarget() {
    if (g_vk.device == VK_NULL_HANDLE) return;
    if (g_vk.chain_target_view != VK_NULL_HANDLE) {
        vkDestroyImageView(g_vk.device, g_vk.chain_target_view, nullptr);
        g_vk.chain_target_view = VK_NULL_HANDLE;
    }
    if (g_vk.chain_target_image != VK_NULL_HANDLE) {
        vkDestroyImage(g_vk.device, g_vk.chain_target_image, nullptr);
        g_vk.chain_target_image = VK_NULL_HANDLE;
    }
    if (g_vk.chain_target_memory != VK_NULL_HANDLE) {
        vkFreeMemory(g_vk.device, g_vk.chain_target_memory, nullptr);
        g_vk.chain_target_memory = VK_NULL_HANDLE;
    }
    g_vk.chain_target_width = 0;
    g_vk.chain_target_height = 0;
}

void DestroyShaderChain() {
#if defined(EMUCOREA_HAVE_LIBRASHADER)
    if (g_vk.shader_chain != nullptr) {
        libra_vk_filter_chain_t chain = static_cast<libra_vk_filter_chain_t>(g_vk.shader_chain);
        libra_vk_filter_chain_free(&chain);
        g_vk.shader_chain = nullptr;
    }
#endif
    DestroyShaderChainTarget();
    g_vk.shader_chain_preset.clear();
    g_vk.shader_chain_generation = 0;
    g_vk.shader_chain_failed = false;
    g_vk.shader_frame_count = 0;
}

bool EnsureShaderChainTarget(uint32_t width, uint32_t height) {
    if (width == 0 || height == 0) return false;
    if (g_vk.chain_target_image != VK_NULL_HANDLE && g_vk.chain_target_width == width &&
        g_vk.chain_target_height == height) {
        return true;
    }
    DestroyShaderChainTarget();

    VkImageCreateInfo image_info{};
    image_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    image_info.imageType = VK_IMAGE_TYPE_2D;
    image_info.format = VK_FORMAT_R8G8B8A8_UNORM;
    image_info.extent = {width, height, 1};
    image_info.mipLevels = 1;
    image_info.arrayLayers = 1;
    image_info.samples = VK_SAMPLE_COUNT_1_BIT;
    image_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    image_info.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                       VK_IMAGE_USAGE_SAMPLED_BIT;
    image_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    image_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(g_vk.device, &image_info, nullptr, &g_vk.chain_target_image) != VK_SUCCESS) {
        VK_LOGE("vkCreateImage (shader chain target) failed");
        return false;
    }

    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(g_vk.device, g_vk.chain_target_image, &requirements);
    const uint32_t memory_type = FindMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memory_type == UINT32_MAX) {
        VK_LOGE("No device-local memory for shader chain target");
        DestroyShaderChainTarget();
        return false;
    }
    VkMemoryAllocateInfo allocate_info{};
    allocate_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocate_info.allocationSize = requirements.size;
    allocate_info.memoryTypeIndex = memory_type;
    if (vkAllocateMemory(g_vk.device, &allocate_info, nullptr, &g_vk.chain_target_memory) != VK_SUCCESS) {
        VK_LOGE("vkAllocateMemory (shader chain target) failed");
        DestroyShaderChainTarget();
        return false;
    }
    vkBindImageMemory(g_vk.device, g_vk.chain_target_image, g_vk.chain_target_memory, 0);

    VkImageViewCreateInfo view_info{};
    view_info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    view_info.image = g_vk.chain_target_image;
    view_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
    view_info.format = VK_FORMAT_R8G8B8A8_UNORM;
    view_info.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vkCreateImageView(g_vk.device, &view_info, nullptr, &g_vk.chain_target_view) != VK_SUCCESS) {
        VK_LOGE("vkCreateImageView (shader chain target) failed");
        DestroyShaderChainTarget();
        return false;
    }
    g_vk.chain_target_width = width;
    g_vk.chain_target_height = height;
    return true;
}

bool EnsureShaderChain() {
    if (!shader_chain::IsEnabled()) return false;
    const std::string path = shader_chain::PresetPath();
    if (path.empty()) return false;

    const uint64_t generation = shader_chain::Generation();
    if (g_vk.shader_chain != nullptr && g_vk.shader_chain_preset == path &&
        g_vk.shader_chain_generation == generation) {
        return true;
    }
    if (g_vk.shader_chain_failed && g_vk.shader_chain_preset == path &&
        g_vk.shader_chain_generation == generation) {
        return false;
    }

    DestroyShaderChain();
    g_vk.shader_chain_preset = path;
    g_vk.shader_chain_generation = generation;

    libra_shader_preset_t preset = nullptr;
    if (libra_error_t err = libra_preset_create(path.c_str(), &preset)) {
        ReportShaderChainError("preset load", err);
        g_vk.shader_chain_failed = true;
        return false;
    }

    libra_device_vk_t vk = {};
    vk.physical_device = g_vk.physical_device;
    vk.instance = g_vk.instance;
    vk.device = g_vk.device;
    vk.queue = g_vk.queue;
    vk.entry = &vkGetInstanceProcAddr;

    libra_vk_filter_chain_t chain = nullptr;
    if (libra_error_t err = libra_vk_filter_chain_create(&preset, vk, nullptr, &chain)) {
        ReportShaderChainError("chain create", err);
        g_vk.shader_chain_failed = true;
        return false;
    }
    g_vk.shader_chain = chain;
    g_vk.shader_frame_count = 0;
    VK_LOGI("librashader: loaded preset '%s'", path.c_str());
    return true;
}
#endif  // EMUCOREA_HAVE_LIBRASHADER

void DestroySwapchain() {
    if (g_vk.device == VK_NULL_HANDLE) return;
    if (g_vk.effect_pipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(g_vk.device, g_vk.effect_pipeline, nullptr);
        g_vk.effect_pipeline = VK_NULL_HANDLE;
    }
    if (g_vk.effect_render_pass != VK_NULL_HANDLE) {
        vkDestroyRenderPass(g_vk.device, g_vk.effect_render_pass, nullptr);
        g_vk.effect_render_pass = VK_NULL_HANDLE;
    }
    for (VkFramebuffer& framebuffer : g_vk.swapchain_framebuffers) {
        if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(g_vk.device, framebuffer, nullptr);
    }
    g_vk.swapchain_framebuffers.clear();
    for (VkImageView& view : g_vk.swapchain_views) {
        if (view != VK_NULL_HANDLE) vkDestroyImageView(g_vk.device, view, nullptr);
    }
    g_vk.swapchain_views.clear();
    for (VkSemaphore semaphore : g_vk.present_semaphores) {
        if (semaphore != VK_NULL_HANDLE) vkDestroySemaphore(g_vk.device, semaphore, nullptr);
    }
    g_vk.present_semaphores.clear();
    if (g_vk.swapchain == VK_NULL_HANDLE) return;
    g_vk.pfn_destroy_swapchain(g_vk.device, g_vk.swapchain, nullptr);
    g_vk.swapchain = VK_NULL_HANDLE;
    g_vk.swapchain_images.clear();
    g_vk.swapchain_initialized.clear();
}

bool CreateSwapchain() {
    if (g_vk.device == VK_NULL_HANDLE || g_vk.surface == VK_NULL_HANDLE) return false;
    if (g_vk.swapchain != VK_NULL_HANDLE) return true;

    VkSurfaceCapabilitiesKHR capabilities{};
    if (g_vk.pfn_surface_caps(g_vk.physical_device, g_vk.surface, &capabilities) != VK_SUCCESS) {
        VK_LOGE("vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed");
        return false;
    }

    uint32_t format_count = 0;
    if (g_vk.pfn_surface_formats(g_vk.physical_device, g_vk.surface, &format_count, nullptr) != VK_SUCCESS ||
        format_count == 0) {
        VK_LOGE("No Vulkan surface formats");
        return false;
    }
    std::vector<VkSurfaceFormatKHR> formats(format_count);
    g_vk.pfn_surface_formats(g_vk.physical_device, g_vk.surface, &format_count, formats.data());
    const VkSurfaceFormatKHR format = ChooseSwapchainFormat(format_count, formats.data());

    uint32_t mode_count = 0;
    if (g_vk.pfn_surface_present_modes(g_vk.physical_device, g_vk.surface, &mode_count, nullptr) != VK_SUCCESS ||
        mode_count == 0) {
        VK_LOGE("No Vulkan surface present modes");
        return false;
    }
    std::vector<VkPresentModeKHR> modes(mode_count);
    g_vk.pfn_surface_present_modes(g_vk.physical_device, g_vk.surface, &mode_count, modes.data());
    // Mailbox hands the newest finished frame to the display instead of
    // queueing behind the previous one, which shortens the path between the
    // emulated frame and the panel. FIFO is the fallback and the only mode
    // the specification guarantees, so it stays the default when mailbox is
    // not advertised.
    VkPresentModeKHR present_mode = VK_PRESENT_MODE_FIFO_KHR;
    for (VkPresentModeKHR mode : modes) {
        if (mode == VK_PRESENT_MODE_MAILBOX_KHR) {
            present_mode = mode;
            break;
        }
    }

    if ((capabilities.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
        VK_LOGE("Swapchain images do not support transfer destinations");
        return false;
    }
    VkImageUsageFlags usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if ((capabilities.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0) {
        usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    }

    uint32_t image_count = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0 && image_count > capabilities.maxImageCount) {
        image_count = capabilities.maxImageCount;
    }

    VkExtent2D extent = ChooseSwapchainExtent(capabilities);
    if (extent.width == 0 || extent.height == 0) {
        VK_LOGE("Swapchain extent is empty");
        return false;
    }

    VkSwapchainCreateInfoKHR create_info{};
    create_info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    create_info.surface = g_vk.surface;
    create_info.minImageCount = image_count;
    create_info.imageFormat = format.format;
    create_info.imageColorSpace = format.colorSpace;
    create_info.imageExtent = extent;
    create_info.imageArrayLayers = 1;
    create_info.imageUsage = usage;
    create_info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    // The frame is rendered in the window's logical orientation; the display
    // compositor already rotates it for the physical panel. Declaring the
    // surface's currentTransform as preTransform made the platform rotate the
    // image a second time (90 degrees off on portrait-native phones), so ask
    // for an identity pre-transform whenever the surface allows it.
    VkSurfaceTransformFlagBitsKHR pre_transform = capabilities.currentTransform;
    if ((capabilities.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0) {
        pre_transform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    }
    create_info.preTransform = pre_transform;
    create_info.compositeAlpha = ChooseCompositeAlpha(capabilities.supportedCompositeAlpha);
    create_info.presentMode = present_mode;
    create_info.clipped = VK_TRUE;
    VK_LOGI("Vulkan swapchain transforms: supported=0x%x current=0x%x chosen=0x%x",
            static_cast<unsigned>(capabilities.supportedTransforms),
            static_cast<unsigned>(capabilities.currentTransform),
            static_cast<unsigned>(pre_transform));

    const VkResult result = g_vk.pfn_create_swapchain(g_vk.device, &create_info, nullptr, &g_vk.swapchain);
    if (result != VK_SUCCESS) {
        VK_LOGE("vkCreateSwapchainKHR failed (0x%x)", result);
        return false;
    }

    uint32_t actual_count = 0;
    if (vkGetSwapchainImagesKHR(g_vk.device, g_vk.swapchain, &actual_count, nullptr) != VK_SUCCESS ||
        actual_count == 0) {
        VK_LOGE("vkGetSwapchainImagesKHR failed");
        DestroySwapchain();
        return false;
    }
    g_vk.swapchain_images.resize(actual_count);
    vkGetSwapchainImagesKHR(g_vk.device, g_vk.swapchain, &actual_count, g_vk.swapchain_images.data());
    g_vk.swapchain_initialized.assign(actual_count, false);
    // Reacquiring an image guarantees its previous presentation consumed this semaphore.
    g_vk.present_semaphores.assign(actual_count, VK_NULL_HANDLE);
    VkSemaphoreCreateInfo semaphore_info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (VkSemaphore& semaphore : g_vk.present_semaphores) {
        if (vkCreateSemaphore(g_vk.device, &semaphore_info, nullptr, &semaphore) != VK_SUCCESS) {
            DestroySwapchain();
            return false;
        }
    }
    g_vk.swapchain_format = format.format;
    g_vk.swapchain_extent = extent;
    g_vk.swapchain_transform = capabilities.currentTransform;
    g_vk.swapchain_failures = 0;

    if ((usage & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0) {
        if (!CreateEffectRenderPass() || !CreateSwapchainTargets()) {
            DestroySwapchain();
            return false;
        }
        if (g_shader_effect.load(std::memory_order_relaxed) != 0 && !EnsureEffectPipeline()) {
            VK_LOGW("Vulkan shader effect pipeline unavailable; falling back to blit");
        }
    }

    VK_LOGI("Vulkan swapchain %ux%u, %u images, format %u, transform %u, present mode %u", extent.width,
            extent.height, actual_count, static_cast<unsigned>(format.format),
            static_cast<unsigned>(capabilities.currentTransform), static_cast<unsigned>(present_mode));
    return true;
}

bool RecreateSwapchain() {
    if (g_vk.device == VK_NULL_HANDLE) return false;
    vkDeviceWaitIdle(g_vk.device);
    DestroySwapchain();
    if (CreateSwapchain()) return true;

    ++g_vk.swapchain_failures;
    if (g_vk.swapchain_failures >= kMaxSwapchainFailures) {
        VK_LOGE("Giving up on Vulkan swapchain after %d failures", g_vk.swapchain_failures);
        g_vk.failed = true;
    }
    return false;
}

PresentRect FitDisplayRect(const VkExtent2D& extent, double display_aspect, bool stretch) {
    PresentRect rect{0, 0, static_cast<int>(extent.width), static_cast<int>(extent.height)};
    if (stretch) return rect;
    if (display_aspect <= 0.0 || !std::isfinite(display_aspect)) display_aspect = 4.0 / 3.0;

    const double window_aspect = static_cast<double>(extent.width) / static_cast<double>(extent.height);
    int width;
    int height;
    if (window_aspect > display_aspect) {
        height = static_cast<int>(extent.height);
        width = static_cast<int>(height * display_aspect + 0.5);
    } else {
        width = static_cast<int>(extent.width);
        height = static_cast<int>(width / display_aspect + 0.5);
    }
    if (width < 1) width = 1;
    if (height < 1) height = 1;
    rect.width = width;
    rect.height = height;
    rect.x = (static_cast<int>(extent.width) - width) / 2;
    rect.y = (static_cast<int>(extent.height) - height) / 2;
    return rect;
}

void SetImage(void*, const retro_vulkan_image* image, uint32_t num_semaphores, const VkSemaphore* semaphores,
              uint32_t src_queue_family) {
    (void)num_semaphores;
    (void)semaphores;
    (void)src_queue_family;
    if (image == nullptr || image->image_view == VK_NULL_HANDLE) {
        g_vk.has_frame_image = false;
        return;
    }
    g_vk.frame_image = *image;
    g_vk.has_frame_image = true;
}

uint32_t GetSyncIndex(void*) {
    return g_vk.sync_index;
}

uint32_t GetSyncIndexMask(void*) {
    // Core output images are independent of Android's acquired swapchain image.
    return 3u;
}

void SetCommandBuffers(void*, uint32_t, const VkCommandBuffer*) {}

void WaitSyncIndex(void*) {
    if (g_vk.device == VK_NULL_HANDLE || g_vk.frame_fence == VK_NULL_HANDLE) return;
    // Present waits for the preceding submission before reusing its command
    // buffer. With alternating core images, only the latest image can be in use.
    if (g_vk.sync_index != g_vk.submitted_sync_index) return;
    vkWaitForFences(g_vk.device, 1, &g_vk.frame_fence, VK_TRUE, kFenceWaitTimeoutNs);
}

void LockQueue(void*) {
    g_queue_mutex.lock();
}

void UnlockQueue(void*) {
    g_queue_mutex.unlock();
}

void SetSignalSemaphore(void*, VkSemaphore) {}

void FillInterface() {
    g_vk.interface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    g_vk.interface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    g_vk.interface.handle = &g_vk;
    g_vk.interface.instance = g_vk.instance;
    g_vk.interface.gpu = g_vk.physical_device;
    g_vk.interface.device = g_vk.device;
    g_vk.interface.get_device_proc_addr = &vkGetDeviceProcAddr;
    g_vk.interface.get_instance_proc_addr = &vkGetInstanceProcAddr;
    g_vk.interface.queue = g_vk.queue;
    g_vk.interface.queue_index = g_vk.queue_family;
    g_vk.interface.set_image = &SetImage;
    g_vk.interface.get_sync_index = &GetSyncIndex;
    g_vk.interface.get_sync_index_mask = &GetSyncIndexMask;
    g_vk.interface.set_command_buffers = &SetCommandBuffers;
    g_vk.interface.wait_sync_index = &WaitSyncIndex;
    g_vk.interface.lock_queue = &LockQueue;
    g_vk.interface.unlock_queue = &UnlockQueue;
    g_vk.interface.set_signal_semaphore = &SetSignalSemaphore;
}

struct PresentPushConstants {
    float dst_x;
    float dst_y;
    float dst_w;
    float dst_h;
    float src_x;
    float src_y;
    float src_w;
    float src_h;
    float out_w;
    float out_h;
    float effect;
    float pad;
};

bool RecordPresentBlit(uint32_t swapchain_index, uint32_t source_width, uint32_t source_height,
                       const PresentRect& dst, const CropRect& crop) {
    VkCommandBuffer command_buffer = g_vk.command_buffer;
    vkResetCommandBuffer(command_buffer, 0);

    VkCommandBufferBeginInfo begin_info{};
    begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(command_buffer, &begin_info) != VK_SUCCESS) return false;

    const VkImage source_image = g_vk.frame_image.create_info.image;
    const VkImageLayout source_layout = g_vk.frame_image.image_layout;
    const VkImage target_image = g_vk.swapchain_images[swapchain_index];

    VkImageMemoryBarrier source_barrier{};
    source_barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    source_barrier.oldLayout = source_layout;
    source_barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    source_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    source_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    source_barrier.image = source_image;
    source_barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    source_barrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT;
    source_barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0,
                         nullptr, 0, nullptr, 1, &source_barrier);

    VkImageMemoryBarrier target_barrier{};
    target_barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    target_barrier.oldLayout =
        g_vk.swapchain_initialized[swapchain_index] ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR : VK_IMAGE_LAYOUT_UNDEFINED;
    target_barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    target_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    target_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    target_barrier.image = target_image;
    target_barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    target_barrier.srcAccessMask = 0;
    target_barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0,
                         nullptr, 0, nullptr, 1, &target_barrier);

    VkClearColorValue clear{};
    const VkImageSubresourceRange clear_range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdClearColorImage(command_buffer, target_image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1,
                         &clear_range);

    VkImageBlit blit{};
    blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.srcOffsets[0] = {crop.left, crop.top, 0};
    blit.srcOffsets[1] = {static_cast<int32_t>(source_width) - crop.right,
                          static_cast<int32_t>(source_height) - crop.bottom, 1};
    blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.dstOffsets[0] = {dst.x, dst.y, 0};
    blit.dstOffsets[1] = {dst.x + dst.width, dst.y + dst.height, 1};
    vkCmdBlitImage(command_buffer, source_image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target_image,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);

    source_barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    source_barrier.newLayout = source_layout;
    source_barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    source_barrier.dstAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0,
                         nullptr, 0, nullptr, 1, &source_barrier);

    target_barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    target_barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    target_barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    target_barrier.dstAccessMask = 0;
    vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
                         nullptr, 0, nullptr, 1, &target_barrier);

    return vkEndCommandBuffer(command_buffer) == VK_SUCCESS;
}

#if !defined(EMUCOREA_HAVE_LIBRASHADER)
bool RecordPresentEffect(uint32_t swapchain_index, uint32_t source_width, uint32_t source_height,
                         const PresentRect& dst, const CropRect& crop, int effect) {
    if (!EnsureEffectPipeline()) return false;
    if (swapchain_index >= g_vk.swapchain_framebuffers.size()) return false;

    VkCommandBuffer command_buffer = g_vk.command_buffer;
    vkResetCommandBuffer(command_buffer, 0);

    VkCommandBufferBeginInfo begin_info{};
    begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(command_buffer, &begin_info) != VK_SUCCESS) return false;

    const VkImage source_image = g_vk.frame_image.create_info.image;
    const VkImageLayout source_layout = g_vk.frame_image.image_layout;
    const VkImage target_image = g_vk.swapchain_images[swapchain_index];

    // The push-constant source rect is normalised texture space; the crop
    // offsets trim the sampled region to the user's selection.
    const float inv_width = source_width > 0 ? 1.0f / static_cast<float>(source_width) : 0.0f;
    const float inv_height = source_height > 0 ? 1.0f / static_cast<float>(source_height) : 0.0f;

    VkImageMemoryBarrier source_barrier{};
    source_barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    source_barrier.oldLayout = source_layout;
    source_barrier.newLayout = source_layout;
    source_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    source_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    source_barrier.image = source_image;
    source_barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    source_barrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT;
    source_barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &source_barrier);

    VkImageMemoryBarrier target_barrier{};
    target_barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    target_barrier.oldLayout =
        g_vk.swapchain_initialized[swapchain_index] ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR : VK_IMAGE_LAYOUT_UNDEFINED;
    target_barrier.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    target_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    target_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    target_barrier.image = target_image;
    target_barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    target_barrier.srcAccessMask = 0;
    target_barrier.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &target_barrier);

    VkDescriptorImageInfo point_info{};
    point_info.sampler = g_vk.effect_point_sampler;
    point_info.imageView = g_vk.frame_image.image_view;
    point_info.imageLayout = source_layout;
    VkDescriptorImageInfo linear_info{};
    linear_info.sampler = g_vk.effect_linear_sampler;
    linear_info.imageView = g_vk.frame_image.image_view;
    linear_info.imageLayout = source_layout;

    VkWriteDescriptorSet writes[2]{};
    for (uint32_t i = 0; i < 2; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = g_vk.effect_descriptor_set;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[i].pImageInfo = (i == 0) ? &point_info : &linear_info;
    }
    vkUpdateDescriptorSets(g_vk.device, 2, writes, 0, nullptr);

    VkClearValue clear{};
    clear.color = {{0.0f, 0.0f, 0.0f, 1.0f}};
    VkRenderPassBeginInfo render_pass_info{};
    render_pass_info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    render_pass_info.renderPass = g_vk.effect_render_pass;
    render_pass_info.framebuffer = g_vk.swapchain_framebuffers[swapchain_index];
    render_pass_info.renderArea = {{0, 0}, g_vk.swapchain_extent};
    render_pass_info.clearValueCount = 1;
    render_pass_info.pClearValues = &clear;
    vkCmdBeginRenderPass(command_buffer, &render_pass_info, VK_SUBPASS_CONTENTS_INLINE);

    // The fullscreen triangle only covers exactly the destination rect when
    // the viewport is moved to it. Leaving the viewport full-screen lets the
    // oversized triangle rasterize past the rect, and those extra pixels
    // sample the source rect's edge (CLAMP_TO_EDGE), painting the letterbox
    // bars with the frame's edge colours instead of leaving them black. Map
    // the unit destination rect through a destination-sized viewport instead.
    const VkViewport viewport{static_cast<float>(dst.x), static_cast<float>(dst.y),
                              static_cast<float>(dst.width), static_cast<float>(dst.height), 0.0f, 1.0f};
    const VkRect2D scissor{{dst.x, dst.y}, {static_cast<uint32_t>(dst.width), static_cast<uint32_t>(dst.height)}};
    vkCmdSetViewport(command_buffer, 0, 1, &viewport);
    vkCmdSetScissor(command_buffer, 0, 1, &scissor);
    vkCmdBindPipeline(command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, g_vk.effect_pipeline);
    vkCmdBindDescriptorSets(command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, g_vk.effect_pipeline_layout, 0, 1,
                            &g_vk.effect_descriptor_set, 0, nullptr);

    PresentPushConstants constants{};
    constants.dst_x = 0.0f;
    constants.dst_y = 0.0f;
    constants.dst_w = 1.0f;
    constants.dst_h = 1.0f;
    constants.src_x = static_cast<float>(crop.left) * inv_width;
    constants.src_y = static_cast<float>(crop.top) * inv_height;
    constants.src_w = static_cast<float>(static_cast<int32_t>(source_width) - crop.left - crop.right) *
                      inv_width;
    constants.src_h = static_cast<float>(static_cast<int32_t>(source_height) - crop.top - crop.bottom) *
                      inv_height;
    // Output pixel count of the drawn image, used by the sharpening effect to
    // derive its texel scale from the on-screen size.
    constants.out_w = static_cast<float>(dst.width);
    constants.out_h = static_cast<float>(dst.height);
    constants.effect = static_cast<float>(effect);
    vkCmdPushConstants(command_buffer, g_vk.effect_pipeline_layout,
                       VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(constants), &constants);
    vkCmdDraw(command_buffer, 3, 1, 0, 0);
    vkCmdEndRenderPass(command_buffer);

    target_barrier.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    target_barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    target_barrier.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    target_barrier.dstAccessMask = 0;
    vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &target_barrier);

    return vkEndCommandBuffer(command_buffer) == VK_SUCCESS;
}
#endif  // !EMUCOREA_HAVE_LIBRASHADER

#if defined(EMUCOREA_HAVE_LIBRASHADER)
bool RecordPresentShaderChain(uint32_t swapchain_index, uint32_t source_width, uint32_t source_height,
                              const PresentRect& dst) {
    if (g_vk.shader_chain == nullptr || g_vk.chain_target_image == VK_NULL_HANDLE) return false;
    if (swapchain_index >= g_vk.swapchain_images.size()) return false;

    VkCommandBuffer command_buffer = g_vk.command_buffer;
    vkResetCommandBuffer(command_buffer, 0);

    VkCommandBufferBeginInfo begin_info{};
    begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(command_buffer, &begin_info) != VK_SUCCESS) return false;

    const VkImageLayout source_layout = g_vk.frame_image.image_layout;
    VkImageMemoryBarrier source_barrier{};
    source_barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    source_barrier.oldLayout = source_layout;
    source_barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    source_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    source_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    source_barrier.image = g_vk.frame_image.create_info.image;
    source_barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    source_barrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT;
    source_barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &source_barrier);

    VkImageMemoryBarrier target_barrier{};
    target_barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    target_barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    target_barrier.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    target_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    target_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    target_barrier.image = g_vk.chain_target_image;
    target_barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    target_barrier.srcAccessMask = 0;
    target_barrier.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &target_barrier);

    // The core presents BGRA on Vulkan. Preserve its declared format rather than
    // reinterpreting the same bytes as RGBA (which swaps red and blue).
    const libra_image_vk_t in = {g_vk.frame_image.create_info.image, g_vk.frame_image.create_info.format, source_width,
                                 source_height};
    const libra_image_vk_t out = {g_vk.chain_target_image, VK_FORMAT_R8G8B8A8_UNORM, g_vk.chain_target_width,
                                  g_vk.chain_target_height};
    const libra_viewport_t viewport = {0.0f, 0.0f, g_vk.chain_target_width, g_vk.chain_target_height};

    libra_vk_filter_chain_t chain = static_cast<libra_vk_filter_chain_t>(g_vk.shader_chain);
    if (libra_error_t err =
            libra_vk_filter_chain_frame(&chain, command_buffer, g_vk.shader_frame_count, in, out, &viewport,
                                        nullptr, nullptr)) {
        ReportShaderChainError("frame", err);
        g_vk.shader_chain_failed = true;
        vkEndCommandBuffer(command_buffer);
        return false;
    }
    ++g_vk.shader_frame_count;

    target_barrier.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    target_barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    target_barrier.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    target_barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &target_barrier);

    VkImageMemoryBarrier swapchain_barrier{};
    swapchain_barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    swapchain_barrier.oldLayout =
        g_vk.swapchain_initialized[swapchain_index] ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR : VK_IMAGE_LAYOUT_UNDEFINED;
    swapchain_barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    swapchain_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    swapchain_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    swapchain_barrier.image = g_vk.swapchain_images[swapchain_index];
    swapchain_barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    swapchain_barrier.srcAccessMask = 0;
    swapchain_barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0,
                         nullptr, 0, nullptr, 1, &swapchain_barrier);

    VkClearColorValue clear{};
    const VkImageSubresourceRange clear_range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdClearColorImage(command_buffer, g_vk.swapchain_images[swapchain_index],
                         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1, &clear_range);

    VkImageBlit blit{};
    blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.srcOffsets[0] = {0, 0, 0};
    blit.srcOffsets[1] = {static_cast<int32_t>(g_vk.chain_target_width),
                          static_cast<int32_t>(g_vk.chain_target_height), 1};
    blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.dstOffsets[0] = {dst.x, dst.y, 0};
    blit.dstOffsets[1] = {dst.x + dst.width, dst.y + dst.height, 1};
    vkCmdBlitImage(command_buffer, g_vk.chain_target_image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                   g_vk.swapchain_images[swapchain_index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit,
                   VK_FILTER_LINEAR);

    swapchain_barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    swapchain_barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    swapchain_barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    swapchain_barrier.dstAccessMask = 0;
    vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
                         nullptr, 0, nullptr, 1, &swapchain_barrier);

    return vkEndCommandBuffer(command_buffer) == VK_SUCCESS;
}
#endif  // EMUCOREA_HAVE_LIBRASHADER

}  // namespace

bool Prepare(ANativeWindow* window, uint32_t window_generation) {
    if (g_vk.requested || g_vk.active) return true;
    if (window == nullptr) return false;

    if (!EnsureInstance() || !EnsureSurface(window, window_generation) || !PickPhysicalDevice()) {
        Destroy();
        return false;
    }
    g_vk.window_generation = window_generation;
    return true;
}

bool AcceptHardwareRender(retro_hw_render_callback* callback) {
    if (callback == nullptr) return false;
    g_vk.requested = true;
    g_vk.callback = *callback;
    g_vk.has_callback = true;
    g_vk.context_reset_pending = true;
    if (!g_vk.active) g_vk.failed = false;
    VK_LOGI("Accepted Vulkan hardware renderer request");
    return true;
}

bool AcceptNegotiationInterface(const retro_hw_render_context_negotiation_interface* negotiation) {
    if (negotiation == nullptr ||
        negotiation->interface_type != RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN) {
        VK_LOGE("Rejected negotiation interface (type=%d)", negotiation != nullptr
                ? static_cast<int>(negotiation->interface_type) : -1);
        return false;
    }
    g_vk.negotiation = *reinterpret_cast<const retro_hw_render_context_negotiation_interface_vulkan*>(negotiation);
    g_vk.has_negotiation = true;
    VK_LOGI("Vulkan negotiation interface registered (version %u)", g_vk.negotiation.interface_version);
    return true;
}

bool FillHardwareRenderInterface(retro_hw_render_interface** out_interface) {
    if (out_interface == nullptr || !g_vk.requested || g_vk.device == VK_NULL_HANDLE) return false;
    FillInterface();
    *out_interface = reinterpret_cast<retro_hw_render_interface*>(&g_vk.interface);
    return true;
}

bool IsRequested() {
    return g_vk.requested;
}

bool IsActive() {
    return g_vk.active && !g_vk.failed;
}

void NotifyContextDestroy() {
    // The core's context_destroy tears down a render context it created during
    // context_reset. When a load failed before the context was ever created the
    // callback must not run, or the core destroys uninitialised GPU state.
    if (g_vk.active && g_vk.has_callback && g_vk.callback.context_destroy != nullptr) {
        g_vk.callback.context_destroy();
    }
    g_vk.active = false;
    g_vk.context_reset_pending = false;
}

void SetShaderEffect(int effect) {
    g_shader_effect.store(effect, std::memory_order_relaxed);
}

void SetDisplayCrop(int left, int top, int right, int bottom) {
    const auto clamp = [](int value) {
        if (value < 0) return 0;
        if (value > 64) return 64;
        return value;
    };
    g_vk.crop_left = clamp(left);
    g_vk.crop_top = clamp(top);
    g_vk.crop_right = clamp(right);
    g_vk.crop_bottom = clamp(bottom);
}

bool EnsureContext(ANativeWindow* window, uint32_t window_generation) {
    if (!g_vk.requested) return false;
    if (window == nullptr) return false;
    if (g_vk.failed) {
        if (window_generation == g_vk.window_generation) return false;
        g_vk.failed = false;
        g_vk.swapchain_failures = 0;
    }

    const bool window_changed =
        window_generation != g_vk.preparation_generation || g_vk.preparation_window != window;
    if (g_vk.active && !window_changed && g_vk.swapchain != VK_NULL_HANDLE &&
        !g_vk.context_reset_pending) {
        return true;
    }

    const bool was_active = g_vk.active;
    if (!EnsureInstance() || !EnsureSurface(window, window_generation) || !PickPhysicalDevice() ||
        !CreateDevice()) {
        g_vk.failed = true;
        return false;
    }

    if (window_changed || g_vk.swapchain == VK_NULL_HANDLE) {
        if (!RecreateSwapchain()) return false;
    }

    g_vk.window_generation = window_generation;
    g_vk.active = true;
    g_vk.has_frame_image = false;
    const bool need_reset = !was_active || g_vk.context_reset_pending;
    g_vk.context_reset_pending = false;
    if (need_reset) {
        if (g_vk.has_callback && g_vk.callback.context_reset != nullptr) {
            g_vk.callback.context_reset();
        }
        VK_LOGI("Vulkan hardware context reset complete");
    } else if (window_changed) {
        VK_LOGI("Vulkan presentation surface rebound; core display kept");
    }
    return true;
}

bool Present(uint32_t source_width, uint32_t source_height, double display_aspect, bool stretch,
             bool adjust_aspect_for_crop) {
    if (!IsActive() || g_vk.device == VK_NULL_HANDLE || g_vk.swapchain == VK_NULL_HANDLE || !g_vk.has_frame_image) {
        return false;
    }
    if (source_width == 0 || source_height == 0) return false;

    if (vkWaitForFences(g_vk.device, 1, &g_vk.frame_fence, VK_TRUE, kFenceWaitTimeoutNs) != VK_SUCCESS) {
        VK_LOGW("Vulkan frame fence timed out");
        return false;
    }

    uint32_t swapchain_index = 0;
    VkResult result = g_vk.pfn_acquire(g_vk.device, g_vk.swapchain, UINT64_MAX, g_vk.acquire_semaphore,
                                       VK_NULL_HANDLE, &swapchain_index);
    if (result == VK_ERROR_OUT_OF_DATE_KHR) {
        RecreateSwapchain();
        return false;
    }
    // A swapchain whose preTransform differs from the surface's current
    // transform reports SUBOPTIMAL_KHR on every present while still presenting
    // correctly; rebuilding it there just burns a full swapchain per frame.
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        VK_LOGW("vkAcquireNextImageKHR failed (0x%x)", result);
        return false;
    }
    if (swapchain_index >= g_vk.swapchain_images.size()) return false;

#if defined(EMUCOREA_HAVE_LIBRASHADER)
    const bool want_chain = shader_chain::IsEnabled() && !shader_chain::PresetPath().empty();
#else
    constexpr bool want_chain = false;
#endif
    // Shader chains sample the whole frame image (no sub-rect input), so the
    // crop applies only to the direct present paths.
    const CropRect crop = want_chain
        ? CropRect{0, 0, 0, 0}
        : ClampCrop(CropRect{g_vk.crop_left, g_vk.crop_top, g_vk.crop_right, g_vk.crop_bottom},
                    source_width, source_height);
    if (adjust_aspect_for_crop && IsCropActive(crop)) {
        // The caller's aspect describes the full frame; rescale it for the
        // trimmed region so the fit stays undistorted.
        const double source_aspect = static_cast<double>(source_width) / static_cast<double>(source_height);
        const double crop_aspect = static_cast<double>(source_width - crop.left - crop.right) /
                                   static_cast<double>(source_height - crop.top - crop.bottom);
        display_aspect *= crop_aspect / source_aspect;
    }

    const PresentRect dst = FitDisplayRect(g_vk.swapchain_extent, display_aspect, stretch);
    bool recorded = false;
#if defined(EMUCOREA_HAVE_LIBRASHADER)
    if (want_chain && EnsureShaderChain() && EnsureShaderChainTarget(dst.width, dst.height)) {
        recorded = RecordPresentShaderChain(swapchain_index, source_width, source_height, dst);
        if (!recorded) VK_LOGW("librashader chain frame failed; falling back to blit");
    }
    if (!recorded) {
        recorded = RecordPresentBlit(swapchain_index, source_width, source_height, dst, crop);
    }
#else
    int effect = g_shader_effect.load(std::memory_order_relaxed);
    if (effect == 0) {
        effect = 5; // Fallback to Bilinear shader quad for reliable linear upscaling
    }
    if (effect != 0) {
        recorded = RecordPresentEffect(swapchain_index, source_width, source_height, dst, crop, effect);
        if (!recorded) VK_LOGW("Vulkan shader effect path failed; falling back to blit");
    }
    if (!recorded) {
        recorded = RecordPresentBlit(swapchain_index, source_width, source_height, dst, crop);
    }
#endif
    if (!recorded) {
        VK_LOGW("Failed to record Vulkan present commands");
        return false;
    }

    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo submit_info{};
    submit_info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit_info.waitSemaphoreCount = 1;
    submit_info.pWaitSemaphores = &g_vk.acquire_semaphore;
    submit_info.pWaitDstStageMask = &wait_stage;
    submit_info.commandBufferCount = 1;
    submit_info.pCommandBuffers = &g_vk.command_buffer;
    submit_info.signalSemaphoreCount = 1;
    submit_info.pSignalSemaphores = &g_vk.present_semaphores[swapchain_index];
    std::unique_lock<std::mutex> queue_lock(g_queue_mutex);
    vkResetFences(g_vk.device, 1, &g_vk.frame_fence);
    if (vkQueueSubmit(g_vk.queue, 1, &submit_info, g_vk.frame_fence) != VK_SUCCESS) {
        g_vk.failed = true;
        VK_LOGW("vkQueueSubmit failed");
        return false;
    }

    VkPresentInfoKHR present_info{};
    present_info.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present_info.waitSemaphoreCount = 1;
    present_info.pWaitSemaphores = &g_vk.present_semaphores[swapchain_index];
    present_info.swapchainCount = 1;
    present_info.pSwapchains = &g_vk.swapchain;
    present_info.pImageIndices = &swapchain_index;
    result = g_vk.pfn_queue_present(g_vk.present_queue, &present_info);
    queue_lock.unlock();
    g_vk.swapchain_initialized[swapchain_index] = true;
    g_vk.submitted_sync_index = g_vk.sync_index;
    g_vk.sync_index = (g_vk.sync_index + 1) % 2;

    if (result == VK_ERROR_OUT_OF_DATE_KHR) {
        RecreateSwapchain();
    } else if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        VK_LOGW("vkQueuePresentKHR failed (0x%x)", result);
        return false;
    }
    // The next core frame uses the other image while this submission finishes.
    return true;
}

void Destroy() {
    if (g_vk.device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(g_vk.device);
        DestroySwapchain();
#if defined(EMUCOREA_HAVE_LIBRASHADER)
        DestroyShaderChain();
#endif
        if (g_vk.command_pool != VK_NULL_HANDLE) vkDestroyCommandPool(g_vk.device, g_vk.command_pool, nullptr);
        if (g_vk.acquire_semaphore != VK_NULL_HANDLE)
            vkDestroySemaphore(g_vk.device, g_vk.acquire_semaphore, nullptr);
        if (g_vk.frame_fence != VK_NULL_HANDLE) vkDestroyFence(g_vk.device, g_vk.frame_fence, nullptr);
        if (g_vk.effect_descriptor_pool != VK_NULL_HANDLE)
            vkDestroyDescriptorPool(g_vk.device, g_vk.effect_descriptor_pool, nullptr);
        if (g_vk.effect_pipeline_layout != VK_NULL_HANDLE)
            vkDestroyPipelineLayout(g_vk.device, g_vk.effect_pipeline_layout, nullptr);
        if (g_vk.effect_descriptor_layout != VK_NULL_HANDLE)
            vkDestroyDescriptorSetLayout(g_vk.device, g_vk.effect_descriptor_layout, nullptr);
        if (g_vk.effect_vertex_module != VK_NULL_HANDLE)
            vkDestroyShaderModule(g_vk.device, g_vk.effect_vertex_module, nullptr);
        if (g_vk.effect_fragment_module != VK_NULL_HANDLE)
            vkDestroyShaderModule(g_vk.device, g_vk.effect_fragment_module, nullptr);
        if (g_vk.effect_point_sampler != VK_NULL_HANDLE)
            vkDestroySampler(g_vk.device, g_vk.effect_point_sampler, nullptr);
        if (g_vk.effect_linear_sampler != VK_NULL_HANDLE)
            vkDestroySampler(g_vk.device, g_vk.effect_linear_sampler, nullptr);
        vkDestroyDevice(g_vk.device, nullptr);
        VK_LOGI("Vulkan render device destroyed");
    }
    if (g_vk.surface != VK_NULL_HANDLE && g_vk.instance != VK_NULL_HANDLE && g_vk.pfn_destroy_surface != nullptr) {
        g_vk.pfn_destroy_surface(g_vk.instance, g_vk.surface, nullptr);
    }
    if (g_vk.instance != VK_NULL_HANDLE) vkDestroyInstance(g_vk.instance, nullptr);
    ResetState();
}

}  // namespace emucoreh::vulkan

