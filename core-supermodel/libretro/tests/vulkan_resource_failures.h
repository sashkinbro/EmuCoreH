// SPDX-License-Identifier: GPL-3.0-or-later
// Inject Vulkan allocation failures while forwarding successful calls to GPU.
#pragma once
#include <unordered_set>
#include <unordered_map>
#include <vulkan/vulkan.h>
namespace VulkanFailureTest {
enum class Point { none, imageView, secondShader, mapMemory };
inline Point point = Point::none;
inline unsigned shaders = 0;
inline std::unordered_set<VkImage> images;
inline std::unordered_set<VkDeviceMemory> memory;
inline std::unordered_set<VkBuffer> buffers;
inline std::unordered_set<VkShaderModule> modules;
inline std::unordered_map<VkImage, VkImageCreateFlags> imageFlags;
inline VkDeviceSize lastVertexBufferBytes = 0;
inline void Inject(Point p) {
  point = p;
  shaders = 0;
}
inline bool Empty() {
  return images.empty() && memory.empty() && buffers.empty() && modules.empty();
}
} // namespace VulkanFailureTest
extern "C" {
VkResult VKAPI_CALL __real_vkCreateImage(VkDevice, const VkImageCreateInfo *,
                                         const VkAllocationCallbacks *,
                                         VkImage *);
void VKAPI_CALL __real_vkDestroyImage(VkDevice, VkImage,
                                      const VkAllocationCallbacks *);
VkResult VKAPI_CALL __real_vkAllocateMemory(VkDevice,
                                            const VkMemoryAllocateInfo *,
                                            const VkAllocationCallbacks *,
                                            VkDeviceMemory *);
void VKAPI_CALL __real_vkFreeMemory(VkDevice, VkDeviceMemory,
                                    const VkAllocationCallbacks *);
VkResult VKAPI_CALL __real_vkCreateImageView(VkDevice,
                                             const VkImageViewCreateInfo *,
                                             const VkAllocationCallbacks *,
                                             VkImageView *);
VkResult VKAPI_CALL
__real_vkCreateShaderModule(VkDevice, const VkShaderModuleCreateInfo *,
                            const VkAllocationCallbacks *, VkShaderModule *);
void VKAPI_CALL __real_vkDestroyShaderModule(VkDevice, VkShaderModule,
                                             const VkAllocationCallbacks *);
VkResult VKAPI_CALL __real_vkCreateBuffer(VkDevice, const VkBufferCreateInfo *,
                                          const VkAllocationCallbacks *,
                                          VkBuffer *);
void VKAPI_CALL __real_vkDestroyBuffer(VkDevice, VkBuffer,
                                       const VkAllocationCallbacks *);
VkResult VKAPI_CALL __real_vkMapMemory(VkDevice, VkDeviceMemory, VkDeviceSize,
                                       VkDeviceSize, VkMemoryMapFlags, void **);
VkResult VKAPI_CALL __wrap_vkCreateImage(VkDevice d, const VkImageCreateInfo *i,
                                         const VkAllocationCallbacks *a,
                                         VkImage *o) {
  auto r = __real_vkCreateImage(d, i, a, o);
  if (r == VK_SUCCESS) {
    VulkanFailureTest::images.insert(*o);
    VulkanFailureTest::imageFlags[*o] = i->flags;
  }
  return r;
}
void VKAPI_CALL __wrap_vkDestroyImage(VkDevice d, VkImage o,
                                      const VkAllocationCallbacks *a) {
  VulkanFailureTest::images.erase(o);
  VulkanFailureTest::imageFlags.erase(o);
  __real_vkDestroyImage(d, o, a);
}
VkResult VKAPI_CALL __wrap_vkAllocateMemory(VkDevice d,
                                            const VkMemoryAllocateInfo *i,
                                            const VkAllocationCallbacks *a,
                                            VkDeviceMemory *o) {
  auto r = __real_vkAllocateMemory(d, i, a, o);
  if (r == VK_SUCCESS)
    VulkanFailureTest::memory.insert(*o);
  return r;
}
void VKAPI_CALL __wrap_vkFreeMemory(VkDevice d, VkDeviceMemory o,
                                    const VkAllocationCallbacks *a) {
  VulkanFailureTest::memory.erase(o);
  __real_vkFreeMemory(d, o, a);
}
VkResult VKAPI_CALL __wrap_vkCreateImageView(VkDevice d,
                                             const VkImageViewCreateInfo *i,
                                             const VkAllocationCallbacks *a,
                                             VkImageView *o) {
  if (VulkanFailureTest::point == VulkanFailureTest::Point::imageView) {
    VulkanFailureTest::point = VulkanFailureTest::Point::none;
    return VK_ERROR_OUT_OF_HOST_MEMORY;
  }
  return __real_vkCreateImageView(d, i, a, o);
}
VkResult VKAPI_CALL
__wrap_vkCreateShaderModule(VkDevice d, const VkShaderModuleCreateInfo *i,
                            const VkAllocationCallbacks *a, VkShaderModule *o) {
  if (VulkanFailureTest::point == VulkanFailureTest::Point::secondShader &&
      ++VulkanFailureTest::shaders == 2) {
    VulkanFailureTest::point = VulkanFailureTest::Point::none;
    return VK_ERROR_OUT_OF_HOST_MEMORY;
  }
  auto r = __real_vkCreateShaderModule(d, i, a, o);
  if (r == VK_SUCCESS)
    VulkanFailureTest::modules.insert(*o);
  return r;
}
void VKAPI_CALL __wrap_vkDestroyShaderModule(VkDevice d, VkShaderModule o,
                                             const VkAllocationCallbacks *a) {
  VulkanFailureTest::modules.erase(o);
  __real_vkDestroyShaderModule(d, o, a);
}
VkResult VKAPI_CALL __wrap_vkCreateBuffer(VkDevice d,
                                          const VkBufferCreateInfo *i,
                                          const VkAllocationCallbacks *a,
                                          VkBuffer *o) {
  auto r = __real_vkCreateBuffer(d, i, a, o);
  if (r == VK_SUCCESS) {
    VulkanFailureTest::buffers.insert(*o);
    if (i->usage & VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
      VulkanFailureTest::lastVertexBufferBytes = i->size;
  }
  return r;
}
void VKAPI_CALL __wrap_vkDestroyBuffer(VkDevice d, VkBuffer o,
                                       const VkAllocationCallbacks *a) {
  VulkanFailureTest::buffers.erase(o);
  __real_vkDestroyBuffer(d, o, a);
}
VkResult VKAPI_CALL __wrap_vkMapMemory(VkDevice d, VkDeviceMemory m,
                                       VkDeviceSize o, VkDeviceSize s,
                                       VkMemoryMapFlags f, void **p) {
  if (VulkanFailureTest::point == VulkanFailureTest::Point::mapMemory) {
    VulkanFailureTest::point = VulkanFailureTest::Point::none;
    return VK_ERROR_MEMORY_MAP_FAILED;
  }
  return __real_vkMapMemory(d, m, o, s, f, p);
}
}
