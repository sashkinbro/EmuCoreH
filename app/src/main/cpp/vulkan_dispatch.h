// Vulkan entry points are resolved from the selected loader, not the system import table.
#pragma once
#include <dlfcn.h>
#include <vulkan/vulkan.h>

static PFN_vkAllocateCommandBuffers dispatch_vkAllocateCommandBuffers = nullptr;
static PFN_vkAllocateDescriptorSets dispatch_vkAllocateDescriptorSets = nullptr;
static PFN_vkAllocateMemory dispatch_vkAllocateMemory = nullptr;
static PFN_vkBeginCommandBuffer dispatch_vkBeginCommandBuffer = nullptr;
static PFN_vkBindBufferMemory dispatch_vkBindBufferMemory = nullptr;
static PFN_vkBindImageMemory dispatch_vkBindImageMemory = nullptr;
static PFN_vkCmdBeginRenderPass dispatch_vkCmdBeginRenderPass = nullptr;
static PFN_vkCmdBindDescriptorSets dispatch_vkCmdBindDescriptorSets = nullptr;
static PFN_vkCmdBindPipeline dispatch_vkCmdBindPipeline = nullptr;
static PFN_vkCmdBlitImage dispatch_vkCmdBlitImage = nullptr;
static PFN_vkCmdClearColorImage dispatch_vkCmdClearColorImage = nullptr;
static PFN_vkCmdCopyBufferToImage dispatch_vkCmdCopyBufferToImage = nullptr;
static PFN_vkCmdDraw dispatch_vkCmdDraw = nullptr;
static PFN_vkCmdEndRenderPass dispatch_vkCmdEndRenderPass = nullptr;
static PFN_vkCmdPipelineBarrier dispatch_vkCmdPipelineBarrier = nullptr;
static PFN_vkCmdPushConstants dispatch_vkCmdPushConstants = nullptr;
static PFN_vkCmdSetScissor dispatch_vkCmdSetScissor = nullptr;
static PFN_vkCmdSetViewport dispatch_vkCmdSetViewport = nullptr;
static PFN_vkCreateBuffer dispatch_vkCreateBuffer = nullptr;
static PFN_vkCreateCommandPool dispatch_vkCreateCommandPool = nullptr;
static PFN_vkCreateDescriptorPool dispatch_vkCreateDescriptorPool = nullptr;
static PFN_vkCreateDescriptorSetLayout dispatch_vkCreateDescriptorSetLayout = nullptr;
static PFN_vkCreateDevice dispatch_vkCreateDevice = nullptr;
static PFN_vkCreateFence dispatch_vkCreateFence = nullptr;
static PFN_vkCreateFramebuffer dispatch_vkCreateFramebuffer = nullptr;
static PFN_vkCreateGraphicsPipelines dispatch_vkCreateGraphicsPipelines = nullptr;
static PFN_vkCreateImage dispatch_vkCreateImage = nullptr;
static PFN_vkCreateImageView dispatch_vkCreateImageView = nullptr;
static PFN_vkCreateInstance dispatch_vkCreateInstance = nullptr;
static PFN_vkCreatePipelineLayout dispatch_vkCreatePipelineLayout = nullptr;
static PFN_vkCreateRenderPass dispatch_vkCreateRenderPass = nullptr;
static PFN_vkCreateSampler dispatch_vkCreateSampler = nullptr;
static PFN_vkCreateSemaphore dispatch_vkCreateSemaphore = nullptr;
static PFN_vkCreateShaderModule dispatch_vkCreateShaderModule = nullptr;
static PFN_vkDestroyBuffer dispatch_vkDestroyBuffer = nullptr;
static PFN_vkDestroyCommandPool dispatch_vkDestroyCommandPool = nullptr;
static PFN_vkDestroyDescriptorPool dispatch_vkDestroyDescriptorPool = nullptr;
static PFN_vkDestroyDescriptorSetLayout dispatch_vkDestroyDescriptorSetLayout = nullptr;
static PFN_vkDestroyDevice dispatch_vkDestroyDevice = nullptr;
static PFN_vkDestroyFence dispatch_vkDestroyFence = nullptr;
static PFN_vkDestroyFramebuffer dispatch_vkDestroyFramebuffer = nullptr;
static PFN_vkDestroyImage dispatch_vkDestroyImage = nullptr;
static PFN_vkDestroyImageView dispatch_vkDestroyImageView = nullptr;
static PFN_vkDestroyInstance dispatch_vkDestroyInstance = nullptr;
static PFN_vkDestroyPipeline dispatch_vkDestroyPipeline = nullptr;
static PFN_vkDestroyPipelineLayout dispatch_vkDestroyPipelineLayout = nullptr;
static PFN_vkDestroyRenderPass dispatch_vkDestroyRenderPass = nullptr;
static PFN_vkDestroySampler dispatch_vkDestroySampler = nullptr;
static PFN_vkDestroySemaphore dispatch_vkDestroySemaphore = nullptr;
static PFN_vkDestroyShaderModule dispatch_vkDestroyShaderModule = nullptr;
static PFN_vkDeviceWaitIdle dispatch_vkDeviceWaitIdle = nullptr;
static PFN_vkEndCommandBuffer dispatch_vkEndCommandBuffer = nullptr;
static PFN_vkEnumerateDeviceExtensionProperties dispatch_vkEnumerateDeviceExtensionProperties = nullptr;
static PFN_vkEnumerateInstanceExtensionProperties dispatch_vkEnumerateInstanceExtensionProperties = nullptr;
static PFN_vkEnumeratePhysicalDevices dispatch_vkEnumeratePhysicalDevices = nullptr;
static PFN_vkFreeMemory dispatch_vkFreeMemory = nullptr;
static PFN_vkGetBufferMemoryRequirements dispatch_vkGetBufferMemoryRequirements = nullptr;
static PFN_vkGetDeviceProcAddr dispatch_vkGetDeviceProcAddr = nullptr;
static PFN_vkGetDeviceQueue dispatch_vkGetDeviceQueue = nullptr;
static PFN_vkGetImageMemoryRequirements dispatch_vkGetImageMemoryRequirements = nullptr;
static PFN_vkGetInstanceProcAddr dispatch_vkGetInstanceProcAddr = nullptr;
static PFN_vkGetPhysicalDeviceMemoryProperties dispatch_vkGetPhysicalDeviceMemoryProperties = nullptr;
static PFN_vkGetPhysicalDeviceProperties dispatch_vkGetPhysicalDeviceProperties = nullptr;
static PFN_vkGetPhysicalDeviceQueueFamilyProperties dispatch_vkGetPhysicalDeviceQueueFamilyProperties = nullptr;
static PFN_vkGetSwapchainImagesKHR dispatch_vkGetSwapchainImagesKHR = nullptr;
static PFN_vkMapMemory dispatch_vkMapMemory = nullptr;
static PFN_vkQueueSubmit dispatch_vkQueueSubmit = nullptr;
static PFN_vkResetCommandBuffer dispatch_vkResetCommandBuffer = nullptr;
static PFN_vkResetFences dispatch_vkResetFences = nullptr;
static PFN_vkUnmapMemory dispatch_vkUnmapMemory = nullptr;
static PFN_vkUpdateDescriptorSets dispatch_vkUpdateDescriptorSets = nullptr;
static PFN_vkWaitForFences dispatch_vkWaitForFences = nullptr;

