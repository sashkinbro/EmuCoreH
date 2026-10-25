// SPDX-License-Identifier: GPL-3.0-or-later
#include "VulkanScene.h"
#include "Graphics/New3D/TextureDecode.h"
#include <VulkanShaders.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <unordered_map>
namespace New3D {
namespace {
void check(VkResult result, const char *operation) {
  if (result != VK_SUCCESS)
    throw std::runtime_error(std::string("Model3 Vulkan ") + operation +
                             " failed (" + std::to_string(result) + ")");
}
struct QueueGuard {
  const VulkanContext &ctx;
  explicit QueueGuard(const VulkanContext &c) : ctx(c) {
    if (ctx.lockQueue)
      ctx.lockQueue();
  }
  ~QueueGuard() {
    if (ctx.unlockQueue)
      ctx.unlockQueue();
  }
};
} // namespace
struct VulkanScene::Impl {
  VulkanContext ctx;
  VkPhysicalDeviceMemoryProperties memory{};
  VkCommandPool commandPool{};
  VkCommandBuffer cmd{};
  VkFence fence{};
  VkDescriptorSetLayout setLayout{};
  VkPipelineLayout pipelineLayout{};
  VkDescriptorPool descriptorPool{};
  VkRenderPass renderPass{}, loadPass{};
  VkFramebuffer framebuffer{}, layerFrames[3]{};
  VkSampler sampler{};
  VkPipeline pipelines[6]{};
  VkFormat depthFormat = VK_FORMAT_D32_SFLOAT_S8_UINT;
  unsigned width = 0, height = 0;
  size_t uniformStride = 0;
  struct Buffer {
    VkBuffer buffer{};
    VkDeviceMemory memory{};
    void *data = nullptr;
    VkDeviceSize size = 0;
  };
  struct Image {
    VkImage image{};
    VkDeviceMemory memory{};
    VkImageView view{};
    unsigned levels = 1;
  };
  Buffer vertexBuffer, uniformBuffer, losBuffer;
  Image color, depth, white, layers[3], savedDepth;
  bool savedDepthValid = false;
  bool hostReadPending = false;
  std::unordered_map<uint64_t, Image> textures;
  std::vector<Buffer> staging;
  std::vector<Image> temporaryImages;
  bool dirtyTextures = false;
  const UINT32 *tileBottom = nullptr;
  const UINT32 *tileTop = nullptr;
  unsigned tileWidth = 496, tileHeight = 384;
  explicit Impl(VulkanContext c) : ctx(std::move(c)) {
    if (!ctx.gpu || !ctx.device || !ctx.queue)
      throw std::runtime_error(
          "Model3 Vulkan requires a graphics device and queue");
    vkGetPhysicalDeviceMemoryProperties(ctx.gpu, &memory);
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(ctx.gpu, &properties);
    const size_t alignment = std::max<VkDeviceSize>(
        16, properties.limits.minUniformBufferOffsetAlignment);
    uniformStride = (sizeof(VulkanUniforms) + alignment - 1) & ~(alignment - 1);
  }
  uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags flags) {
    for (uint32_t i = 0; i < memory.memoryTypeCount; ++i)
      if ((bits & (1u << i)) &&
          (memory.memoryTypes[i].propertyFlags & flags) == flags)
        return i;
    throw std::runtime_error("Model3 Vulkan memory type unavailable");
  }
  void destroy(Buffer &b) {
    if (b.data)
      vkUnmapMemory(ctx.device, b.memory);
    if (b.buffer)
      vkDestroyBuffer(ctx.device, b.buffer, nullptr);
    if (b.memory)
      vkFreeMemory(ctx.device, b.memory, nullptr);
    b = {};
  }
  void destroy(Image &i) {
    if (i.view)
      vkDestroyImageView(ctx.device, i.view, nullptr);
    if (i.image)
      vkDestroyImage(ctx.device, i.image, nullptr);
    if (i.memory)
      vkFreeMemory(ctx.device, i.memory, nullptr);
    i = {};
  }
  void ensureBuffer(Buffer &b, VkDeviceSize size, VkBufferUsageFlags usage) {
    size = std::max<VkDeviceSize>(size, 16);
    if (b.size >= size)
      return;
    destroy(b);
    VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    info.size = size;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateBuffer(ctx.device, &info, nullptr, &b.buffer),
          "create buffer");
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(ctx.device, b.buffer, &req);
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memoryType(
        req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    check(vkAllocateMemory(ctx.device, &alloc, nullptr, &b.memory),
          "allocate buffer");
    check(vkBindBufferMemory(ctx.device, b.buffer, b.memory, 0), "bind buffer");
    check(vkMapMemory(ctx.device, b.memory, 0, VK_WHOLE_SIZE, 0, &b.data),
          "map buffer");
    b.size = size;
  }
  Image createImage(unsigned w, unsigned h, unsigned levels, VkFormat format,
                    VkImageUsageFlags usage, VkImageAspectFlags aspect,
                    VkImageCreateFlags flags = 0) {
    Image image;
    image.levels = levels;
    try {
      VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
      info.flags = flags;
      info.imageType = VK_IMAGE_TYPE_2D;
      info.format = format;
      info.extent = {w, h, 1};
      info.mipLevels = levels;
      info.arrayLayers = 1;
      info.samples = VK_SAMPLE_COUNT_1_BIT;
      info.tiling = VK_IMAGE_TILING_OPTIMAL;
      info.usage = usage;
      info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
      check(vkCreateImage(ctx.device, &info, nullptr, &image.image),
            "create image");
      VkMemoryRequirements req{};
      vkGetImageMemoryRequirements(ctx.device, image.image, &req);
      VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
      alloc.allocationSize = req.size;
      alloc.memoryTypeIndex =
          memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
      check(vkAllocateMemory(ctx.device, &alloc, nullptr, &image.memory),
            "allocate image");
      check(vkBindImageMemory(ctx.device, image.image, image.memory, 0),
            "bind image");
      VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
      view.image = image.image;
      view.viewType = VK_IMAGE_VIEW_TYPE_2D;
      view.format = format;
      view.subresourceRange = {aspect, 0, levels, 0, 1};
      check(vkCreateImageView(ctx.device, &view, nullptr, &image.view),
            "create view");
      return image;
    } catch (...) {
      destroy(image);
      throw;
    }
  }
  void barrier(const Image &image, VkImageLayout from, VkImageLayout to,
               VkAccessFlags source, VkAccessFlags dest,
               VkPipelineStageFlags sourceStage, VkPipelineStageFlags destStage,
               VkImageAspectFlags aspect = VK_IMAGE_ASPECT_COLOR_BIT) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = source;
    b.dstAccessMask = dest;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image.image;
    b.subresourceRange = {aspect, 0, image.levels, 0, 1};
    vkCmdPipelineBarrier(cmd, sourceStage, destStage, 0, 0, nullptr, 0, nullptr,
                         1, &b);
  }
  void initialize() {
    if (commandPool)
      return;
    VkCommandPoolCreateInfo pool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool.queueFamilyIndex = ctx.queueFamily;
    check(vkCreateCommandPool(ctx.device, &pool, nullptr, &commandPool),
          "command pool");
    VkCommandBufferAllocateInfo allocate{
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocate.commandPool = commandPool;
    allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate.commandBufferCount = 1;
    check(vkAllocateCommandBuffers(ctx.device, &allocate, &cmd),
          "command buffer");
    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    check(vkCreateFence(ctx.device, &fi, nullptr, &fence), "fence");
    VkDescriptorSetLayoutBinding bindings[3] = {
        {0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1,
         VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, nullptr},
        {1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
         VK_SHADER_STAGE_FRAGMENT_BIT, nullptr},
        {2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
         VK_SHADER_STAGE_FRAGMENT_BIT, nullptr}};
    VkDescriptorSetLayoutCreateInfo layout{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    layout.bindingCount = 3;
    layout.pBindings = bindings;
    check(vkCreateDescriptorSetLayout(ctx.device, &layout, nullptr, &setLayout),
          "descriptor layout");
    VkPipelineLayoutCreateInfo pl{
        VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pl.setLayoutCount = 1;
    pl.pSetLayouts = &setLayout;
    check(vkCreatePipelineLayout(ctx.device, &pl, nullptr, &pipelineLayout),
          "pipeline layout");
    VkDescriptorPoolSize sizes[] = {
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 65536},
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 131072}};
    VkDescriptorPoolCreateInfo dp{
        VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dp.maxSets = 65536;
    dp.poolSizeCount = 2;
    dp.pPoolSizes = sizes;
    check(vkCreateDescriptorPool(ctx.device, &dp, nullptr, &descriptorPool),
          "descriptor pool");
    VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.magFilter = VK_FILTER_NEAREST;
    si.minFilter = VK_FILTER_NEAREST;
    si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    si.addressModeU = si.addressModeV = si.addressModeW =
        VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.maxLod = 16;
    check(vkCreateSampler(ctx.device, &si, nullptr, &sampler), "sampler");
    VkFormatProperties props{};
    vkGetPhysicalDeviceFormatProperties(ctx.gpu, depthFormat, &props);
    if (!(props.optimalTilingFeatures &
          VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT))
      depthFormat = VK_FORMAT_D24_UNORM_S8_UINT;
    VkAttachmentDescription attachments[2]{};
    attachments[0].format = VK_FORMAT_R8G8B8A8_UNORM;
    attachments[0].samples = VK_SAMPLE_COUNT_1_BIT;
    attachments[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachments[0].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachments[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachments[0].finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    attachments[1].format = depthFormat;
    attachments[1].samples = VK_SAMPLE_COUNT_1_BIT;
    attachments[1].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachments[1].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachments[1].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachments[1].stencilStoreOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachments[1].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachments[1].finalLayout =
        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    VkAttachmentReference cr{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL},
        dr{1, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1;
    sub.pColorAttachments = &cr;
    sub.pDepthStencilAttachment = &dr;
    VkSubpassDependency deps[2]{};
    deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
    deps[0].dstSubpass = 0;
    deps[0].srcStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    deps[0].srcAccessMask =
        VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                           VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
    deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    deps[1].srcSubpass = 0;
    deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                           VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    deps[1].dstStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    deps[1].dstAccessMask =
        VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    VkRenderPassCreateInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rp.attachmentCount = 2;
    rp.pAttachments = attachments;
    rp.subpassCount = 1;
    rp.pSubpasses = &sub;
    rp.dependencyCount = 2;
    rp.pDependencies = deps;
    check(vkCreateRenderPass(ctx.device, &rp, nullptr, &renderPass),
          "render pass");
    attachments[0].loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
    attachments[0].initialLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    attachments[1].loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
    attachments[1].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
    attachments[1].initialLayout =
        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    check(vkCreateRenderPass(ctx.device, &rp, nullptr, &loadPass),
          "load render pass");
    createPipelines();
  }
  void createPipelines() {
    VkShaderModule modules[2]{};
    try {
      VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
      sm.codeSize = sizeof(real3d_vert);
      sm.pCode = real3d_vert;
      check(vkCreateShaderModule(ctx.device, &sm, nullptr, &modules[0]),
            "vertex shader");
      sm.codeSize = sizeof(real3d_frag);
      sm.pCode = real3d_frag;
      check(vkCreateShaderModule(ctx.device, &sm, nullptr, &modules[1]),
            "fragment shader");
      VkPipelineShaderStageCreateInfo stages[2]{};
      for (int i = 0; i < 2; ++i) {
        stages[i].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[i].stage =
            i ? VK_SHADER_STAGE_FRAGMENT_BIT : VK_SHADER_STAGE_VERTEX_BIT;
        stages[i].module = modules[i];
        stages[i].pName = "main";
      }
      FVertex v{};
      auto offset = [&](const void *p) {
        return uint32_t(static_cast<const unsigned char *>(p) -
                        reinterpret_cast<unsigned char *>(&v));
      };
      VkVertexInputBindingDescription binding{0, sizeof(FVertex),
                                              VK_VERTEX_INPUT_RATE_VERTEX};
      VkVertexInputAttributeDescription attributes[] = {
          {0, 0, VK_FORMAT_R32G32B32A32_SFLOAT, offset(v.pos)},
          {1, 0, VK_FORMAT_R32G32B32_SFLOAT, offset(v.normal)},
          {2, 0, VK_FORMAT_R32G32_SFLOAT, offset(v.texcoords)},
          {3, 0, VK_FORMAT_R8G8B8A8_UNORM, offset(v.faceColour)},
          {4, 0, VK_FORMAT_R32G32B32_SFLOAT, offset(v.faceNormal)},
          {5, 0, VK_FORMAT_R32_SFLOAT, offset(&v.fixedShade)}};
      VkPipelineVertexInputStateCreateInfo vi{
          VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
      vi.vertexBindingDescriptionCount = 1;
      vi.pVertexBindingDescriptions = &binding;
      vi.vertexAttributeDescriptionCount = 6;
      vi.pVertexAttributeDescriptions = attributes;
      VkPipelineInputAssemblyStateCreateInfo ia{
          VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
      ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
      VkPipelineViewportStateCreateInfo vp{
          VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
      vp.viewportCount = 1;
      vp.scissorCount = 1;
      VkPipelineRasterizationStateCreateInfo raster{
          VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
      raster.polygonMode = VK_POLYGON_MODE_FILL;
      raster.cullMode = VK_CULL_MODE_NONE;
      raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
      raster.lineWidth = 1;
      VkPipelineMultisampleStateCreateInfo ms{
          VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
      ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
      VkPipelineColorBlendAttachmentState blend{};
      blend.colorWriteMask = 15;
      blend.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
      blend.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
      blend.colorBlendOp = VK_BLEND_OP_ADD;
      blend.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
      blend.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
      blend.alphaBlendOp = VK_BLEND_OP_ADD;
      VkPipelineColorBlendStateCreateInfo cb{
          VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
      cb.attachmentCount = 1;
      cb.pAttachments = &blend;
      VkDynamicState dynamics[] = {VK_DYNAMIC_STATE_VIEWPORT,
                                   VK_DYNAMIC_STATE_SCISSOR};
      VkPipelineDynamicStateCreateInfo ds{
          VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
      ds.dynamicStateCount = 2;
      ds.pDynamicStates = dynamics;
      VkPipelineDepthStencilStateCreateInfo depthState{
          VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
      depthState.depthCompareOp = VK_COMPARE_OP_LESS_OR_EQUAL;
      depthState.front.compareOp = VK_COMPARE_OP_EQUAL;
      depthState.front.failOp = VK_STENCIL_OP_KEEP;
      depthState.front.depthFailOp = depthState.front.passOp =
          VK_STENCIL_OP_INCREMENT_AND_CLAMP;
      depthState.front.compareMask = depthState.front.writeMask = 255;
      depthState.back = depthState.front;
      for (unsigned variant = 0; variant < 6; ++variant) {
        bool tile = variant >= 4;
        bool transparent = variant & 1;
        blend.blendEnable = tile && transparent;
        depthState.depthCompareOp =
            transparent ? VK_COMPARE_OP_LESS : VK_COMPARE_OP_LESS_OR_EQUAL;
        depthState.depthTestEnable = !tile;
        depthState.depthWriteEnable = !tile;
        depthState.stencilTestEnable = !tile && (variant & 2);
        VkGraphicsPipelineCreateInfo pi{
            VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        pi.stageCount = 2;
        pi.pStages = stages;
        pi.pVertexInputState = &vi;
        pi.pInputAssemblyState = &ia;
        pi.pViewportState = &vp;
        pi.pRasterizationState = &raster;
        pi.pMultisampleState = &ms;
        pi.pDepthStencilState = &depthState;
        pi.pColorBlendState = &cb;
        pi.pDynamicState = &ds;
        pi.layout = pipelineLayout;
        pi.renderPass = renderPass;
        check(vkCreateGraphicsPipelines(ctx.device, VK_NULL_HANDLE, 1, &pi,
                                        nullptr, &pipelines[variant]),
              "mesh pipeline");
      }
    } catch (...) {
      for (auto module : modules)
        if (module)
          vkDestroyShaderModule(ctx.device, module, nullptr);
      throw;
    }
    for (auto module : modules)
      vkDestroyShaderModule(ctx.device, module, nullptr);
  }
  void resize(unsigned w, unsigned h) {
    if (width == w && height == h)
      return;
    if (!w || !h || w > 8192 || h > 8192)
      throw std::runtime_error("Model3 Vulkan invalid extent");
    if (framebuffer)
      vkDestroyFramebuffer(ctx.device, framebuffer, nullptr);
    framebuffer = {};
    for (auto &fb : layerFrames) {
      if (fb)
        vkDestroyFramebuffer(ctx.device, fb, nullptr);
      fb = {};
    }
    destroy(color);
    destroy(depth);
    for (auto &image : layers)
      destroy(image);
    destroy(savedDepth);
    savedDepthValid = false;
    width = w;
    height = h;
    auto createColor = [&](VkImageCreateFlags flags = 0) {
      return createImage(w, h, 1, VK_FORMAT_R8G8B8A8_UNORM,
                         VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                             VK_IMAGE_USAGE_SAMPLED_BIT |
                             VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                         VK_IMAGE_ASPECT_COLOR_BIT, flags);
    };
    // libretro permits the frontend to reinterpret 8-bit UNORM output as sRGB.
    color = createColor(VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT);
    for (auto &image : layers)
      image = createColor();
    depth = createImage(
        w, h, 1, depthFormat,
        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT |
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT);
    savedDepth = createImage(
        w, h, 1, depthFormat,
        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT |
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT);
    auto createFrame = [&](Image &image, VkFramebuffer &target) {
      VkImageView views[] = {image.view, depth.view};
      VkFramebufferCreateInfo fb{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
      fb.renderPass = renderPass;
      fb.attachmentCount = 2;
      fb.pAttachments = views;
      fb.width = w;
      fb.height = h;
      fb.layers = 1;
      check(vkCreateFramebuffer(ctx.device, &fb, nullptr, &target),
            "framebuffer");
    };
    createFrame(color, framebuffer);
    for (unsigned i = 0; i < 3; ++i)
      createFrame(layers[i], layerFrames[i]);
  }
  void startPass(VkFramebuffer target, bool clear, bool background = false) {
    VkClearValue clears[2]{};
    if (background)
      clears[0].color.float32[3] = 1;
    clears[1].depthStencil = {1, 0};
    VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    rp.renderPass = clear ? renderPass : loadPass;
    rp.framebuffer = target;
    rp.renderArea.extent = {width, height};
    rp.clearValueCount = 2;
    rp.pClearValues = clears;
    vkCmdBeginRenderPass(cmd, &rp, VK_SUBPASS_CONTENTS_INLINE);
    VkDeviceSize zero = 0;
    vkCmdBindVertexBuffers(cmd, 0, 1, &vertexBuffer.buffer, &zero);
    VkRect2D scissor{{0, 0}, {width, height}};
    vkCmdSetScissor(cmd, 0, 1, &scissor);
  }
  void copyDepth(bool restore, int priority = -1,
                 const Viewport *los = nullptr) {
    const auto aspect = VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
    barrier(depth, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
            restore ? VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                    : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT |
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            restore ? VK_ACCESS_TRANSFER_WRITE_BIT
                    : VK_ACCESS_TRANSFER_READ_BIT,
            VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
                VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, aspect);
    if (!restore)
      barrier(savedDepth,
              savedDepthValid ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                              : VK_IMAGE_LAYOUT_UNDEFINED,
              VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
              savedDepthValid ? VK_ACCESS_TRANSFER_READ_BIT : 0,
              VK_ACCESS_TRANSFER_WRITE_BIT,
              savedDepthValid ? VK_PIPELINE_STAGE_TRANSFER_BIT
                              : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
              VK_PIPELINE_STAGE_TRANSFER_BIT, aspect);
    VkImageCopy copies[2]{};
    for (unsigned i = 0; i < 2; ++i) {
      auto a = i ? VK_IMAGE_ASPECT_STENCIL_BIT : VK_IMAGE_ASPECT_DEPTH_BIT;
      copies[i].srcSubresource = {a, 0, 0, 1};
      copies[i].dstSubresource = {a, 0, 0, 1};
      copies[i].extent = {width, height, 1};
    }
    vkCmdCopyImage(cmd, restore ? savedDepth.image : depth.image,
                   VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                   restore ? depth.image : savedDepth.image,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 2, copies);
    if (!restore && los && priority >= 0) {
      int x = int(los->losPosX * (double(width) / 496)),
          y = int(los->losPosY * (double(height) / 384));
      if (x >= 0 && x < int(width) && y >= 0 && y < int(height)) {
        VkBufferImageCopy copy{};
        copy.bufferOffset = priority * 4;
        copy.imageSubresource = {VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1};
        copy.imageOffset = {x, y, 0};
        copy.imageExtent = {1, 1, 1};
        vkCmdCopyImageToBuffer(cmd, depth.image,
                               VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               losBuffer.buffer, 1, &copy);
        hostReadPending = true;
      }
    }
    barrier(depth,
            restore ? VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                    : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
            restore ? VK_ACCESS_TRANSFER_WRITE_BIT
                    : VK_ACCESS_TRANSFER_READ_BIT,
            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT |
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
                VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
            aspect);
    if (!restore) {
      barrier(savedDepth, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
              VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
              VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
              VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
              aspect);
      savedDepthValid = true;
    }
  }
  void begin() {
    hostReadPending = false;
    check(vkResetCommandPool(ctx.device, commandPool, 0), "reset commands");
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    check(vkBeginCommandBuffer(cmd, &bi), "begin commands");
  }
  void submit() {
    if (hostReadPending) {
      // A completed fence supplies execution ordering. Device writes must also
      // become visible to the host, including for HOST_COHERENT allocations.
      VkMemoryBarrier visibility{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
      visibility.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      visibility.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
      vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                           VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &visibility, 0,
                           nullptr, 0, nullptr);
    }
    check(vkEndCommandBuffer(cmd), "end commands");
    check(vkResetFences(ctx.device, 1, &fence), "reset fence");
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    {
      QueueGuard guard(ctx);
      check(vkQueueSubmit(ctx.queue, 1, &si, fence), "submit");
    }
    check(vkWaitForFences(ctx.device, 1, &fence, VK_TRUE, UINT64_MAX),
          "wait fence");
  }
  Image upload(const std::vector<UINT8> &rgba, unsigned w, unsigned h,
               const std::vector<VkBufferImageCopy> &regions) {
    Image image = createImage(
        w, h, unsigned(regions.size()), VK_FORMAT_R8G8B8A8_UNORM,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        VK_IMAGE_ASPECT_COLOR_BIT);
    try {
      staging.emplace_back();
      auto &buffer = staging.back();
      ensureBuffer(buffer, rgba.size(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
      std::memcpy(buffer.data, rgba.data(), rgba.size());
      barrier(image, VK_IMAGE_LAYOUT_UNDEFINED,
              VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
              VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
              VK_PIPELINE_STAGE_TRANSFER_BIT);
      vkCmdCopyBufferToImage(cmd, buffer.buffer, image.image,
                             VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                             unsigned(regions.size()), regions.data());
      barrier(image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
              VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
              VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
              VK_PIPELINE_STAGE_TRANSFER_BIT,
              VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
      return image;
    } catch (...) {
      destroy(image);
      throw;
    }
  }
  Image &texture(const SceneFrame &frame, int format, int x, int y, int w,
                 int h) {
    if (!frame.textureRAM || w <= 0 || h <= 0 || w > 1024 || h > 1024)
      return white;
    x &= 2047;
    y &= 2047;
    uint64_t key = uint64_t(x) | (uint64_t(y) << 11) | (uint64_t(w) << 22) |
                   (uint64_t(h) << 33) | (uint64_t(format & 15) << 44);
    auto found = textures.find(key);
    if (found != textures.end())
      return found->second;
    std::vector<UINT8> pixels;
    std::vector<VkBufferImageCopy> copies;
    for (unsigned level = 0, mw = w, mh = h; mw && mh;
         ++level, mw /= 2, mh /= 2) {
      auto origin = TextureMipOrigin(x, y, level);
      VkBufferImageCopy copy{};
      copy.bufferOffset = pixels.size();
      copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, level, 0, 1};
      copy.imageExtent = {mw, mh, 1};
      copies.push_back(copy);
      for (unsigned iy = 0; iy < mh; ++iy)
        for (unsigned ix = 0; ix < mw; ++ix) {
          std::array<UINT8, 4> pixel{};
          if (origin.x + ix < 2048 && origin.y + iy < 2048)
            pixel = DecodeTexel(
                frame.textureRAM[(origin.y + iy) * 2048 + origin.x + ix],
                format);
          pixels.insert(pixels.end(), pixel.begin(), pixel.end());
        }
    }
    return textures.emplace(key, upload(pixels, w, h, copies)).first->second;
  }
  Image *tile(const UINT32 *argb) {
    if (!argb)
      return nullptr;
    std::vector<UINT8> rgba(size_t(tileWidth) * tileHeight * 4);
    for (size_t i = 0; i < rgba.size() / 4; ++i) {
      UINT32 p = argb[i];
      rgba[i * 4] = p >> 16;
      rgba[i * 4 + 1] = p >> 8;
      rgba[i * 4 + 2] = p;
      rgba[i * 4 + 3] = p >> 24;
    }
    VkBufferImageCopy copy{};
    copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.imageExtent = {tileWidth, tileHeight, 1};
    temporaryImages.push_back(upload(rgba, tileWidth, tileHeight, {copy}));
    return &temporaryImages.back();
  }
  VkDescriptorSet descriptor(Image &base, Image &micro) {
    VkDescriptorSet set{};
    VkDescriptorSetAllocateInfo alloc{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    alloc.descriptorPool = descriptorPool;
    alloc.descriptorSetCount = 1;
    alloc.pSetLayouts = &setLayout;
    check(vkAllocateDescriptorSets(ctx.device, &alloc, &set),
          "allocate descriptor");
    VkDescriptorBufferInfo buffer{uniformBuffer.buffer, 0,
                                  sizeof(VulkanUniforms)};
    VkDescriptorImageInfo images[] = {
        {sampler, base.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL},
        {sampler, micro.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}};
    VkWriteDescriptorSet writes[3]{};
    for (unsigned i = 0; i < 3; ++i) {
      writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[i].dstSet = set;
      writes[i].dstBinding = i;
      writes[i].descriptorCount = 1;
      writes[i].descriptorType = i ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                   : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
    }
    writes[0].pBufferInfo = &buffer;
    writes[1].pImageInfo = &images[0];
    writes[2].pImageInfo = &images[1];
    vkUpdateDescriptorSets(ctx.device, 3, writes, 0, nullptr);
    return set;
  }
  VulkanUniforms uniforms(const Viewport &vp, const Model &model,
                          const Mesh &mesh, bool opaque) {
    VulkanUniforms u{};
    u.modelScale = model.scale;
    std::memcpy(u.modelMat, model.modelMat, 64);
    std::memcpy(u.projMat, vp.projectionMatrix.currentMatrix, 64);
    u.translatorMap = mesh.translatorMap;
    u.textureEnabled = mesh.textured;
    u.microTexture = mesh.microTexture;
    u.microTextureScale = mesh.microTextureScale;
    u.baseTexSize[0] = mesh.width;
    u.baseTexSize[1] = mesh.height;
    u.textureInverted = mesh.inverted;
    u.textureAlpha = mesh.textureAlpha;
    u.alphaTest = mesh.alphaTest;
    u.discardAlpha = opaque;
    u.textureWrapMode[0] = mesh.wrapModeU;
    u.textureWrapMode[1] = mesh.wrapModeV;
    std::copy_n(vp.fogParams, 3, u.fogColour);
    std::copy_n(vp.spotEllipse, 4, u.spotEllipse);
    std::copy_n(vp.spotRange, 2, u.spotRange);
    std::copy_n(vp.spotColor, 3, u.spotColor);
    std::copy_n(vp.spotFogColor, 3, u.spotFogColor);
    std::copy_n(vp.lightingParams, 3, u.lighting);
    std::copy_n(vp.lightingParams + 3, 3, u.lighting + 4);
    u.lightEnabled = mesh.lighting;
    u.sunClamp = vp.sunClamp;
    u.intensityClamp = vp.intensityClamp;
    u.specularEnabled = mesh.specular;
    u.specularValue = mesh.specularValue;
    u.shininess = mesh.shininess;
    u.fogIntensity = mesh.fogIntensity;
    u.fogDensity = vp.fogParams[3];
    u.fogStart = vp.fogParams[4];
    u.fogAttenuation = vp.fogParams[5];
    u.fogAmbient = vp.fogParams[6];
    u.fixedShading = mesh.fixedShading;
    u.hardwareStep = vp.hardwareStep;
    u.targetHeight = height;
    return u;
  }
  struct Draw {
    const Viewport *vp = nullptr;
    VkDescriptorSet set{};
    uint32_t first = 0, count = 0;
    unsigned variant = 0;
  };
  void draw(const Draw &d, size_t index) {
    VkViewport vp{0, 0, float(width), float(height), 0, 1};
    if (d.vp) {
      vp.x = float(d.vp->x);
      vp.y = float(int(height) - d.vp->y - d.vp->height);
      vp.width = float(d.vp->width);
      vp.height = float(d.vp->height);
    }
    vkCmdSetViewport(cmd, 0, 1, &vp);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                      pipelines[d.variant]);
    uint32_t dynamic = uint32_t(index * uniformStride);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            pipelineLayout, 0, 1, &d.set, 1, &dynamic);
    vkCmdDraw(cmd, d.count, 1, d.first, 0);
  }
  std::array<float, 4> render(const SceneFrame &frame) {
    {
      QueueGuard guard(ctx);
      check(vkQueueWaitIdle(ctx.queue), "wait shared queue");
    }
    initialize();
    resize(frame.width, frame.height);
    for (auto &b : staging)
      destroy(b);
    staging.clear();
    for (auto &image : temporaryImages)
      destroy(image);
    temporaryImages.clear();
    temporaryImages.reserve(2);
    if (dirtyTextures) {
      for (auto &entry : textures)
        destroy(entry.second);
      textures.clear();
      dirtyTextures = false;
    }
    check(vkResetDescriptorPool(ctx.device, descriptorPool, 0),
          "reset descriptors");
    size_t drawCount = 64;
    for (auto &n : frame.nodes)
      for (auto &m : n.models)
        if (m.meshes)
          drawCount += m.meshes->size() * 3;
    if (drawCount > 65536)
      throw std::runtime_error("Model3 Vulkan draw limit exceeded");
    ensureBuffer(vertexBuffer,
                 std::max<size_t>(3, frame.rom.size() + frame.ram.size()) * sizeof(FVertex),
                 VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
    if (!frame.rom.empty())
      std::memcpy(vertexBuffer.data, frame.rom.data(),
                  frame.rom.size() * sizeof(FVertex));
    if (!frame.ram.empty())
      std::memcpy(static_cast<char *>(vertexBuffer.data) +
                      frame.rom.size() * sizeof(FVertex),
                  frame.ram.data(), frame.ram.size() * sizeof(FVertex));
    ensureBuffer(uniformBuffer, drawCount * uniformStride,
                 VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
    ensureBuffer(losBuffer, 16, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    for (unsigned i = 0; i < 4; ++i) {
      if (depthFormat == VK_FORMAT_D32_SFLOAT_S8_UINT)
        static_cast<float *>(losBuffer.data)[i] = 1;
      else
        static_cast<UINT32 *>(losBuffer.data)[i] = 0xffffff;
    }
    begin();
    if (!white.image) {
      VkBufferImageCopy copy{};
      copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
      copy.imageExtent = {1, 1, 1};
      white = upload({255, 255, 255, 255}, 1, 1, {copy});
    }
    Image *bottom = tile(tileBottom);
    Image *top = tile(tileTop);
    std::vector<Draw> draws;
    draws.reserve(drawCount);
    auto add = [&](Draw d, const VulkanUniforms &u, Image &base, Image &micro) {
      std::memcpy(static_cast<char *>(uniformBuffer.data) +
                      draws.size() * uniformStride,
                  &u, sizeof(u));
      d.set = descriptor(base, micro);
      draws.push_back(d);
      return draws.size() - 1;
    };
    auto screen = [&](int mode, Image &base, Image &micro, bool blend,
                      const Viewport *vp = nullptr,
                      const VulkanUniforms *state = nullptr) {
      VulkanUniforms u = state ? *state : VulkanUniforms{};
      u.tileMode = mode;
      u.targetHeight = height;
      Draw d;
      d.vp = vp;
      d.count = 3;
      d.variant = blend ? 5 : 4;
      return add(d, u, base, micro);
    };
    const size_t none = std::numeric_limits<size_t>::max();
    size_t bottomDraw = bottom ? screen(1, *bottom, white, false) : none,
           topDraw = top ? screen(1, *top, white, true) : none;
    size_t wipe = screen(2, layers[0], white, false),
           baseComposite = screen(3, layers[0], white, false),
           alphaComposite = screen(4, layers[1], layers[2], true);
    size_t scroll = none;
    const Viewport *scrollSource = nullptr;
    for (int pri = 0; pri < 4 && !scrollSource; ++pri)
      for (auto &n : frame.nodes)
        if (n.viewport.priority == pri && n.viewport.scrollFog != 0) {
          scrollSource = &n.viewport;
          break;
        }
    if (scrollSource) {
      const Viewport *match = nullptr;
      for (int pri = 0; pri < 4 && !match; ++pri)
        for (auto &n : frame.nodes)
          if (n.viewport.priority == pri && n.viewport.fogParams[3] != 0 &&
              std::equal(n.viewport.fogParams, n.viewport.fogParams + 3,
                         scrollSource->fogParams)) {
            match = &n.viewport;
            break;
          }
      if (match) {
        VulkanUniforms u{};
        std::copy_n(scrollSource->fogParams, 3, u.fogColour);
        u.fogIntensity = scrollSource->scrollFog;
        u.fogAttenuation = match->scrollAtt;
        u.fogAmbient = match->fogParams[6];
        std::copy_n(match->spotEllipse, 4, u.spotEllipse);
        std::copy_n(match->spotFogColor, 3, u.spotFogColor);
        scroll = screen(5, white, white, true, match, &u);
      }
    }
    std::array<std::array<std::vector<size_t>, 3>, 8> groups;
    const Viewport *los[4]{};
    for (int priority = 0; priority < 4; ++priority)
      for (int overlay = 0; overlay < 2; ++overlay) {
        for (unsigned pass = 0; pass < 3; ++pass) {
          auto layer = static_cast<Layer>(pass);
          for (auto &node : frame.nodes) {
            if (node.viewport.priority != priority ||
                node.viewport.width <= 0 || node.viewport.height <= 0)
              continue;
            if (!los[priority] &&
                (node.viewport.losPosX || node.viewport.losPosY))
              los[priority] = &node.viewport;
            for (auto &model : node.models) {
              if (!model.meshes)
                continue;
              for (auto &mesh : *model.meshes) {
                if (!SceneFrame::Draws(mesh, layer, overlay != 0))
                  continue;
                auto view = frame.Vertices(model, mesh);
                if (!view.count)
                  continue;
                Image *base = &white;
                Image *micro = &white;
                if (mesh.textured) {
                  auto origin = SceneFrame::TextureOrigin(model, mesh);
                  base = &texture(frame, mesh.format, origin.x, origin.y,
                                  mesh.width, mesh.height);
                  if (mesh.microTexture) {
                    static constexpr int xs[] = {0, 0, 128, 128,
                                                 0, 0, 128, 128},
                                         ys[] = {0,   128, 0,   128,
                                                 256, 384, 256, 384};
                    int id = mesh.microTextureID & 7;
                    micro =
                        &texture(frame, 0, xs[id],
                                 ys[id] + (((origin.y / 1024 + 1) & 1) * 1024),
                                 128, 128);
                  }
                }
                Draw d;
                d.vp = &node.viewport;
                d.first = model.dynamic
                              ? uint32_t(frame.rom.size() +
                                         size_t(mesh.vboOffset) - frame.ramBase)
                              : uint32_t(mesh.vboOffset);
                d.count = uint32_t(view.count);
                d.variant = (pass ? 1 : 0) + (mesh.layered ? 2 : 0);
                groups[priority * 2 + overlay][pass].push_back(
                    add(d, uniforms(node.viewport, model, mesh, !pass), *base,
                        *micro));
              }
            }
          }
        }
      }
    startPass(framebuffer, true, true);
    if (bottomDraw != none)
      draw(draws[bottomDraw], bottomDraw);
    if (scroll != none)
      draw(draws[scroll], scroll);
    vkCmdEndRenderPass(cmd);
    for (unsigned i = 1; i < 3; ++i) {
      startPass(layerFrames[i], true);
      vkCmdEndRenderPass(cmd);
    }
    for (unsigned group = 0; group < 8; ++group) {
      auto &passes = groups[group];
      if (passes[0].empty() && passes[1].empty() && passes[2].empty())
        continue;
      startPass(layerFrames[0], true);
      for (auto index : passes[0])
        draw(draws[index], index);
      vkCmdEndRenderPass(cmd);
      copyDepth(false, group % 2 ? -1 : int(group / 2),
                group % 2 ? nullptr : los[group / 2]);
      for (unsigned i = 1; i < 3; ++i) {
        startPass(layerFrames[i], false);
        draw(draws[wipe], wipe);
        vkCmdEndRenderPass(cmd);
      }
      startPass(framebuffer, false);
      draw(draws[baseComposite], baseComposite);
      vkCmdEndRenderPass(cmd);
      startPass(layerFrames[1], false);
      for (auto index : passes[1])
        draw(draws[index], index);
      vkCmdEndRenderPass(cmd);
      copyDepth(true);
      startPass(layerFrames[2], false);
      for (auto index : passes[2])
        draw(draws[index], index);
      vkCmdEndRenderPass(cmd);
    }
    startPass(framebuffer, false);
    draw(draws[alphaComposite], alphaComposite);
    if (topDraw != none)
      draw(draws[topDraw], topDraw);
    vkCmdEndRenderPass(cmd);
    submit();
    std::array<float, 4> values{};
    for (unsigned i = 0; i < 4; ++i) {
      float d =
          depthFormat == VK_FORMAT_D32_SFLOAT_S8_UINT
              ? static_cast<float *>(losBuffer.data)[i]
              : float(static_cast<UINT32 *>(losBuffer.data)[i] & 0xffffff) /
                    16777215.f;
      const auto range = frame.zRanges[i];
      if (los[i] && d >= .99f && d < 1.f && range[0] < 0 && range[1] < 0) {
        float ndc = d * 2 - 1;
        values[i] = 2 * range[0] * range[1] /
                    (range[1] + range[0] - ndc * (range[1] - range[0]));
      }
    }
    return values;
  }
  std::vector<UINT8> readback() {
    if (!color.image)
      return {};
    {
      QueueGuard guard(ctx);
      check(vkQueueWaitIdle(ctx.queue), "wait readback");
    }
    Buffer read;
    try {
      ensureBuffer(read, size_t(width) * height * 4,
                   VK_BUFFER_USAGE_TRANSFER_DST_BIT);
      begin();
      barrier(color, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
              VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_SHADER_READ_BIT,
              VK_ACCESS_TRANSFER_READ_BIT,
              VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
              VK_PIPELINE_STAGE_TRANSFER_BIT);
      VkBufferImageCopy copy{};
      copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
      copy.imageExtent = {width, height, 1};
      vkCmdCopyImageToBuffer(cmd, color.image,
                             VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, read.buffer,
                             1, &copy);
      hostReadPending = true;
      barrier(color, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
              VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
              VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
              VK_PIPELINE_STAGE_TRANSFER_BIT,
              VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
      submit();
      std::vector<UINT8> pixels(size_t(width) * height * 4);
      std::memcpy(pixels.data(), read.data, pixels.size());
      destroy(read);
      return pixels;
    } catch (...) {
      destroy(read);
      throw;
    }
  }
  ~Impl() {
    if (!ctx.device)
      return;
    {
      QueueGuard guard(ctx);
      vkQueueWaitIdle(ctx.queue);
    }
    for (auto &b : staging)
      destroy(b);
    for (auto &image : temporaryImages)
      destroy(image);
    for (auto &entry : textures)
      destroy(entry.second);
    destroy(white);
    destroy(vertexBuffer);
    destroy(uniformBuffer);
    destroy(losBuffer);
    for (auto fb : layerFrames)
      if (fb)
        vkDestroyFramebuffer(ctx.device, fb, nullptr);
    for (auto &image : layers)
      destroy(image);
    destroy(savedDepth);
    if (framebuffer)
      vkDestroyFramebuffer(ctx.device, framebuffer, nullptr);
    destroy(color);
    destroy(depth);
    for (auto p : pipelines)
      if (p)
        vkDestroyPipeline(ctx.device, p, nullptr);
    if (renderPass)
      vkDestroyRenderPass(ctx.device, renderPass, nullptr);
    if (loadPass)
      vkDestroyRenderPass(ctx.device, loadPass, nullptr);
    if (descriptorPool)
      vkDestroyDescriptorPool(ctx.device, descriptorPool, nullptr);
    if (pipelineLayout)
      vkDestroyPipelineLayout(ctx.device, pipelineLayout, nullptr);
    if (setLayout)
      vkDestroyDescriptorSetLayout(ctx.device, setLayout, nullptr);
    if (sampler)
      vkDestroySampler(ctx.device, sampler, nullptr);
    if (fence)
      vkDestroyFence(ctx.device, fence, nullptr);
    if (commandPool)
      vkDestroyCommandPool(ctx.device, commandPool, nullptr);
  }
};
VulkanScene::VulkanScene(VulkanContext c)
    : impl(std::make_unique<Impl>(std::move(c))) {}
VulkanScene::~VulkanScene() = default;
std::array<float, 4> VulkanScene::Render(const SceneFrame &frame) {
  return impl->render(frame);
}
void VulkanScene::InvalidateTextures(unsigned, unsigned, unsigned, unsigned,
                                     unsigned) {
  impl->dirtyTextures = true;
}
void VulkanScene::SetTileLayers(const UINT32 *bottom, const UINT32 *top,
                                unsigned width, unsigned height) {
  impl->tileBottom = bottom;
  impl->tileTop = top;
  impl->tileWidth = width;
  impl->tileHeight = height;
}
VkImage VulkanScene::Image() const { return impl->color.image; }
VkImageView VulkanScene::View() const { return impl->color.view; }
unsigned VulkanScene::Width() const { return impl->width; }
unsigned VulkanScene::Height() const { return impl->height; }
std::vector<UINT8> VulkanScene::Readback() { return impl->readback(); }
} // namespace New3D
