/*
	Copyright 2026 SBRO

	This file is part of Flycast.

    Flycast is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    Flycast is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Flycast.  If not, see <https://www.gnu.org/licenses/>.
 */
// Pure retry policy for vkCreateDevice feature sets.
//
// Some Android drivers (Mali blobs in particular) report a feature as
// supported from vkGetPhysicalDeviceFeatures(2) and then reject it from
// vkCreateDevice with VK_ERROR_FEATURE_NOT_PRESENT. The device creation loop
// retries with progressively smaller feature sets; this header owns the
// attempt schedule so it can be unit tested without a Vulkan device.
#pragma once

namespace vk_retry_policy {

enum class FeatureProfile {
	// Everything the physical device reported as supported.
	Full,
	// Only the two features the renderer actually toggles
	// (fragmentStoresAndAtomics, samplerAnisotropy), extension features off.
	Core,
	// No feature at all: guaranteed to pass on a spec-conforming driver.
	None,
};

constexpr int kFeatureAttempts = 3;

// The feature profile requested by the given attempt (0-based). Out-of-range
// attempts clamp to the closest profile.
constexpr FeatureProfile ProfileForAttempt(int attempt)
{
	if (attempt <= 0)
		return FeatureProfile::Full;
	if (attempt == 1)
		return FeatureProfile::Core;
	return FeatureProfile::None;
}

// True while another attempt is allowed after the one at [attempt] failed.
constexpr bool HasMoreAttempts(int attempt)
{
	return attempt + 1 < kFeatureAttempts;
}

}  // namespace vk_retry_policy
