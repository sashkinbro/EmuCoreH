// SPDX-License-Identifier: GPL-3.0-or-later
// Observe real Vulkan command recording; all calls still execute on the GPU.
#pragma once
#include <cstdio>
#include <cstdlib>
#include <unordered_map>
#include <vulkan/vulkan.h>
namespace {
struct HostCopyState {
  bool copied = false, visible = false;
};
std::unordered_map<VkCommandBuffer, HostCopyState> hostCopies;
} // namespace
extern "C" {
VkResult VKAPI_CALL
__real_vkBeginCommandBuffer(VkCommandBuffer, const VkCommandBufferBeginInfo *);
void VKAPI_CALL __real_vkCmdCopyImageToBuffer(VkCommandBuffer, VkImage,
                                              VkImageLayout, VkBuffer, uint32_t,
                                              const VkBufferImageCopy *);
void VKAPI_CALL __real_vkCmdPipelineBarrier(
    VkCommandBuffer, VkPipelineStageFlags, VkPipelineStageFlags,
    VkDependencyFlags, uint32_t, const VkMemoryBarrier *, uint32_t,
    const VkBufferMemoryBarrier *, uint32_t, const VkImageMemoryBarrier *);
VkResult VKAPI_CALL __real_vkQueueSubmit(VkQueue, uint32_t,
                                         const VkSubmitInfo *, VkFence);
VkResult VKAPI_CALL __wrap_vkBeginCommandBuffer(
    VkCommandBuffer command, const VkCommandBufferBeginInfo *info) {
  hostCopies[command] = {};
  return __real_vkBeginCommandBuffer(command, info);
}
void VKAPI_CALL __wrap_vkCmdCopyImageToBuffer(
    VkCommandBuffer command, VkImage image, VkImageLayout layout,
    VkBuffer buffer, uint32_t count, const VkBufferImageCopy *regions) {
  hostCopies[command].copied = true;
  hostCopies[command].visible = false;
  __real_vkCmdCopyImageToBuffer(command, image, layout, buffer, count, regions);
}
void VKAPI_CALL __wrap_vkCmdPipelineBarrier(
    VkCommandBuffer command, VkPipelineStageFlags source,
    VkPipelineStageFlags dest, VkDependencyFlags dependencies,
    uint32_t memoryCount, const VkMemoryBarrier *memory, uint32_t bufferCount,
    const VkBufferMemoryBarrier *buffers, uint32_t imageCount,
    const VkImageMemoryBarrier *images) {
  if ((source & VK_PIPELINE_STAGE_TRANSFER_BIT) &&
      (dest & VK_PIPELINE_STAGE_HOST_BIT)) {
    for (uint32_t i = 0; i < memoryCount; ++i)
      if ((memory[i].srcAccessMask & VK_ACCESS_TRANSFER_WRITE_BIT) &&
          (memory[i].dstAccessMask & VK_ACCESS_HOST_READ_BIT))
        hostCopies[command].visible = true;
    for (uint32_t i = 0; i < bufferCount; ++i)
      if ((buffers[i].srcAccessMask & VK_ACCESS_TRANSFER_WRITE_BIT) &&
          (buffers[i].dstAccessMask & VK_ACCESS_HOST_READ_BIT))
        hostCopies[command].visible = true;
  }
  __real_vkCmdPipelineBarrier(command, source, dest, dependencies, memoryCount,
                              memory, bufferCount, buffers, imageCount, images);
}
VkResult VKAPI_CALL __wrap_vkQueueSubmit(VkQueue queue, uint32_t count,
                                         const VkSubmitInfo *submits,
                                         VkFence fence) {
  for (uint32_t i = 0; i < count; ++i)
    for (uint32_t j = 0; j < submits[i].commandBufferCount; ++j) {
      const auto state = hostCopies[submits[i].pCommandBuffers[j]];
      if (state.copied && !state.visible) {
        std::fprintf(stderr, "FAIL: GPU host readback requires TRANSFER_WRITE "
                             "to HOST_READ visibility before submission\n");
        std::exit(1);
      }
    }
  return __real_vkQueueSubmit(queue, count, submits, fence);
}
}
