#include "../../main/cpp/audio_resampler.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

static void TestClockDrift(double drift, double playbackRate = 1.0) {
    constexpr size_t capacity = 16384;
    std::vector<int16_t> ring(capacity * 2);
    int16_t out[1024 * 2];
    size_t read = 0, write = 0;
    emucoreh::AudioResampler resampler;
    double nextFrame = 0, nextCallback = 0, carry = 0;
    while (nextCallback < 120) {
        if (nextFrame <= nextCallback) {
            carry += 44100.0 / 59.94;
            const size_t count = static_cast<size_t>(carry);
            carry -= count;
            const size_t available = (write + capacity - read) % capacity;
            assert(available + count < capacity);
            for (size_t i = 0; i < count; ++i) {
                ring[write * 2] = 12000;
                ring[write * 2 + 1] = -12000;
                write = (write + 1) % capacity;
            }
            nextFrame += 1.0 / (59.94 * playbackRate);
        } else {
            const auto count = resampler.Read(ring.data(), capacity, read, write, 4096, out, 1024, playbackRate);
            if (nextCallback > 1.0) assert(count == 1024);
            for (size_t i = 0; i < count; ++i) {
                assert(out[i * 2] == 12000 && out[i * 2 + 1] == -12000);
            }
            nextCallback += 1024.0 / (44100.0 * (1.0 + drift));
        }
    }
}

static void TestUnderrunAndReset() {
    int16_t ring[64 * 2]{};
    int16_t out[32 * 2]{};
    size_t read = 60, write = 8;
    emucoreh::AudioResampler resampler;
    const auto count = resampler.Read(ring, 64, read, write, 8, out, 32);
    assert(count > 0 && count < 32);
    assert((write + 64 - read) % 64 <= 1);
    assert(resampler.Read(ring, 64, read, write, 8, out, 32) == 0);
    resampler.Reset();
    read = 0;
    write = 40;
    assert(resampler.Read(ring, 64, read, write, 8, out, 16) == 16);
}

int main() {
    TestClockDrift(-0.008);
    TestClockDrift(0);
    TestClockDrift(0.008);
    TestClockDrift(0.008, 50.0 / 59.94);
    TestClockDrift(-0.008, 2.0);
    TestUnderrunAndReset();
    std::puts("Audio resampler: drift, ring wrap, underrun and reset passed");
}
