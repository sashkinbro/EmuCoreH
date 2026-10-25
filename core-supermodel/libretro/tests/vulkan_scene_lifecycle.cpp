// SPDX-License-Identifier: GPL-3.0-or-later
#include "Graphics/New3D/SceneSnapshot.h"
#include "Graphics/Vulkan/VulkanScene.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#ifdef MODEL3_TEST_HOST_VISIBILITY
#include "vulkan_host_visibility.h"
#endif
#ifdef MODEL3_TEST_RESOURCE_FAILURES
#include "vulkan_resource_failures.h"
#endif
static void check(bool value, const char *message) {
  if (!value) {
    std::fprintf(stderr, "FAIL: %s\n", message);
    std::exit(1);
  }
}
static unsigned validationErrors = 0;
static VKAPI_ATTR VkBool32 VKAPI_CALL
validationMessage(VkDebugUtilsMessageSeverityFlagBitsEXT severity,
                  VkDebugUtilsMessageTypeFlagsEXT,
                  const VkDebugUtilsMessengerCallbackDataEXT *data, void *) {
  if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
    ++validationErrors;
  std::fprintf(stderr, "VALIDATION: %s\n", data->pMessage);
  return VK_FALSE;
}
int main(int argc, char **argv) {
  const bool validation = argc > 1 && !std::strcmp(argv[1], "--validation");
  const char *layer = "VK_LAYER_KHRONOS_validation";
  const char *extension = VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.apiVersion = VK_API_VERSION_1_0;
  VkInstanceCreateInfo instanceInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  instanceInfo.pApplicationInfo = &app;
  VkDebugUtilsMessengerCreateInfoEXT debug{
      VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
  if (validation) {
    uint32_t count = 0;
    vkEnumerateInstanceLayerProperties(&count, nullptr);
    std::vector<VkLayerProperties> layers(count);
    vkEnumerateInstanceLayerProperties(&count, layers.data());
    bool found = false;
    for (const auto &item : layers)
      found |= !std::strcmp(item.layerName, layer);
    check(found, "requested Khronos validation layer is discoverable");
    instanceInfo.enabledLayerCount = 1;
    instanceInfo.ppEnabledLayerNames = &layer;
    instanceInfo.enabledExtensionCount = 1;
    instanceInfo.ppEnabledExtensionNames = &extension;
    debug.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT |
                            VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
    debug.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
    debug.pfnUserCallback = validationMessage;
    instanceInfo.pNext = &debug;
  }
  VkInstance instance{};
  check(vkCreateInstance(&instanceInfo, nullptr, &instance) == VK_SUCCESS,
        "instance");
  VkDebugUtilsMessengerEXT messenger{};
  if (validation) {
    auto create = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
        vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT"));
    check(create && create(instance, &debug, nullptr, &messenger) == VK_SUCCESS,
          "validation callback installed");
  }
  uint32_t count = 0;
  vkEnumeratePhysicalDevices(instance, &count, nullptr);
  check(count > 0, "GPU");
  std::vector<VkPhysicalDevice> gpus(count);
  vkEnumeratePhysicalDevices(instance, &count, gpus.data());
  uint32_t familyCount = 0;
  vkGetPhysicalDeviceQueueFamilyProperties(gpus[0], &familyCount, nullptr);
  std::vector<VkQueueFamilyProperties> families(familyCount);
  vkGetPhysicalDeviceQueueFamilyProperties(gpus[0], &familyCount,
                                           families.data());
  uint32_t family = 0;
  while (family < familyCount &&
         !(families[family].queueFlags & VK_QUEUE_GRAPHICS_BIT))
    ++family;
  check(family < familyCount, "graphics queue");
  float priority = 1;
  VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
  qi.queueFamilyIndex = family;
  qi.queueCount = 1;
  qi.pQueuePriorities = &priority;
  VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
  di.queueCreateInfoCount = 1;
  di.pQueueCreateInfos = &qi;
  VkDevice device{};
  check(vkCreateDevice(gpus[0], &di, nullptr, &device) == VK_SUCCESS, "device");
  VkQueue queue{};
  vkGetDeviceQueue(device, family, 0, &queue);
#ifdef MODEL3_TEST_RESOURCE_FAILURES
  {
    bool failed = false;
    VulkanFailureTest::Inject(VulkanFailureTest::Point::imageView);
    {
      New3D::VulkanScene renderer({gpus[0], device, queue, family});
      std::vector<New3D::Node> nodes;
      std::vector<New3D::FVertex> ram, rom;
      New3D::SceneFrame scene{nodes, ram, rom, nullptr, 0, 16, 16};
      try {
        renderer.Render(scene);
      } catch (const std::exception &) {
        failed = true;
      }
    }
    check(failed && VulkanFailureTest::Empty(),
          "partial image allocation is released after failed image-view "
          "creation");
  }
  {
    bool failed = false;
    VulkanFailureTest::Inject(VulkanFailureTest::Point::secondShader);
    {
      New3D::VulkanScene renderer({gpus[0], device, queue, family});
      std::vector<New3D::Node> nodes;
      std::vector<New3D::FVertex> ram, rom;
      New3D::SceneFrame scene{nodes, ram, rom, nullptr, 0, 16, 16};
      try {
        renderer.Render(scene);
      } catch (const std::exception &) {
        failed = true;
      }
    }
    check(failed && VulkanFailureTest::Empty(),
          "partial shader creation releases earlier shader module");
  }
  {
    bool failed = false;
    {
      New3D::VulkanScene renderer({gpus[0], device, queue, family});
      std::vector<New3D::Node> nodes;
      std::vector<New3D::FVertex> ram, rom;
      New3D::SceneFrame scene{nodes, ram, rom, nullptr, 0, 16, 16};
      renderer.Render(scene);
      check(VulkanFailureTest::lastVertexBufferBytes >= 3 * sizeof(New3D::FVertex),
            "empty scenes reserve inputs for all three fullscreen vertices");
      check((VulkanFailureTest::imageFlags.at(renderer.Image()) &
             VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT) != 0,
            "exported libretro image permits frontend sRGB reinterpretation");
      VkImageViewCreateInfo srgb{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
      srgb.image = renderer.Image();
      srgb.viewType = VK_IMAGE_VIEW_TYPE_2D;
      srgb.format = VK_FORMAT_R8G8B8A8_SRGB;
      srgb.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      VkImageView srgbView{};
      check(vkCreateImageView(device, &srgb, nullptr, &srgbView) == VK_SUCCESS,
            "frontend can create a real sRGB view of exported UNORM image");
      vkDestroyImageView(device, srgbView, nullptr);
      VulkanFailureTest::Inject(VulkanFailureTest::Point::mapMemory);
      try {
        renderer.Readback();
      } catch (const std::exception &) {
        failed = true;
      }
    }
    check(failed && VulkanFailureTest::Empty(),
          "readback mapping failure releases local buffer and allocation");
  }
#endif
  {
    New3D::VulkanScene renderer({gpus[0], device, queue, family});
    if (argc > 3 && !std::strcmp(argv[1], "--replay")) {
      std::ifstream input(argv[2], std::ios::binary);
      New3D::OwnedSceneSnapshot snapshot;
      check(New3D::ReadSceneSnapshot(input, snapshot),
            "read actual CPU scene snapshot");
      const std::string filter = argc > 4 ? argv[4] : "";
      for (auto &node : snapshot.nodes) {
        if (filter == "no-fog")
          node.viewport.scrollFog = 0;
        size_t modelIndex = 0;
        for (auto &model : node.models) {
          if (model.meshes)
            for (auto &mesh : *model.meshes) {
              if (filter == "no-poly-alpha" && mesh.polyAlpha)
                mesh.vertexCount = 0;
              if (filter == "no-overlay" && mesh.highPriority)
                mesh.vertexCount = 0;
              if (filter == "no-layered")
                mesh.layered = false;
              if (filter == "no-fog")
                mesh.fogIntensity = 0;
              if (filter.rfind("keep-alpha-", 0) == 0 && mesh.polyAlpha &&
                  modelIndex != size_t(std::stoul(filter.substr(11))))
                mesh.vertexCount = 0;
            }
          ++modelIndex;
        }
      }
      renderer.Render(snapshot.Frame());
      auto rgba = renderer.Readback();
      FILE *output = std::fopen(argv[3], "wb");
      check(output != nullptr, "replay output");
      std::fprintf(output, "P6\n%u %u\n255\n", snapshot.width, snapshot.height);
      for (size_t i = 0; i < rgba.size(); i += 4)
        std::fwrite(rgba.data() + i, 1, 3, output);
      std::fclose(output);
      std::printf("PASS: actual CPU scene replay nodes=%zu RAM=%zu ROM=%zu\n",
                  snapshot.nodes.size(), snapshot.ram.size(),
                  snapshot.rom.size());
    } else {
      std::vector<New3D::Node> nodes(1);
      nodes.reserve(2);
      auto &node = nodes[0];
      node.viewport = {};
      node.viewport.width = 64;
      node.viewport.height = 64;
      node.viewport.projectionMatrix.LoadIdentity();
      node.viewport.spotEllipse[2] = 1;
      node.viewport.spotEllipse[3] = 1;
      node.viewport.fogParams[6] = 1;
      New3D::Model model;
      std::memset(model.modelMat, 0, sizeof(model.modelMat));
      for (int i = 0; i < 4; ++i)
        model.modelMat[i * 5] = 1;
      model.meshes = std::make_shared<std::vector<New3D::Mesh>>(1);
      auto &mesh = (*model.meshes)[0];
      mesh.vboOffset = 0;
      mesh.vertexCount = 3;
      node.models.push_back(model);
      std::vector<New3D::FVertex> vertices(3), rom;
      const float xy[3][2] = {{-1, -1}, {1, -1}, {0, 1}};
      for (unsigned i = 0; i < 3; ++i) {
        auto &v = vertices[i];
        std::memset(&v, 0, sizeof(v));
        v.pos[0] = xy[i][0];
        v.pos[1] = xy[i][1];
        v.pos[2] = 0;
        v.pos[3] = 1;
        v.faceColour[0] = 255;
        v.faceColour[3] = 255;
      }
      New3D::SceneFrame scene{nodes, vertices, rom, nullptr, 0, 64, 64};
      renderer.Render(scene);
      check(renderer.Image() != VK_NULL_HANDLE &&
                renderer.View() != VK_NULL_HANDLE,
            "real Vulkan target");
      auto pixels = renderer.Readback();
      check(pixels.size() == 64 * 64 * 4, "readback size");
      const size_t center = (32 * 64 + 32) * 4;
      check(pixels[center] > 240 && pixels[center + 1] < 5 &&
                pixels[center + 2] < 5,
            "Real3D red mesh rasterized");
      vertices[0].faceColour[0] = vertices[1].faceColour[0] =
          vertices[2].faceColour[0] = 0;
      vertices[0].faceColour[1] = vertices[1].faceColour[1] =
          vertices[2].faceColour[1] = 255;
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center + 1] > 240 && pixels[center] < 5,
            "dynamic vertex update");
      vertices.resize(6);
      for (unsigned i = 0; i < 3; ++i) {
        vertices[i + 3] = vertices[i];
        vertices[i + 3].pos[2] = .5f;
        vertices[i + 3].faceColour[0] = 255;
        vertices[i + 3].faceColour[1] = 0;
        vertices[i + 3].faceColour[3] = 128;
      }
      New3D::Model behind = model;
      behind.meshes = std::make_shared<std::vector<New3D::Mesh>>(*model.meshes);
      behind.meshes->front().polyAlpha = true;
      behind.meshes->front().vboOffset = 3;
      node.models.push_back(behind);
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center + 1] > 240 && pixels[center] < 5,
            "opaque geometry occludes later translucent triangle");
      node.models.pop_back();
      // Both Real3D transparency planes contribute independently; sequential
      // alpha blending would incorrectly attenuate the first plane to roughly
      // 64 red.
      vertices.resize(6);
      for (unsigned i = 0; i < 3; ++i) {
        vertices[i].faceColour[0] = 255;
        vertices[i].faceColour[1] = 0;
        vertices[i].faceColour[3] = 128;
        vertices[i + 3] = vertices[i];
        vertices[i + 3].faceColour[0] = 0;
        vertices[i + 3].faceColour[2] = 255;
      }
      mesh.polyAlpha = true;
      model.meshes->push_back(mesh);
      (*model.meshes)[1].vboOffset = 3;
      (*model.meshes)[1].transLSelect = true;
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center] >= 125 && pixels[center] <= 131 &&
                pixels[center + 2] >= 125 && pixels[center + 2] <= 131,
            "independent weighted transparency planes");
      model.meshes->resize(1);
      auto &material = (*model.meshes)[0];
      material.polyAlpha = false;
      for (unsigned i = 0; i < 3; ++i) {
        vertices[i].faceColour[0] = vertices[i].faceColour[1] =
            vertices[i].faceColour[2] = vertices[i].faceColour[3] = 255;
        vertices[i].texcoords[0] = vertices[i].texcoords[1] = .5f;
      }
      std::vector<UINT16> texels(2048 * 2048, 0x03e0);
      scene.textureRAM = texels.data();
      material.textured = true;
      material.format = 0;
      material.width = material.height = 32;
      material.x = material.y = 0;
      material.wrapModeU = material.wrapModeV = New3D::Mesh::repeat;
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center + 1] > 240 && pixels[center] < 5,
            "decoded Real3D texture sampled");
      std::fill(texels.begin(), texels.end(), 0x7c00);
      renderer.InvalidateTextures(0, 0, 0, 32, 32);
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center] > 240 && pixels[center + 1] < 5,
            "texture RAM invalidation reaches Vulkan");
      std::fill(texels.begin(), texels.end(), 0x7fff);
      for (unsigned y = 1024; y < 1152; ++y)
        std::fill_n(texels.data() + y * 2048, 128, 0x03e0);
      material.microTexture = true;
      material.microTextureID = 0;
      material.microTextureScale = 1;
      renderer.InvalidateTextures(0, 0, 0, 2048, 2048);
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center] >= 126 && pixels[center] <= 129 &&
                pixels[center + 1] > 250 && pixels[center + 2] >= 126 &&
                pixels[center + 2] <= 129,
            "microtexture from opposite texture bank blends with base texture");
      material.microTexture = false;
      material.textured = false;
      for (unsigned i = 0; i < 3; ++i) {
        vertices[i].faceColour[1] = vertices[i].faceColour[2] = 0;
        vertices[i].pos[2] = -.5f;
        vertices[i + 3] = vertices[i];
        vertices[i + 3].faceColour[0] = 0;
        vertices[i + 3].faceColour[2] = 255;
        vertices[i + 3].pos[2] = .5f;
      }
      model.meshes->push_back(material);
      (*model.meshes)[1].vboOffset = 3;
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center] > 240 && pixels[center + 2] < 5,
            "near mesh occludes later far mesh");
      (*model.meshes)[1].highPriority = true;
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center + 2] > 240 && pixels[center] < 5,
            "overlay priority resets depth");
      model.meshes->resize(1);
      New3D::Model higher = model;
      higher.meshes = std::make_shared<std::vector<New3D::Mesh>>(*model.meshes);
      higher.meshes->front().vboOffset = 3;
      nodes.push_back({node.viewport, {higher}});
      nodes.back().viewport.priority = 1;
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center + 2] > 240 && pixels[center] < 5,
            "viewport priority resets depth between Real3D planes");
      nodes.pop_back();
      model.meshes->front().lighting = true;
      node.viewport.lightingParams[4] = .5f;
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center] >= 126 && pixels[center] <= 129 &&
                pixels[center + 1] < 5,
            "viewport ambient lighting scales material color");
      model.meshes->front().lighting = false;
      node.viewport.fogParams[1] = 1;
      node.viewport.fogParams[4] = 1;
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center + 1] > 240 && pixels[center] < 5,
            "mesh fog uses viewport color and density");
      node.viewport.fogParams[1] = node.viewport.fogParams[4] = 0;
      for (unsigned i = 0; i < 3; ++i)
        vertices[i].pos[2] = .99f;
      node.viewport.losPosX = 248;
      node.viewport.losPosY = 192;
      scene.zRanges[0] = {-1, -100};
      auto los = renderer.Render(scene);
      float expected = 200.f / (-101.f + .99f * 99.f);
      check(
          std::abs(los[0] - expected) < .03f,
          "LOS samples priority opaque depth and reconstructs guest distance");
      model.meshes->clear();
      node.viewport.losPosX = node.viewport.losPosY = 0;
      std::vector<UINT32> bottom(64 * 64, 0xff0000ff), top(64 * 64, 0x80ff0000);
      renderer.SetTileLayers(bottom.data(), top.data(), 64, 64);
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center] >= 127 && pixels[center] <= 129 &&
                pixels[center + 2] >= 126 && pixels[center + 2] <= 128,
            "native TileGen bottom and top ordered around 3D");
      renderer.SetTileLayers(nullptr, nullptr);
      node.viewport.scrollFog = .5f;
      node.viewport.fogParams[1] = 1;
      node.viewport.fogParams[3] = 1;
      renderer.Render(scene);
      pixels = renderer.Readback();
      check(pixels[center + 1] >= 126 && pixels[center + 1] <= 129 &&
                pixels[center] < 5,
            "scroll fog composites over bottom layer");
      std::puts(
          "PASS: Vulkan Real3D meshes, textures/invalidation, depth/priority, "
          "independent transparency, fog/scroll fog, TileGen and LOS");
    }
  }
  vkDestroyDevice(device, nullptr);
  if (validation) {
    auto destroy = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
        vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT"));
    destroy(instance, messenger, nullptr);
    check(validationErrors == 0, "Vulkan validation reports no errors");
    std::puts("PASS: Khronos Vulkan validation enabled, zero errors");
  }
  vkDestroyInstance(instance, nullptr);
  return 0;
}
