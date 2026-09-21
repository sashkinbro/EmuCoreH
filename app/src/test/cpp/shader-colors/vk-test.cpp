#include <cstdio>
#include <cstdlib>
#include <librashader.h>
#include <vector>
#include <vulkan/vulkan.h>
#define CHECK(x)                                                               \
  do {                                                                         \
    auto r = (x);                                                              \
    if (r != VK_SUCCESS) {                                                     \
      printf("Vulkan error %d line %d\n", r, __LINE__);                        \
      exit(2);                                                                 \
    }                                                                          \
  } while (0)
void libraCheck(libra_error_t e) {
  if (e) {
    char *msg = nullptr;
    libra_error_write(e, &msg);
    printf("%s\n", msg);
    exit(3);
  }
}
VkDevice device;
VkPhysicalDevice gpu;
VkCommandBuffer cmd;
uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags props) {
  VkPhysicalDeviceMemoryProperties m;
  vkGetPhysicalDeviceMemoryProperties(gpu, &m);
  for (uint32_t i = 0; i < m.memoryTypeCount; i++)
    if ((bits & (1u << i)) && (m.memoryTypes[i].propertyFlags & props) == props)
      return i;
  exit(4);
}
struct Image {
  VkImage image;
  VkDeviceMemory memory;
};
Image makeImage(VkFormat format) {
  Image out{};
  VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  ci.flags = VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
  ci.imageType = VK_IMAGE_TYPE_2D;
  ci.format = format;
  ci.extent = {8, 8, 1};
  ci.mipLevels = 1;
  ci.arrayLayers = 1;
  ci.samples = VK_SAMPLE_COUNT_1_BIT;
  ci.tiling = VK_IMAGE_TILING_OPTIMAL;
  ci.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
             VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
  CHECK(vkCreateImage(device, &ci, nullptr, &out.image));
  VkMemoryRequirements req;
  vkGetImageMemoryRequirements(device, out.image, &req);
  VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  ai.allocationSize = req.size;
  ai.memoryTypeIndex =
      memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  CHECK(vkAllocateMemory(device, &ai, nullptr, &out.memory));
  CHECK(vkBindImageMemory(device, out.image, out.memory, 0));
  return out;
}
void barrier(VkImage image, VkImageLayout old, VkImageLayout next,
             VkAccessFlags src, VkAccessFlags dst) {
  VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  b.oldLayout = old;
  b.newLayout = next;
  b.srcAccessMask = src;
  b.dstAccessMask = dst;
  b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  b.image = image;
  b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                       VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0,
                       nullptr, 1, &b);
}
int main(int argc, char **argv) {
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.apiVersion = VK_API_VERSION_1_1;
  VkInstanceCreateInfo ic{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  ic.pApplicationInfo = &app;
  VkInstance instance;
  CHECK(vkCreateInstance(&ic, nullptr, &instance));
  uint32_t n = 0;
  CHECK(vkEnumeratePhysicalDevices(instance, &n, nullptr));
  std::vector<VkPhysicalDevice> gpus(n);
  CHECK(vkEnumeratePhysicalDevices(instance, &n, gpus.data()));
  gpu = gpus[0];
  vkGetPhysicalDeviceQueueFamilyProperties(gpu, &n, nullptr);
  std::vector<VkQueueFamilyProperties> props(n);
  vkGetPhysicalDeviceQueueFamilyProperties(gpu, &n, props.data());
  uint32_t family = 0;
  while (!(props[family].queueFlags & VK_QUEUE_GRAPHICS_BIT))
    family++;
  float priority = 1;
  VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
  qi.queueFamilyIndex = family;
  qi.queueCount = 1;
  qi.pQueuePriorities = &priority;
  VkDeviceCreateInfo dc{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
  dc.queueCreateInfoCount = 1;
  dc.pQueueCreateInfos = &qi;
  CHECK(vkCreateDevice(gpu, &dc, nullptr, &device));
  VkQueue queue;
  vkGetDeviceQueue(device, family, 0, &queue);
  VkCommandPoolCreateInfo pc{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
  pc.queueFamilyIndex = family;
  pc.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
  VkCommandPool pool;
  CHECK(vkCreateCommandPool(device, &pc, nullptr, &pool));
  VkCommandBufferAllocateInfo ca{
      VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
  ca.commandPool = pool;
  ca.commandBufferCount = 1;
  ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  CHECK(vkAllocateCommandBuffers(device, &ca, &cmd));
  auto input = makeImage(VK_FORMAT_B8G8R8A8_UNORM);
  auto output = makeImage(VK_FORMAT_R8G8B8A8_UNORM);
  VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
  bc.size = 256;
  bc.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
  VkBuffer buffer;
  CHECK(vkCreateBuffer(device, &bc, nullptr, &buffer));
  VkMemoryRequirements req;
  vkGetBufferMemoryRequirements(device, buffer, &req);
  VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  ma.allocationSize = req.size;
  ma.memoryTypeIndex =
      memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                         VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
  VkDeviceMemory memory;
  CHECK(vkAllocateMemory(device, &ma, nullptr, &memory));
  CHECK(vkBindBufferMemory(device, buffer, memory, 0));
  libra_shader_preset_t preset = nullptr;
  libraCheck(libra_preset_create(argv[1], &preset));
  libra_device_vk_t dev{gpu, instance, device, queue, &vkGetInstanceProcAddr};
  libra_vk_filter_chain_t chain = nullptr;
  libraCheck(libra_vk_filter_chain_create(&preset, dev, nullptr, &chain));
  bool passed = true;
  for (int i = 0; i < 2; i++) {
    CHECK(vkResetCommandBuffer(cmd, 0));
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    CHECK(vkBeginCommandBuffer(cmd, &begin));
    barrier(input.image, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
            VK_ACCESS_TRANSFER_WRITE_BIT);
    VkClearColorValue color{{0.8f, 0.4f, 0.2f, 1.f}};
    VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdClearColorImage(cmd, input.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                         &color, 1, &range);
    barrier(input.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
    barrier(output.image, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0,
            VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    libra_image_vk_t in{
        input.image,
        i == 0 ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_B8G8R8A8_UNORM, 8, 8};
    libra_image_vk_t out{output.image, VK_FORMAT_R8G8B8A8_UNORM, 8, 8};
    libraCheck(libra_vk_filter_chain_frame(&chain, cmd, i, in, out, nullptr,
                                           nullptr, nullptr));
    barrier(output.image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    VkBufferImageCopy copy{};
    copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.imageExtent = {8, 8, 1};
    vkCmdCopyImageToBuffer(cmd, output.image,
                           VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1,
                           &copy);
    CHECK(vkEndCommandBuffer(cmd));
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    CHECK(vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE));
    CHECK(vkQueueWaitIdle(queue));
    void *data;
    CHECK(vkMapMemory(device, memory, 0, VK_WHOLE_SIZE, 0, &data));
    auto pixel = static_cast<unsigned char *>(data) + 4 * (4 * 8 + 4);
    printf("%s RGB=%d,%d,%d,%d\n", i == 0 ? "old RGBA" : "actual BGRA",
           pixel[0], pixel[1], pixel[2], pixel[3]);
    passed &= abs(int(pixel[0]) - (i == 0 ? 51 : 204)) <= 2 &&
              abs(int(pixel[1]) - 102) <= 2 &&
              abs(int(pixel[2]) - (i == 0 ? 204 : 51)) <= 2;
    vkUnmapMemory(device, memory);
  }
  libra_vk_filter_chain_free(&chain);
  vkDestroyBuffer(device, buffer, nullptr);
  vkFreeMemory(device, memory, nullptr);
  for (auto image : {input, output}) {
    vkDestroyImage(device, image.image, nullptr);
    vkFreeMemory(device, image.memory, nullptr);
  }
  vkDestroyCommandPool(device, pool, nullptr);
  vkDestroyDevice(device, nullptr);
  vkDestroyInstance(instance, nullptr);
  return passed ? 0 : 5;
}
