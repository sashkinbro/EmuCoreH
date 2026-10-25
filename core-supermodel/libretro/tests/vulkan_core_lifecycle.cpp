// SPDX-License-Identifier: GPL-3.0-or-later
// Offscreen real-content Vulkan/libretro probe; saves only temporary test
// state.
#include "libretro.h"
#include "libretro_vulkan.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>
static void check(bool ok, const char *message) {
  if (!ok) {
    std::fprintf(stderr, "FAIL: %s\n", message ? message : "unknown");
    std::exit(1);
  }
}
static std::string systemDir, saveDir, option = "1x";
static retro_hw_render_callback hardware{};
static retro_hw_render_interface_vulkan iface{};
static retro_vulkan_image image{};
static unsigned frames = 0, images = 0;
static size_t pcm = 0, nonzero = 0;
static bool changed = false;
static unsigned validationErrors=0;
static VKAPI_ATTR VkBool32 VKAPI_CALL validationMessage(VkDebugUtilsMessageSeverityFlagBitsEXT severity,VkDebugUtilsMessageTypeFlagsEXT,const VkDebugUtilsMessengerCallbackDataEXT* data,void*){
  if(severity&VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)++validationErrors;
  std::fprintf(stderr,"VALIDATION: %s\n",data->pMessage);return VK_FALSE;
}
static void setImage(void *, const retro_vulkan_image *value, uint32_t sems,
                     const VkSemaphore *, uint32_t family) {
  check(sems == 0, "same-queue submission protocol");
  check(family == VK_QUEUE_FAMILY_IGNORED || family == iface.queue_index,
        "shared graphics queue family");
  check(value &&
            value->image_layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        "sample-ready image");
  image = *value;
  ++images;
}
static void noop(void *) {}
static bool environment(unsigned command, void *data) {
  switch (command) {
  case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    *static_cast<const char **>(data) = systemDir.c_str();
    return true;
  case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
    *static_cast<const char **>(data) = saveDir.c_str();
    return true;
  case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
    *static_cast<unsigned *>(data) = RETRO_HW_CONTEXT_VULKAN;
    return true;
  case RETRO_ENVIRONMENT_SET_HW_RENDER: {
    auto *cb = static_cast<retro_hw_render_callback *>(data);
    check(cb->context_type == RETRO_HW_CONTEXT_VULKAN,
          "core negotiates requested Vulkan");
    hardware = *cb;
    return true;
  }
  case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
    *static_cast<const retro_hw_render_interface **>(data) =
        reinterpret_cast<const retro_hw_render_interface *>(&iface);
    return true;
  case RETRO_ENVIRONMENT_GET_VARIABLE: {
    auto *var = static_cast<retro_variable *>(data);
    if (std::strcmp(var->key, "supermodel_internal_resolution"))
      return false;
    var->value = option.c_str();
    return true;
  }
  case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
    *static_cast<bool *>(data) = changed;
    changed = false;
    return true;
  case RETRO_ENVIRONMENT_SET_GEOMETRY:
    return true;
  case RETRO_ENVIRONMENT_SHUTDOWN:
    check(false, "core shutdown");
    return true;
  default:
    return false;
  }
}
static void video(const void *p, unsigned, unsigned, size_t) {
  if (p) {
    check(p == RETRO_HW_FRAME_BUFFER_VALID, "hardware frame");
    ++frames;
  }
}
static size_t audio(const int16_t *data, size_t count) {
  pcm += count;
  for (size_t i = 0; i < count * 2; ++i)
    nonzero += data[i] != 0;
  return count;
}
static int16_t input(unsigned, unsigned, unsigned, unsigned) { return 0; }
template <class T> T symbol(void *lib, const char *name) {
  auto fn = reinterpret_cast<T>(dlsym(lib, name));
  check(fn != nullptr, name);
  return fn;
}
static void dump(const char *path, unsigned width, unsigned height) {
  vkQueueWaitIdle(iface.queue);
  VkBuffer buffer{};
  VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
  bi.size = size_t(width) * height * 4;
  bi.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
  check(vkCreateBuffer(iface.device, &bi, nullptr, &buffer) == VK_SUCCESS,
        "readback buffer");
  VkMemoryRequirements req{};
  vkGetBufferMemoryRequirements(iface.device, buffer, &req);
  VkPhysicalDeviceMemoryProperties props{};
  vkGetPhysicalDeviceMemoryProperties(iface.gpu, &props);
  unsigned type = 0;
  while (type < props.memoryTypeCount &&
         (!(req.memoryTypeBits & (1u << type)) ||
          (props.memoryTypes[type].propertyFlags &
           (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) !=
              (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
               VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)))
    ++type;
  check(type < props.memoryTypeCount, "readback memory type");
  VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  ai.allocationSize = req.size;
  ai.memoryTypeIndex = type;
  VkDeviceMemory memory{};
  check(vkAllocateMemory(iface.device, &ai, nullptr, &memory) == VK_SUCCESS,
        "readback memory");
  vkBindBufferMemory(iface.device, buffer, memory, 0);
  VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
  pi.queueFamilyIndex = iface.queue_index;
  VkCommandPool pool{};
  vkCreateCommandPool(iface.device, &pi, nullptr, &pool);
  VkCommandBufferAllocateInfo ca{
      VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
  ca.commandPool = pool;
  ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  ca.commandBufferCount = 1;
  VkCommandBuffer cmd{};
  vkAllocateCommandBuffers(iface.device, &ca, &cmd);
  VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
  vkBeginCommandBuffer(cmd, &begin);
  VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  barrier.oldLayout = image.image_layout;
  barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
  barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
  barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
  barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex =
      VK_QUEUE_FAMILY_IGNORED;
  barrier.image = image.create_info.image;
  barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                       nullptr, 1, &barrier);
  VkBufferImageCopy copy{};
  copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  copy.imageExtent = {width, height, 1};
  vkCmdCopyImageToBuffer(cmd, image.create_info.image,
                         VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1,
                         &copy);
  VkMemoryBarrier hostRead{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
  hostRead.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
  hostRead.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
  vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&hostRead,0,nullptr,0,nullptr);
  std::swap(barrier.oldLayout, barrier.newLayout);
  std::swap(barrier.srcAccessMask, barrier.dstAccessMask);
  vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                       VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0,
                       nullptr, 1, &barrier);
  vkEndCommandBuffer(cmd);
  VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
  si.commandBufferCount = 1;
  si.pCommandBuffers = &cmd;
  check(vkQueueSubmit(iface.queue, 1, &si, VK_NULL_HANDLE) == VK_SUCCESS,
        "readback submit");
  vkQueueWaitIdle(iface.queue);
  void *pixels{};
  vkMapMemory(iface.device, memory, 0, VK_WHOLE_SIZE, 0, &pixels);
  auto *bytes = static_cast<unsigned char *>(pixels);
  FILE *file = std::fopen(path, "wb");
  check(file != nullptr, "screenshot path");
  std::fprintf(file, "P6\n%u %u\n255\n", width, height);
  size_t lit = 0;
  for (size_t i = 0; i < size_t(width) * height; ++i) {
    std::fwrite(bytes + i * 4, 1, 3, file);
    lit += bytes[i * 4] || bytes[i * 4 + 1] || bytes[i * 4 + 2];
  }
  std::fclose(file);
  std::printf("FRAME %u %ux%u nonblack=%zu PCM=%zu nonzero=%zu\n", frames,
              width, height, lit, pcm, nonzero);
  vkUnmapMemory(iface.device, memory);
  vkDestroyCommandPool(iface.device, pool, nullptr);
  vkDestroyBuffer(iface.device, buffer, nullptr);
  vkFreeMemory(iface.device, memory, nullptr);
}
int main(int argc, char **argv) {
  check(argc >= 6, "usage: vulkan_core_lifecycle core.so system owner.zip "
                   "temporary-save screenshot-prefix [frames]");
  systemDir = argv[2];
  saveDir = argv[4];
  std::filesystem::create_directories(saveDir);
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.apiVersion = VK_API_VERSION_1_0;
  VkInstanceCreateInfo ii{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  ii.pApplicationInfo = &app;
  const bool validation=std::getenv("MODEL3_VULKAN_VALIDATION")!=nullptr;
  const char* layer="VK_LAYER_KHRONOS_validation";
  const char* extension=VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
  VkDebugUtilsMessengerCreateInfoEXT debug{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
  if(validation){
    uint32_t layerCount=0;vkEnumerateInstanceLayerProperties(&layerCount,nullptr);
    std::vector<VkLayerProperties> layers(layerCount);vkEnumerateInstanceLayerProperties(&layerCount,layers.data());
    bool found=false;for(const auto& value:layers)found|=!std::strcmp(value.layerName,layer);
    check(found,"requested Khronos validation layer is discoverable");
    ii.enabledLayerCount=1;ii.ppEnabledLayerNames=&layer;ii.enabledExtensionCount=1;ii.ppEnabledExtensionNames=&extension;
    debug.messageSeverity=VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
    debug.messageType=VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
    debug.pfnUserCallback=validationMessage;ii.pNext=&debug;
  }
  check(vkCreateInstance(&ii, nullptr, &iface.instance) == VK_SUCCESS,
        "instance");
  VkDebugUtilsMessengerEXT messenger{};
  if(validation){
    auto create=reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(vkGetInstanceProcAddr(iface.instance,"vkCreateDebugUtilsMessengerEXT"));
    check(create&&create(iface.instance,&debug,nullptr,&messenger)==VK_SUCCESS,"validation callback installed");
  }
  uint32_t count = 0;
  vkEnumeratePhysicalDevices(iface.instance, &count, nullptr);
  check(count > 0, "GPU");
  std::vector<VkPhysicalDevice> gpus(count);
  vkEnumeratePhysicalDevices(iface.instance, &count, gpus.data());
  iface.gpu = gpus[0];
  uint32_t nf = 0;
  vkGetPhysicalDeviceQueueFamilyProperties(iface.gpu, &nf, nullptr);
  std::vector<VkQueueFamilyProperties> families(nf);
  vkGetPhysicalDeviceQueueFamilyProperties(iface.gpu, &nf, families.data());
  while (iface.queue_index < nf &&
         !(families[iface.queue_index].queueFlags & VK_QUEUE_GRAPHICS_BIT))
    ++iface.queue_index;
  check(iface.queue_index < nf, "graphics family");
  float priority = 1;
  VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
  qi.queueFamilyIndex = iface.queue_index;
  qi.queueCount = 1;
  qi.pQueuePriorities = &priority;
  VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
  di.queueCreateInfoCount = 1;
  di.pQueueCreateInfos = &qi;
  check(vkCreateDevice(iface.gpu, &di, nullptr, &iface.device) == VK_SUCCESS,
        "device");
  vkGetDeviceQueue(iface.device, iface.queue_index, 0, &iface.queue);
  iface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
  iface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
  iface.set_image = setImage;
  iface.lock_queue = noop;
  iface.unlock_queue = noop;
  iface.wait_sync_index = noop;
  void *lib = dlopen(argv[1], RTLD_NOW);
  check(lib != nullptr, dlerror());
  symbol<void (*)(retro_environment_t)>(lib,
                                        "retro_set_environment")(environment);
  symbol<void (*)(retro_video_refresh_t)>(lib,
                                          "retro_set_video_refresh")(video);
  symbol<void (*)(retro_audio_sample_batch_t)>(
      lib, "retro_set_audio_sample_batch")(audio);
  symbol<void (*)(retro_input_state_t)>(lib, "retro_set_input_state")(input);
  auto run = symbol<void (*)()>(lib, "retro_run");
  symbol<void (*)()>(lib, "retro_init")();
  retro_game_info game{};
  game.path = argv[3];
  check(
      symbol<bool (*)(const retro_game_info *)>(lib, "retro_load_game")(&game),
      symbol<const char *(*)()>(lib, "supermodel_boot_error")());
  hardware.context_reset();
  if (argc > 7) {
    std::ifstream inputState(argv[7], std::ios::binary | std::ios::ate);
    check(bool(inputState), "input CPU state file");
    const auto size = inputState.tellg();
    check(size > 0 && size < 512 * 1024 * 1024, "input CPU state size");
    std::vector<unsigned char> bytes(static_cast<size_t>(size));
    inputState.seekg(0);
    check(bool(inputState.read(reinterpret_cast<char *>(bytes.data()), size)),
          "read input CPU state");
    check(symbol<bool (*)(const void *, size_t)>(lib, "retro_unserialize")(
              bytes.data(), bytes.size()),
          "restore input CPU state");
  }
  unsigned total = argc > 6 ? unsigned(std::atoi(argv[6])) : 600;
  for (unsigned i = 0; i < total; ++i) {
    run();
    if ((i + 1) % 120 == 0 || i + 1 == total)
      dump(
          (std::string(argv[5]) + "-" + std::to_string(i + 1) + ".ppm").c_str(),
          496, 384);
  }
  check(frames == total && images == total && pcm > 0, "retail frames and PCM");
  check(nonzero > 0, "retail audio is nonzero");
  auto stateSize = symbol<size_t (*)()>(lib, "retro_serialize_size")();
  std::vector<unsigned char> state(stateSize);
  check(stateSize && symbol<bool (*)(void *, size_t)>(lib, "retro_serialize")(
                         state.data(), state.size()),
        "Vulkan save");
  const auto statePath =
      std::filesystem::path(saveDir) / "vulkan-baseline.rstate";
  FILE *stateFile = std::fopen(statePath.string().c_str(), "wb");
  check(stateFile && std::fwrite(state.data(), 1, state.size(), stateFile) ==
                         state.size(),
        "export baseline CPU state");
  std::fclose(stateFile);
  // Reset host renderer/LOS history before producing the reference successor.
  // A fresh GLES renderer can load this exact state and run one frame to
  // compare.
  hardware.context_destroy();
  hardware.context_reset();
  check(symbol<bool (*)(const void *, size_t)>(lib, "retro_unserialize")(
            state.data(), state.size()),
        "restore baseline with fresh renderer");
  run();
  dump((std::string(argv[5]) + "-state-next.ppm").c_str(), 496, 384);
  std::printf("STATE %s bytes=%zu successor captured with fresh renderer\n",
              statePath.string().c_str(), state.size());
  check(symbol<bool (*)(const void *, size_t)>(lib, "retro_unserialize")(
            state.data(), state.size()),
        "restore baseline before resize matrix");
  for (const char *value : {"2x", "4x", "1x"}) {
    option = value;
    changed = true;
    run();
    run();
    unsigned scale = value[0] - '0';
    dump((std::string(argv[5]) + "-" + value + ".ppm").c_str(), 496 * scale,
         384 * scale);
    check(symbol<bool (*)(const void *, size_t)>(lib, "retro_unserialize")(
              state.data(), state.size()),
          "state across Vulkan resize");
  }
  hardware.context_destroy();
  hardware.context_reset();
  run();
  hardware.context_destroy();
  symbol<void (*)()>(lib, "retro_unload_game")();
  symbol<void (*)()>(lib, "retro_deinit")();
  dlclose(lib);
  vkDestroyDevice(iface.device, nullptr);
  if(validation){
    auto destroy=reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(vkGetInstanceProcAddr(iface.instance,"vkDestroyDebugUtilsMessengerEXT"));
    destroy(iface.instance,messenger,nullptr);
    check(validationErrors==0,"retail adapter Vulkan validation reports no errors");
    std::puts("PASS: retail adapter Khronos Vulkan validation enabled, zero errors");
  }
  vkDestroyInstance(iface.instance, nullptr);
  std::puts("PASS: Model3 Vulkan retail frames, PCM, resize, state and context "
            "recreation");
  return 0;
}
