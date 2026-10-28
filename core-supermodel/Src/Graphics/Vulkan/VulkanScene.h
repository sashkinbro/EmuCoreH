// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "Graphics/New3D/SceneFrame.h"
#include <functional>
#include <memory>
#include <vulkan/vulkan.h>
namespace New3D {
struct VulkanContext {
  VkPhysicalDevice gpu = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;
  VkQueue queue = VK_NULL_HANDLE;
  uint32_t queueFamily = 0;
  std::function<void()> lockQueue, unlockQueue;
  VkInstance instance = VK_NULL_HANDLE;
  PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
  PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;
};
// Experimental Real3D mesh consumer. Until per-sync-index image slots are
// added, queue-idle at frame start protects the shared output from frontend
// reads.
class VulkanScene final : public SceneConsumer {
public:
  explicit VulkanScene(VulkanContext context);
  ~VulkanScene() override;
  std::array<float, 4> Render(const SceneFrame &frame) override;
  void InvalidateTextures(unsigned, unsigned, unsigned, unsigned,
                          unsigned) override;
  void SetTileLayers(const UINT32 *bottom, const UINT32 *top,
                     unsigned width = 496, unsigned height = 384);
  VkImage Image() const;
  VkImageView View() const;
  unsigned Width() const;
  unsigned Height() const;
  std::vector<UINT8> Readback();

private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
} // namespace New3D
