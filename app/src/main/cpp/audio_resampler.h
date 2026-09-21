#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>

namespace emucoreh {

// Follows small device-clock differences without changing emulation speed.
// Accessed only by the audio callback while the audio ring is locked.
class AudioResampler {
public:
    void Reset() {
        fraction_ = 0.0;
        ratio_ = 1.0;
        base_ratio_ = 1.0;
        buffering_ = true;
    }

    size_t Read(const int16_t* ring, size_t capacity, size_t& read, size_t write,
                size_t target, int16_t* out, size_t frames, double baseRatio = 1.0) {
        baseRatio = std::clamp(baseRatio, 0.25, 2.0);
        if (baseRatio != base_ratio_) {
            ratio_ = baseRatio;
            base_ratio_ = baseRatio;
        }
        size_t available = (write + capacity - read) % capacity;
        if (buffering_) {
            if (available < std::max(target, size_t{2})) return 0;
            buffering_ = false;
        }
        const double error = (static_cast<double>(available) - target) / std::max(target, size_t{1});
        const double desired = baseRatio * (1.0 + std::clamp(error * 0.02, -0.02, 0.02));
        ratio_ += (desired - ratio_) * 0.05;

        size_t produced = 0;
        while (produced < frames && available >= std::max(size_t{2}, static_cast<size_t>(fraction_ + ratio_) + 1)) {
            const size_t next = (read + 1) % capacity;
            for (size_t channel = 0; channel < 2; ++channel) {
                const double a = ring[read * 2 + channel];
                const double b = ring[next * 2 + channel];
                out[produced * 2 + channel] = static_cast<int16_t>(a + (b - a) * fraction_);
            }
            fraction_ += ratio_;
            const size_t consumed = static_cast<size_t>(fraction_);
            fraction_ -= consumed;
            read = (read + consumed) % capacity;
            available -= consumed;
            ++produced;
        }
        if (produced < frames) Reset();
        return produced;
    }

private:
    double fraction_ = 0.0;
    double ratio_ = 1.0;
    double base_ratio_ = 1.0;
    bool buffering_ = true;
};

}  // namespace emucoreh
