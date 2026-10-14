// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once

#include <vulkan/vulkan.hpp>
#include <array>

// Registration and pool allocation use the same binding descriptions.
inline auto oitPerFrameDescriptorBindings()
{
    return std::array<vk::DescriptorSetLayoutBinding, 10> {
        vk::DescriptorSetLayoutBinding(0, vk::DescriptorType::eUniformBuffer, 1, vk::ShaderStageFlagBits::eVertex),
        vk::DescriptorSetLayoutBinding(1, vk::DescriptorType::eUniformBuffer, 1, vk::ShaderStageFlagBits::eFragment),
        vk::DescriptorSetLayoutBinding(2, vk::DescriptorType::eCombinedImageSampler, 1, vk::ShaderStageFlagBits::eFragment),
        vk::DescriptorSetLayoutBinding(3, vk::DescriptorType::eStorageBuffer, 1, vk::ShaderStageFlagBits::eFragment),
        vk::DescriptorSetLayoutBinding(4, vk::DescriptorType::eInputAttachment, 1, vk::ShaderStageFlagBits::eFragment),
        vk::DescriptorSetLayoutBinding(5, vk::DescriptorType::eInputAttachment, 1, vk::ShaderStageFlagBits::eFragment),
        vk::DescriptorSetLayoutBinding(6, vk::DescriptorType::eCombinedImageSampler, 1, vk::ShaderStageFlagBits::eFragment),
        vk::DescriptorSetLayoutBinding(7, vk::DescriptorType::eStorageBuffer, 1, vk::ShaderStageFlagBits::eFragment),
        vk::DescriptorSetLayoutBinding(8, vk::DescriptorType::eStorageBuffer, 1, vk::ShaderStageFlagBits::eFragment),
        vk::DescriptorSetLayoutBinding(9, vk::DescriptorType::eStorageBuffer, 1, vk::ShaderStageFlagBits::eFragment)
    };
}

inline auto oitColorInputDescriptorBindings()
{
    return std::array<vk::DescriptorSetLayoutBinding, 1> {
        vk::DescriptorSetLayoutBinding(0, vk::DescriptorType::eInputAttachment, 1, vk::ShaderStageFlagBits::eFragment)
    };
}
