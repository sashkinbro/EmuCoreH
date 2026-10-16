// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-2.0-or-later

#include "../../../../core/core/rend/vulkan/vk_device_retry_policy.h"

#include <cassert>

int main() {
    using vk_retry_policy::FeatureProfile;
    using vk_retry_policy::HasMoreAttempts;
    using vk_retry_policy::kFeatureAttempts;
    using vk_retry_policy::ProfileForAttempt;
    static_assert(kFeatureAttempts == 3);

    // The initial attempt asks for everything the driver reported.
    assert(ProfileForAttempt(0) == FeatureProfile::Full);
    // The first retry matches the classic Flycast feature profile.
    assert(ProfileForAttempt(1) == FeatureProfile::Core);
    // The last retry requests no feature at all.
    assert(ProfileForAttempt(2) == FeatureProfile::None);
    // Out-of-range attempts clamp to the closest profile.
    assert(ProfileForAttempt(3) == FeatureProfile::None);
    assert(ProfileForAttempt(-1) == FeatureProfile::Full);

    // Two retries are allowed after the initial attempt, no more.
    assert(HasMoreAttempts(0));
    assert(HasMoreAttempts(1));
    assert(!HasMoreAttempts(2));
    assert(!HasMoreAttempts(3));

    // The ladder never grows back to a larger feature set.
    assert(ProfileForAttempt(1) != ProfileForAttempt(0));
    assert(ProfileForAttempt(2) != ProfileForAttempt(1));
    return 0;
}