#define vkAllocateCommandBuffers dispatch_vkAllocateCommandBuffers
#define vkAllocateDescriptorSets dispatch_vkAllocateDescriptorSets
#define vkAllocateMemory dispatch_vkAllocateMemory
#define vkBeginCommandBuffer dispatch_vkBeginCommandBuffer
#define vkBindBufferMemory dispatch_vkBindBufferMemory
#define vkBindImageMemory dispatch_vkBindImageMemory
#define vkCmdBeginRenderPass dispatch_vkCmdBeginRenderPass
#define vkCmdBindDescriptorSets dispatch_vkCmdBindDescriptorSets
#define vkCmdBindPipeline dispatch_vkCmdBindPipeline
#define vkCmdBlitImage dispatch_vkCmdBlitImage
#define vkCmdClearColorImage dispatch_vkCmdClearColorImage
#define vkCmdCopyBufferToImage dispatch_vkCmdCopyBufferToImage
#define vkCmdDraw dispatch_vkCmdDraw
#define vkCmdEndRenderPass dispatch_vkCmdEndRenderPass
#define vkCmdPipelineBarrier dispatch_vkCmdPipelineBarrier
#define vkCmdPushConstants dispatch_vkCmdPushConstants
#define vkCmdSetScissor dispatch_vkCmdSetScissor
#define vkCmdSetViewport dispatch_vkCmdSetViewport
#define vkCreateBuffer dispatch_vkCreateBuffer
#define vkCreateCommandPool dispatch_vkCreateCommandPool
#define vkCreateDescriptorPool dispatch_vkCreateDescriptorPool
#define vkCreateDescriptorSetLayout dispatch_vkCreateDescriptorSetLayout
#define vkCreateDevice dispatch_vkCreateDevice
#define vkCreateFence dispatch_vkCreateFence
#define vkCreateFramebuffer dispatch_vkCreateFramebuffer
#define vkCreateGraphicsPipelines dispatch_vkCreateGraphicsPipelines
#define vkCreateImage dispatch_vkCreateImage
#define vkCreateImageView dispatch_vkCreateImageView
#define vkCreateInstance dispatch_vkCreateInstance
#define vkCreatePipelineLayout dispatch_vkCreatePipelineLayout
#define vkCreateRenderPass dispatch_vkCreateRenderPass
#define vkCreateSampler dispatch_vkCreateSampler
#define vkCreateSemaphore dispatch_vkCreateSemaphore
#define vkCreateShaderModule dispatch_vkCreateShaderModule
#define vkDestroyBuffer dispatch_vkDestroyBuffer
#define vkDestroyCommandPool dispatch_vkDestroyCommandPool
#define vkDestroyDescriptorPool dispatch_vkDestroyDescriptorPool
#define vkDestroyDescriptorSetLayout dispatch_vkDestroyDescriptorSetLayout
#define vkDestroyDevice dispatch_vkDestroyDevice
#define vkDestroyFence dispatch_vkDestroyFence
#define vkDestroyFramebuffer dispatch_vkDestroyFramebuffer
#define vkDestroyImage dispatch_vkDestroyImage
#define vkDestroyImageView dispatch_vkDestroyImageView
#define vkDestroyInstance dispatch_vkDestroyInstance
#define vkDestroyPipeline dispatch_vkDestroyPipeline
#define vkDestroyPipelineLayout dispatch_vkDestroyPipelineLayout
#define vkDestroyRenderPass dispatch_vkDestroyRenderPass
#define vkDestroySampler dispatch_vkDestroySampler
#define vkDestroySemaphore dispatch_vkDestroySemaphore
#define vkDestroyShaderModule dispatch_vkDestroyShaderModule
#define vkDeviceWaitIdle dispatch_vkDeviceWaitIdle
#define vkEndCommandBuffer dispatch_vkEndCommandBuffer
#define vkEnumerateDeviceExtensionProperties dispatch_vkEnumerateDeviceExtensionProperties
#define vkEnumerateInstanceExtensionProperties dispatch_vkEnumerateInstanceExtensionProperties
#define vkEnumeratePhysicalDevices dispatch_vkEnumeratePhysicalDevices
#define vkFreeMemory dispatch_vkFreeMemory
#define vkGetBufferMemoryRequirements dispatch_vkGetBufferMemoryRequirements
#define vkGetDeviceProcAddr dispatch_vkGetDeviceProcAddr
#define vkGetDeviceQueue dispatch_vkGetDeviceQueue
#define vkGetImageMemoryRequirements dispatch_vkGetImageMemoryRequirements
#define vkGetInstanceProcAddr dispatch_vkGetInstanceProcAddr
#define vkGetPhysicalDeviceMemoryProperties dispatch_vkGetPhysicalDeviceMemoryProperties
#define vkGetPhysicalDeviceProperties dispatch_vkGetPhysicalDeviceProperties
#define vkGetPhysicalDeviceQueueFamilyProperties dispatch_vkGetPhysicalDeviceQueueFamilyProperties
#define vkGetSwapchainImagesKHR dispatch_vkGetSwapchainImagesKHR
#define vkMapMemory dispatch_vkMapMemory
#define vkQueueSubmit dispatch_vkQueueSubmit
#define vkResetCommandBuffer dispatch_vkResetCommandBuffer
#define vkResetFences dispatch_vkResetFences
#define vkUnmapMemory dispatch_vkUnmapMemory
#define vkUpdateDescriptorSets dispatch_vkUpdateDescriptorSets
#define vkWaitForFences dispatch_vkWaitForFences

static bool LoadVulkanGlobals(void* library) {
    dispatch_vkGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
    if (!dispatch_vkGetInstanceProcAddr) return false;
    dispatch_vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(dispatch_vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance"));
    dispatch_vkEnumerateInstanceExtensionProperties = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(dispatch_vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties"));
    return dispatch_vkCreateInstance && dispatch_vkEnumerateInstanceExtensionProperties && dispatch_vkGetInstanceProcAddr;
}

static bool LoadVulkanInstance(VkInstance instance) {
    dispatch_vkCreateDevice = reinterpret_cast<PFN_vkCreateDevice>(dispatch_vkGetInstanceProcAddr(instance, "vkCreateDevice"));
    dispatch_vkDestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(dispatch_vkGetInstanceProcAddr(instance, "vkDestroyInstance"));
    dispatch_vkEnumerateDeviceExtensionProperties = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(dispatch_vkGetInstanceProcAddr(instance, "vkEnumerateDeviceExtensionProperties"));
    dispatch_vkEnumeratePhysicalDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(dispatch_vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
    dispatch_vkGetDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(dispatch_vkGetInstanceProcAddr(instance, "vkGetDeviceProcAddr"));
    dispatch_vkGetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties>(dispatch_vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceMemoryProperties"));
    dispatch_vkGetPhysicalDeviceProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(dispatch_vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
    dispatch_vkGetPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(dispatch_vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    return dispatch_vkCreateDevice && dispatch_vkDestroyInstance && dispatch_vkEnumerateDeviceExtensionProperties && dispatch_vkEnumeratePhysicalDevices && dispatch_vkGetDeviceProcAddr && dispatch_vkGetPhysicalDeviceMemoryProperties && dispatch_vkGetPhysicalDeviceProperties && dispatch_vkGetPhysicalDeviceQueueFamilyProperties;
}

static bool LoadVulkanDevice(VkDevice device) {
    dispatch_vkAllocateCommandBuffers = reinterpret_cast<PFN_vkAllocateCommandBuffers>(dispatch_vkGetDeviceProcAddr(device, "vkAllocateCommandBuffers"));
    dispatch_vkAllocateDescriptorSets = reinterpret_cast<PFN_vkAllocateDescriptorSets>(dispatch_vkGetDeviceProcAddr(device, "vkAllocateDescriptorSets"));
    dispatch_vkAllocateMemory = reinterpret_cast<PFN_vkAllocateMemory>(dispatch_vkGetDeviceProcAddr(device, "vkAllocateMemory"));
    dispatch_vkBeginCommandBuffer = reinterpret_cast<PFN_vkBeginCommandBuffer>(dispatch_vkGetDeviceProcAddr(device, "vkBeginCommandBuffer"));
    dispatch_vkBindBufferMemory = reinterpret_cast<PFN_vkBindBufferMemory>(dispatch_vkGetDeviceProcAddr(device, "vkBindBufferMemory"));
    dispatch_vkBindImageMemory = reinterpret_cast<PFN_vkBindImageMemory>(dispatch_vkGetDeviceProcAddr(device, "vkBindImageMemory"));
    dispatch_vkCmdBeginRenderPass = reinterpret_cast<PFN_vkCmdBeginRenderPass>(dispatch_vkGetDeviceProcAddr(device, "vkCmdBeginRenderPass"));
    dispatch_vkCmdBindDescriptorSets = reinterpret_cast<PFN_vkCmdBindDescriptorSets>(dispatch_vkGetDeviceProcAddr(device, "vkCmdBindDescriptorSets"));
    dispatch_vkCmdBindPipeline = reinterpret_cast<PFN_vkCmdBindPipeline>(dispatch_vkGetDeviceProcAddr(device, "vkCmdBindPipeline"));
    dispatch_vkCmdBlitImage = reinterpret_cast<PFN_vkCmdBlitImage>(dispatch_vkGetDeviceProcAddr(device, "vkCmdBlitImage"));
    dispatch_vkCmdClearColorImage = reinterpret_cast<PFN_vkCmdClearColorImage>(dispatch_vkGetDeviceProcAddr(device, "vkCmdClearColorImage"));
    dispatch_vkCmdCopyBufferToImage = reinterpret_cast<PFN_vkCmdCopyBufferToImage>(dispatch_vkGetDeviceProcAddr(device, "vkCmdCopyBufferToImage"));
    dispatch_vkCmdDraw = reinterpret_cast<PFN_vkCmdDraw>(dispatch_vkGetDeviceProcAddr(device, "vkCmdDraw"));
    dispatch_vkCmdEndRenderPass = reinterpret_cast<PFN_vkCmdEndRenderPass>(dispatch_vkGetDeviceProcAddr(device, "vkCmdEndRenderPass"));
    dispatch_vkCmdPipelineBarrier = reinterpret_cast<PFN_vkCmdPipelineBarrier>(dispatch_vkGetDeviceProcAddr(device, "vkCmdPipelineBarrier"));
    dispatch_vkCmdPushConstants = reinterpret_cast<PFN_vkCmdPushConstants>(dispatch_vkGetDeviceProcAddr(device, "vkCmdPushConstants"));
    dispatch_vkCmdSetScissor = reinterpret_cast<PFN_vkCmdSetScissor>(dispatch_vkGetDeviceProcAddr(device, "vkCmdSetScissor"));
    dispatch_vkCmdSetViewport = reinterpret_cast<PFN_vkCmdSetViewport>(dispatch_vkGetDeviceProcAddr(device, "vkCmdSetViewport"));
    dispatch_vkCreateBuffer = reinterpret_cast<PFN_vkCreateBuffer>(dispatch_vkGetDeviceProcAddr(device, "vkCreateBuffer"));
    dispatch_vkCreateCommandPool = reinterpret_cast<PFN_vkCreateCommandPool>(dispatch_vkGetDeviceProcAddr(device, "vkCreateCommandPool"));
    dispatch_vkCreateDescriptorPool = reinterpret_cast<PFN_vkCreateDescriptorPool>(dispatch_vkGetDeviceProcAddr(device, "vkCreateDescriptorPool"));
    dispatch_vkCreateDescriptorSetLayout = reinterpret_cast<PFN_vkCreateDescriptorSetLayout>(dispatch_vkGetDeviceProcAddr(device, "vkCreateDescriptorSetLayout"));
    dispatch_vkCreateFence = reinterpret_cast<PFN_vkCreateFence>(dispatch_vkGetDeviceProcAddr(device, "vkCreateFence"));
    dispatch_vkCreateFramebuffer = reinterpret_cast<PFN_vkCreateFramebuffer>(dispatch_vkGetDeviceProcAddr(device, "vkCreateFramebuffer"));
    dispatch_vkCreateGraphicsPipelines = reinterpret_cast<PFN_vkCreateGraphicsPipelines>(dispatch_vkGetDeviceProcAddr(device, "vkCreateGraphicsPipelines"));
    dispatch_vkCreateImage = reinterpret_cast<PFN_vkCreateImage>(dispatch_vkGetDeviceProcAddr(device, "vkCreateImage"));
    dispatch_vkCreateImageView = reinterpret_cast<PFN_vkCreateImageView>(dispatch_vkGetDeviceProcAddr(device, "vkCreateImageView"));
    dispatch_vkCreatePipelineLayout = reinterpret_cast<PFN_vkCreatePipelineLayout>(dispatch_vkGetDeviceProcAddr(device, "vkCreatePipelineLayout"));
    dispatch_vkCreateRenderPass = reinterpret_cast<PFN_vkCreateRenderPass>(dispatch_vkGetDeviceProcAddr(device, "vkCreateRenderPass"));
    dispatch_vkCreateSampler = reinterpret_cast<PFN_vkCreateSampler>(dispatch_vkGetDeviceProcAddr(device, "vkCreateSampler"));
    dispatch_vkCreateSemaphore = reinterpret_cast<PFN_vkCreateSemaphore>(dispatch_vkGetDeviceProcAddr(device, "vkCreateSemaphore"));
    dispatch_vkCreateShaderModule = reinterpret_cast<PFN_vkCreateShaderModule>(dispatch_vkGetDeviceProcAddr(device, "vkCreateShaderModule"));
    dispatch_vkDestroyBuffer = reinterpret_cast<PFN_vkDestroyBuffer>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyBuffer"));
    dispatch_vkDestroyCommandPool = reinterpret_cast<PFN_vkDestroyCommandPool>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyCommandPool"));
    dispatch_vkDestroyDescriptorPool = reinterpret_cast<PFN_vkDestroyDescriptorPool>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyDescriptorPool"));
    dispatch_vkDestroyDescriptorSetLayout = reinterpret_cast<PFN_vkDestroyDescriptorSetLayout>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyDescriptorSetLayout"));
    dispatch_vkDestroyDevice = reinterpret_cast<PFN_vkDestroyDevice>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyDevice"));
    dispatch_vkDestroyFence = reinterpret_cast<PFN_vkDestroyFence>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyFence"));
    dispatch_vkDestroyFramebuffer = reinterpret_cast<PFN_vkDestroyFramebuffer>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyFramebuffer"));
    dispatch_vkDestroyImage = reinterpret_cast<PFN_vkDestroyImage>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyImage"));
    dispatch_vkDestroyImageView = reinterpret_cast<PFN_vkDestroyImageView>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyImageView"));
    dispatch_vkDestroyPipeline = reinterpret_cast<PFN_vkDestroyPipeline>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyPipeline"));
    dispatch_vkDestroyPipelineLayout = reinterpret_cast<PFN_vkDestroyPipelineLayout>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyPipelineLayout"));
    dispatch_vkDestroyRenderPass = reinterpret_cast<PFN_vkDestroyRenderPass>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyRenderPass"));
    dispatch_vkDestroySampler = reinterpret_cast<PFN_vkDestroySampler>(dispatch_vkGetDeviceProcAddr(device, "vkDestroySampler"));
    dispatch_vkDestroySemaphore = reinterpret_cast<PFN_vkDestroySemaphore>(dispatch_vkGetDeviceProcAddr(device, "vkDestroySemaphore"));
    dispatch_vkDestroyShaderModule = reinterpret_cast<PFN_vkDestroyShaderModule>(dispatch_vkGetDeviceProcAddr(device, "vkDestroyShaderModule"));
    dispatch_vkDeviceWaitIdle = reinterpret_cast<PFN_vkDeviceWaitIdle>(dispatch_vkGetDeviceProcAddr(device, "vkDeviceWaitIdle"));
    dispatch_vkEndCommandBuffer = reinterpret_cast<PFN_vkEndCommandBuffer>(dispatch_vkGetDeviceProcAddr(device, "vkEndCommandBuffer"));
    dispatch_vkFreeMemory = reinterpret_cast<PFN_vkFreeMemory>(dispatch_vkGetDeviceProcAddr(device, "vkFreeMemory"));
    dispatch_vkGetBufferMemoryRequirements = reinterpret_cast<PFN_vkGetBufferMemoryRequirements>(dispatch_vkGetDeviceProcAddr(device, "vkGetBufferMemoryRequirements"));
    dispatch_vkGetDeviceQueue = reinterpret_cast<PFN_vkGetDeviceQueue>(dispatch_vkGetDeviceProcAddr(device, "vkGetDeviceQueue"));
    dispatch_vkGetImageMemoryRequirements = reinterpret_cast<PFN_vkGetImageMemoryRequirements>(dispatch_vkGetDeviceProcAddr(device, "vkGetImageMemoryRequirements"));
    dispatch_vkGetSwapchainImagesKHR = reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(dispatch_vkGetDeviceProcAddr(device, "vkGetSwapchainImagesKHR"));
    dispatch_vkMapMemory = reinterpret_cast<PFN_vkMapMemory>(dispatch_vkGetDeviceProcAddr(device, "vkMapMemory"));
    dispatch_vkQueueSubmit = reinterpret_cast<PFN_vkQueueSubmit>(dispatch_vkGetDeviceProcAddr(device, "vkQueueSubmit"));
    dispatch_vkResetCommandBuffer = reinterpret_cast<PFN_vkResetCommandBuffer>(dispatch_vkGetDeviceProcAddr(device, "vkResetCommandBuffer"));
    dispatch_vkResetFences = reinterpret_cast<PFN_vkResetFences>(dispatch_vkGetDeviceProcAddr(device, "vkResetFences"));
    dispatch_vkUnmapMemory = reinterpret_cast<PFN_vkUnmapMemory>(dispatch_vkGetDeviceProcAddr(device, "vkUnmapMemory"));
    dispatch_vkUpdateDescriptorSets = reinterpret_cast<PFN_vkUpdateDescriptorSets>(dispatch_vkGetDeviceProcAddr(device, "vkUpdateDescriptorSets"));
    dispatch_vkWaitForFences = reinterpret_cast<PFN_vkWaitForFences>(dispatch_vkGetDeviceProcAddr(device, "vkWaitForFences"));
    return dispatch_vkAllocateCommandBuffers && dispatch_vkAllocateDescriptorSets && dispatch_vkAllocateMemory && dispatch_vkBeginCommandBuffer && dispatch_vkBindBufferMemory && dispatch_vkBindImageMemory && dispatch_vkCmdBeginRenderPass && dispatch_vkCmdBindDescriptorSets && dispatch_vkCmdBindPipeline && dispatch_vkCmdBlitImage && dispatch_vkCmdClearColorImage && dispatch_vkCmdCopyBufferToImage && dispatch_vkCmdDraw && dispatch_vkCmdEndRenderPass && dispatch_vkCmdPipelineBarrier && dispatch_vkCmdPushConstants && dispatch_vkCmdSetScissor && dispatch_vkCmdSetViewport && dispatch_vkCreateBuffer && dispatch_vkCreateCommandPool && dispatch_vkCreateDescriptorPool && dispatch_vkCreateDescriptorSetLayout && dispatch_vkCreateFence && dispatch_vkCreateFramebuffer && dispatch_vkCreateGraphicsPipelines && dispatch_vkCreateImage && dispatch_vkCreateImageView && dispatch_vkCreatePipelineLayout && dispatch_vkCreateRenderPass && dispatch_vkCreateSampler && dispatch_vkCreateSemaphore && dispatch_vkCreateShaderModule && dispatch_vkDestroyBuffer && dispatch_vkDestroyCommandPool && dispatch_vkDestroyDescriptorPool && dispatch_vkDestroyDescriptorSetLayout && dispatch_vkDestroyDevice && dispatch_vkDestroyFence && dispatch_vkDestroyFramebuffer && dispatch_vkDestroyImage && dispatch_vkDestroyImageView && dispatch_vkDestroyPipeline && dispatch_vkDestroyPipelineLayout && dispatch_vkDestroyRenderPass && dispatch_vkDestroySampler && dispatch_vkDestroySemaphore && dispatch_vkDestroyShaderModule && dispatch_vkDeviceWaitIdle && dispatch_vkEndCommandBuffer && dispatch_vkFreeMemory && dispatch_vkGetBufferMemoryRequirements && dispatch_vkGetDeviceQueue && dispatch_vkGetImageMemoryRequirements && dispatch_vkGetSwapchainImagesKHR && dispatch_vkMapMemory && dispatch_vkQueueSubmit && dispatch_vkResetCommandBuffer && dispatch_vkResetFences && dispatch_vkUnmapMemory && dispatch_vkUpdateDescriptorSets && dispatch_vkWaitForFences;
}
