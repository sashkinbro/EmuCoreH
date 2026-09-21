/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <functional>
#include <optional>
#include <ostream>
#include <queue>
#include <thread>
#include <vector>

#include "SwappyCommon.h"
#include "gtest/gtest.h"

#define LOG_TAG "SCTest"
#include "Log.h"

using namespace swappy;

using time_point = std::chrono::steady_clock::time_point;
using duration = std::chrono::steady_clock::duration;

// Spoof these - we shouldn't be doing any Java loading in the tests.
extern "C" {
char _binary_classes_dex_start;
char _binary_classes_dex_end;
}

namespace swappycommon_test {

class SwappyCommonTest : public SwappyCommon {
   public:
    SwappyCommonTest(const SwappyCommonSettings& settings)
        : SwappyCommon(settings) {}
};

struct Workload {
    std::function<duration()> cpuWorkTime;
    std::function<duration()> gpuWorkTime;
};

struct Parameters {
    std::optional<bool> autoModeEnabled;
    std::optional<bool> autoPipelineModeEnabled;
    std::optional<duration> fenceTimeout;
    std::optional<duration> maxAutoSwapInterval;
    std::optional<duration> swapInterval;
};

template <typename T>
struct Event {
    T value;
    duration time_offset;  // Offset from start of simulation
};

struct ITimeProvider {
    virtual duration timeFromStart() const = 0;
};

class GpuThread {
   public:
    enum Fence { NO_SYNC, SIGNALLED, UNSIGNALLED };

   private:
    Fence fence_;
    duration fenceTimeout_;
    std::mutex fenceMutex_;
    std::mutex threadMutex_;
    std::thread thread_;
    std::condition_variable fenceCondition_;
    std::condition_variable threadCondition_;
    duration waitTime_;
    bool running_;
    ITimeProvider* timeProvider_;
    duration fenceCreationTime_;

   public:
    GpuThread(ITimeProvider* timeProvider, duration fenceTimeout)
        : fence_(NO_SYNC),
          fenceTimeout_(fenceTimeout),
          waitTime_(0),
          running_(true),
          timeProvider_(timeProvider) {
        thread_ = std::thread(&GpuThread::run, this);
    }
    ~GpuThread() {
        {
            std::lock_guard<std::mutex> lock(threadMutex_);
            fence_ = UNSIGNALLED;
            waitTime_ = duration(0);
            running_ = false;
        }
        threadCondition_.notify_all();
        thread_.join();
    }
    Fence fenceStatus() const { return fence_; }
    void waitForFence() {
        std::unique_lock<std::mutex> lock(fenceMutex_);
        [[maybe_unused]] bool fenceAlreadySignalled = fence_ != UNSIGNALLED;
        if (!fenceCondition_.wait_for(lock, fenceTimeout_, [this]() {
                return fence_ != UNSIGNALLED;
            })) {
            ALOGW("Fence Timeout");
        }
        ALOGV("Fence completed in %lld ns (%s)",
              (timeProvider_->timeFromStart() - fenceCreationTime_).count(),
              fenceAlreadySignalled ? "no extra wait" : "wait");
    }
    void createFence(duration gpuFrameTime) {
        std::lock_guard<std::mutex> lock(threadMutex_);
        fence_ = UNSIGNALLED;
        waitTime_ = gpuFrameTime;
        fenceCreationTime_ = timeProvider_->timeFromStart();
        threadCondition_.notify_all();
    }
    void run() {
        while (running_) {
            {
                std::unique_lock<std::mutex> lock(threadMutex_);
                threadCondition_.wait(
                    lock, [this]() { return fence_ == UNSIGNALLED; });
            }
            std::this_thread::sleep_for(waitTime_);
            {
                std::lock_guard<std::mutex> lock(fenceMutex_);
                fence_ = SIGNALLED;
            }
            fenceCondition_.notify_all();
        }
    }
    duration gpuWorkTime() { return waitTime_; }
    void setFenceTimeout(duration t) { fenceTimeout_ = t; }
};

using SwapEvents = std::vector<Event<duration>>;
struct Result {
    duration averageCpuTime;
    duration averageGpuTime;
    uint64_t numFrames;
    std::optional<SwapEvents> swapIntervalChangedEvents;
};

std::string DisplayDuration(duration d) {
    std::stringstream str;
    str << (std::chrono::duration_cast<std::chrono::microseconds>(d).count() /
            1000.0)
        << "ms";
    return str.str();
}

std::ostream& operator<<(std::ostream& o,
                         const std::vector<Event<duration>>& es) {
    bool first = true;
    for (auto& e : es) {
        if (first)
            first = false;
        else
            o << ", ";
        o << "{ value: " << DisplayDuration(e.value)
          << ", time: " << DisplayDuration(e.time_offset) << "}";
    }
    return o;
}
std::ostream& operator<<(std::ostream& o, const std::vector<Result>& rs) {
    bool first = true;
    for (auto& r : rs) {
        if (first)
            first = false;
        else
            o << ", ";
        o << "{ avg cpu/ms: " << DisplayDuration(r.averageCpuTime)
          << ", avg gpu/ms: " << DisplayDuration(r.averageGpuTime)
          << ", num frames: " << r.numFrames;
        if (r.swapIntervalChangedEvents)
            o << ", swap events: {" << *r.swapIntervalChangedEvents << "}";
        o << "}";
    }
    return o;
}

class Simulator : public ITimeProvider {
   public:
    template <typename T>
    using Schedule = std::vector<Event<T>>;
    using SwapIntervalChangedEvent = Event<duration>;

    Simulator(const SwappyCommonSettings& settings,
              const Schedule<Workload>& workloadSchedule,
              const Schedule<Parameters>& parametersSchedule)
        : commonBase_(new SwappyCommonTest(settings)),
          workloadSchedule_(workloadSchedule),
          parametersSchedule_(parametersSchedule),
          tracers_{preWaitTracer,
                   postWaitTracer,
                   preSwapBuffersTracer,
                   postSwapBuffersTracer,
                   startFrameTracer,
                   (void*)this,
                   swapIntervalChangedTracer},
          gpuThread_(this, commonBase_->getFenceTimeout()),
          cpuTimeSum_(0),
          gpuTimeSum_(0),
          cpuTimeCount_(0) {
        commonBase_->addTracerCallbacks(tracers_);
        commonBase_->removeTracerCallbacks(tracers_);
        commonBase_->addTracerCallbacks(tracers_);
    }
    void setParameters(const Parameters& p) {
        if (p.autoModeEnabled)
            commonBase_->setAutoSwapInterval(*p.autoModeEnabled);
        if (p.autoPipelineModeEnabled)
            commonBase_->setAutoPipelineMode(*p.autoPipelineModeEnabled);
        if (p.fenceTimeout) {
            // This needs to be propagated to the gpuThread
            commonBase_->setFenceTimeout(*p.fenceTimeout);
            gpuThread_.setFenceTimeout(*p.fenceTimeout);
        }
        if (p.maxAutoSwapInterval)
            commonBase_->setMaxAutoSwapDuration(*p.maxAutoSwapInterval);
        if (p.swapInterval)
            Settings::getInstance()->setSwapDuration(p.swapInterval->count());
    }
    void setWorkload(const Workload& w) {
        if (currentWorkload_) {
            recordResults();
        }
        resetResults();
        currentWorkload_ = w;
    }
    void run_for(duration run_time) {
        int parametersScheduleIndex = 0;
        int workloadScheduleIndex = 0;
        bool running = true;
        std::thread choreographer([this, &running]() {
            while (running) {
                auto t =
                    std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::steady_clock::now().time_since_epoch())
                        .count();
                commonBase_->onChoreographer(t);
                std::this_thread::sleep_for(16666666ns);
            }
        });
        mStartTime = std::chrono::steady_clock::now();
        currentWorkload_.reset();
        resetResults();
        firstWait = true;
        // Main game loop
        while (true) {
            // Check if we need to update parameters
            auto dt = timeFromStart();
            if (dt > run_time) break;
            if (parametersScheduleIndex < parametersSchedule_.size() &&
                dt >=
                    parametersSchedule_[parametersScheduleIndex].time_offset) {
                setParameters(
                    parametersSchedule_[parametersScheduleIndex].value);
                ++parametersScheduleIndex;
            }
            if (workloadScheduleIndex < workloadSchedule_.size() &&
                dt >= workloadSchedule_[workloadScheduleIndex].time_offset) {
                setWorkload(workloadSchedule_[workloadScheduleIndex].value);
                ++workloadScheduleIndex;
            }
            // Simulate CPU work
            std::this_thread::sleep_for(currentWorkload_->cpuWorkTime());
            // Swap
            swapInternal();
        }
        recordResults();
        ALOGI("Done: %s", resultsString().c_str());
        running =
            false;  // This avoids the choreographer ticking during the
                    // SwappyCommon destructor but we need fixes to make sure
                    // the choreographer filter shuts down correctly then.
        destroy();
        choreographer.join();
    }
    bool swapInternal() {
        const SwappyCommon::SwapHandlers handlers = {
            .lastFrameIsComplete = [&]() { return lastFrameIsComplete(); },
            .getPrevFrameGpuTime = [&]() { return getFencePendingTime(); },
        };

        commonBase_->onPreSwap(handlers);
        if (commonBase_->needToSetPresentationTime()) {
            bool setPresentationTimeResult = setPresentationTime();
            if (!setPresentationTimeResult) {
                return setPresentationTimeResult;
            }
        }
        resetSyncFence();

        // Actual call to swapBuffers would be here

        commonBase_->onPostSwap(handlers);

        return true;  // swapBuffers result
    }

    void destroy() { commonBase_.reset(); }

    time_point mStartTime;
    duration timeFromStart() const {
        return std::chrono::steady_clock::now() - mStartTime;
    }

    // GPU interaction logic

    bool lastFrameIsComplete() {
        // In OpenGL, this returns
        //   eglGetSyncAttribKHR(display, mSyncFence, EGL_SYNC_STATUS_KHR,
        //   &status);
        switch (gpuThread_.fenceStatus()) {
            case GpuThread::NO_SYNC:
                ALOGV("Last frame complete = true");
                return true;  // First time
            case GpuThread::SIGNALLED:
                ALOGV("Last frame complete = true");
                return true;
            case GpuThread::UNSIGNALLED:
                ALOGV("Last frame complete = false");
                return false;
        }
        return true;
    }
    void resetSyncFence() {
        gpuThread_.waitForFence();
        gpuThread_.createFence(currentWorkload_->gpuWorkTime());
    }
    std::chrono::nanoseconds getFencePendingTime() {
        // How long the fence was waiting
        return gpuThread_.gpuWorkTime();
    }
    bool setPresentationTime() {
        // In openGL, this calls
        //   eglPresentationTimeANDROID(display, surface,
        //   time.time_since_epoch().count());
        return true;
    }

    // Tracer functions
    void preWait() {}
    void postWait(duration cpuTime, duration gpuTime) {
        // Discard the first frame
        static duration prevTime;
        if (firstWait) {
            firstWait = false;
            prevTime = timeFromStart();
        } else {
            cpuTimeSum_ += cpuTime;
            gpuTimeSum_ += gpuTime;
            ++cpuTimeCount_;
        }
    }
    void preSwapBuffers() {}
    void postSwapBuffers(duration desiredPresentationTime) {}
    void startFrame(int currentFrame, duration desiredPresentationTime) {}
    void swapIntervalChanged() {
        auto newSwapInterval = commonBase_->getSwapDuration();
        swapIntervalChangedEvents.push_back(
            SwapIntervalChangedEvent{newSwapInterval, timeFromStart()});
    }

    // Static tracer callbacks
    static void preWaitTracer(void* userdata) {
        Simulator* sim = (Simulator*)userdata;
        sim->preWait();
    }
    static void postWaitTracer(void* userdata, int64_t cpuTime_ns,
                               int64_t gpuTime_ns) {
        Simulator* sim = (Simulator*)userdata;
        sim->postWait(std::chrono::nanoseconds(cpuTime_ns),
                      std::chrono::nanoseconds(gpuTime_ns));
    }
    static void preSwapBuffersTracer(void* userdata) {
        Simulator* sim = (Simulator*)userdata;
        sim->preSwapBuffers();
    }
    static void postSwapBuffersTracer(void* userdata,
                                      int64_t desiredPresentationTimeMillis) {
        Simulator* sim = (Simulator*)userdata;
        sim->postSwapBuffers(
            std::chrono::milliseconds(desiredPresentationTimeMillis));
    }
    static void startFrameTracer(void* userdata, int currentFrame,
                                 int64_t desiredPresentationTimeMillis) {
        Simulator* sim = (Simulator*)userdata;
        sim->startFrame(currentFrame, std::chrono::milliseconds(
                                          desiredPresentationTimeMillis));
    }
    static void swapIntervalChangedTracer(void* userdata) {
        Simulator* sim = (Simulator*)userdata;
        sim->swapIntervalChanged();
    }

    void recordResults() {
        results_.push_back({cpuTimeSum_ / cpuTimeCount_,
                            gpuTimeSum_ / cpuTimeCount_, cpuTimeCount_,
                            swapIntervalChangedEvents});
    }
    void resetResults() {
        cpuTimeCount_ = 0;
        cpuTimeSum_ = 0s;
        gpuTimeSum_ = 0s;
        swapIntervalChangedEvents.clear();
    }

    template <typename T>
    static bool check(T actual, T expected, T error) {
        return (actual <= expected + error && actual >= expected - error);
    }
    bool resultsCheck(const std::vector<Result>& expected,
                      const std::vector<Result>& error) const {
        auto& r = results_;
        if (r.size() != expected.size() || r.size() != error.size())
            return false;
        for (int i = 0; i < r.size(); ++i) {
            if (!check(r[i].averageCpuTime, expected[i].averageCpuTime,
                       error[i].averageCpuTime))
                return false;
            if (!check(r[i].averageGpuTime, expected[i].averageGpuTime,
                       error[i].averageGpuTime))
                return false;
            if (!check(r[i].numFrames, expected[i].numFrames,
                       error[i].numFrames))
                return false;
            if (expected[i].swapIntervalChangedEvents) {
                auto& exp = expected[i].swapIntervalChangedEvents;
                auto& err = error[i].swapIntervalChangedEvents;
                auto& act = r[i].swapIntervalChangedEvents;
                if (!act || act->size() != exp->size() ||
                    act->size() != err->size())
                    return false;
                for (int j = 0; j < act->size(); ++j) {
                    if (!check((*act)[j].value, (*exp)[j].value,
                               (*err)[j].value))
                        return false;
                    if (!check((*act)[j].time_offset, (*exp)[j].time_offset,
                               (*err)[j].time_offset))
                        return false;
                }
            }
        }
        return true;
    }
    std::string resultsString() const {
        std::stringstream str;
        str << results_;
        return str.str();
    }

   private:
    std::unique_ptr<SwappyCommonTest> commonBase_;
    Schedule<Workload> workloadSchedule_;
    std::optional<Workload> currentWorkload_;
    Schedule<Parameters> parametersSchedule_;
    SwappyTracer tracers_;
    GpuThread gpuThread_;
    duration cpuTimeSum_;
    duration gpuTimeSum_;
    uint64_t cpuTimeCount_;
    std::vector<Result> results_;
    std::vector<SwapIntervalChangedEvent> swapIntervalChangedEvents;
    bool firstWait = true;
};

}  // namespace swappycommon_test

using namespace swappycommon_test;

void SingleTest(const SwappyCommonSettings& settings,
                const Parameters& parameters, duration time,
                const Simulator::Schedule<Workload>& workload,
                const std::vector<Result>& expected,
                const std::vector<Result>& error) {
    Settings::getInstance()->reset();
    Simulator test(settings, workload, {Event<Parameters>{parameters, 0s}});
    test.run_for(time);
    EXPECT_TRUE(test.resultsCheck(expected, error))
        << "Bad test result" << test.resultsString() << " vs expected "
        << expected << " +- " << error;
};

SwappyCommonSettings base60HzSettings{
    {0, 0},      // SDK version
    16666667ns,  // refresh period
    0ns,         // app vsync offset
    0ns          // sf vsync offset
};

Parameters defaultParameters{};

Parameters autoModeOffParameters{
    false,      // auto-mode
    false,      // auto pipeline mode
    50ms,       // fence timeout
    50ms,       // maxAutoSwapInterval
    16666667ns  // swap interval
};

Parameters autoModeAndPipeliningOnParameters{
    true,       // auto-mode
    true,       // auto pipeline mode
    50ms,       // fence timeout
    50ms,       // maxAutoSwapInterval
    16666667ns  // swap interval
};
Parameters autoModeOnAutoPipeliningOffParameters{
    true,       // auto-mode
    false,      // auto pipeline mode
    50ms,       // fence timeout
    50ms,       // maxAutoSwapInterval
    16666667ns  // swap interval
};
Parameters autoModeAndPipeliningOn30HzParameters{
    true,       // auto-mode
    true,       // auto pipeline mode
    50ms,       // fence timeout
    50ms,       // maxAutoSwapInterval
    33333333ns  // swap interval
};
Parameters autoModeOnAutoPipeliningOff30HzParameters{
    true,       // auto-mode
    false,      // auto pipeline mode
    50ms,       // fence timeout
    50ms,       // maxAutoSwapInterval
    33333333ns  // swap interval
};

Workload lightWorkload{
    []() { return 10ms; },  // cpu
    []() { return 10ms; },  // gpu
};

Workload mediumCpuWorkload{
    []() { return 30ms; },  // cpu
    []() { return 10ms; },  // gpu
};

Workload mediumGpuWorkload{
    []() { return 10ms; },  // cpu
    []() { return 30ms; },  // gpu
};

Workload highWorkload{
    []() { return 40ms; },  // cpu
    []() { return 40ms; },  // gpu
};

// 10% of the time, frames are slow
Workload aboveThreshold10pc30HzWorkload() {
    static int count = 0;
    return {
        [&]() {
            if (++count == 10) {
                count = 0;
                return 50ms;
            } else
                return 30ms;
        },                      // cpu
        []() { return 10ms; },  // gpu
    };
}

// 10% of the time, frames are slow
Workload aboveThreshold10pc60HzWorkload() {
    static int count = 0;
    return {
        [&]() {
            if (++count == 10) {
                count = 0;
                return 20ms;
            } else
                return 10ms;
        },                      // cpu
        []() { return 10ms; },  // gpu
    };
}

Simulator::Schedule<Workload> loHiLo2sWorkload() {
    return {{lightWorkload, 0s}, {highWorkload, 2s}, {lightWorkload, 4s}};
};

TEST(SwappyCommonTest, DefaultParamsLightWorkload) {
    SingleTest(base60HzSettings, defaultParameters, 1s, {{lightWorkload, 0s}},
               {Result{10ms, 10ms, 60}}, {Result{1ms, 1ms, 2}});
}

TEST(SwappyCommonTest, DefaultParamsMedCpuWorkload) {
    SingleTest(base60HzSettings, defaultParameters, 1s,
               {{mediumCpuWorkload, 0s}}, {Result{30ms, 10ms, 33}},
               {Result{1ms, 1ms, 1}});
}

TEST(SwappyCommonTest, DefaultParamsMedGpuWorkload) {
    SingleTest(base60HzSettings, defaultParameters, 1s,
               {{mediumGpuWorkload, 0s}}, {Result{10ms, 30ms, 30}},
               {Result{1ms, 3ms, 4}});
}

TEST(SwappyCommonTest, DefaultParamsHighWorkload) {
    SingleTest(base60HzSettings, defaultParameters, 1s, {{highWorkload, 0s}},
               {Result{40ms, 40ms, 22}}, {Result{3ms, 3ms, 4}});
}

// Auto-mode off tests
TEST(SwappyCommonTest, AutoModeOffLightWorkload) {
    SingleTest(base60HzSettings, autoModeOffParameters, 1s,
               {{lightWorkload, 0s}}, {Result{10ms, 10ms, 60}},
               {Result{1ms, 1ms, 2}});
}
TEST(SwappyCommonTest, AutoModeOffMedCpuWorkload) {
    SingleTest(base60HzSettings, autoModeOffParameters, 1s,
               {{mediumCpuWorkload, 0s}}, {Result{30ms, 10ms, 33}},
               {Result{1ms, 1ms, 1}});
}
TEST(SwappyCommonTest, AutoModeOffMedGpuWorkload) {
    SingleTest(base60HzSettings, autoModeOffParameters, 1s,
               {{mediumGpuWorkload, 0s}}, {Result{10ms, 30ms, 30}},
               {Result{1ms, 3ms, 4}});
}

TEST(SwappyCommonTest, AutoModeOffHighWorkload) {
    SingleTest(base60HzSettings, autoModeOffParameters, 1s,
               {{highWorkload, 0s}}, {Result{40ms, 40ms, 21}},
               {Result{3ms, 3ms, 2}});
}

// Auto-mode on tests
TEST(SwappyCommonTest, AutoModeOnLightWorkload) {
    SingleTest(base60HzSettings, autoModeAndPipeliningOnParameters, 1s,
               {{lightWorkload, 0s}}, {Result{10ms, 10ms, 60}},
               {Result{1ms, 1ms, 1}});
}

TEST(SwappyCommonTest, AutoModeOnMedCpuWorkload) {
    SingleTest(base60HzSettings, autoModeAndPipeliningOnParameters, 1s,
               {{mediumCpuWorkload, 0s}},
               {Result{30ms, 10ms, 33}},  // Why is this frame count so high?
               {Result{1ms, 1ms, 1}});
}

TEST(SwappyCommonTest, AutoModeOnMedGpuWorkload) {
    SingleTest(base60HzSettings, autoModeAndPipeliningOnParameters, 1s,
               {{mediumGpuWorkload, 0s}}, {Result{10ms, 30ms, 30}},
               {Result{1ms, 3ms, 3}});
}
TEST(SwappyCommonTest, AutoModeOnHighWorkload) {
    SingleTest(base60HzSettings, autoModeAndPipeliningOnParameters, 1s,
               {{highWorkload, 0s}}, {Result{40ms, 40ms, 22}},
               {Result{3ms, 3ms, 2}});
}

// Check that we don't swap faster than the app-set swap interval
TEST(SwappyCommonTest, AutoModeOnLightWorkload30Hz) {
    SingleTest(base60HzSettings, autoModeAndPipeliningOn30HzParameters, 16s,
               {{lightWorkload, 0s},
                {lightWorkload, 4s},
                {lightWorkload, 8s},
                {lightWorkload, 12s}},
               {Result{10ms, 10ms, 120}, Result{10ms, 10ms, 120},
                Result{10ms, 10ms, 120}, Result{10ms, 10ms, 120}},
               {Result{1ms, 1ms, 2}, Result{1ms, 1ms, 2}, Result{1ms, 1ms, 2},
                Result{1ms, 1ms, 2}});
}

// Currently, we are getting frame counts 120, 46, 151
// It decides to swap slower then, because it thinks we are at the threshold,
// doesn't wait.
TEST(SwappyCommonTest, DISABLED_AutoModeOnVariableWorkload) {
    SingleTest(base60HzSettings, autoModeAndPipeliningOnParameters, 6s,
               loHiLo2sWorkload(),
               {Result{10ms, 10ms, 120}, Result{33ms, 33ms, 50},
                Result{10ms, 10ms, 120}},
               {Result{3ms, 3ms, 2}, Result{3ms, 3ms, 2}, Result{3ms, 3ms, 2}});
}

// Currently, we are getting frame counts 61, 26, 59 here.
// This is because pipelining is switched OFF after the first lo sequence.
TEST(SwappyCommonTest, DISABLED_AutoModeOnVariableWorkload30Hz) {
    SingleTest(base60HzSettings, autoModeAndPipeliningOn30HzParameters, 6s,
               loHiLo2sWorkload(),
               {Result{10ms, 10ms, 60}, Result{40ms, 40ms, 60},
                Result{10ms, 10ms, 60}},
               {Result{3ms, 3ms, 2}, Result{3ms, 3ms, 2}, Result{3ms, 3ms, 2}});
}

// Currently 61, 56, 145
// It decides to swap slower then, because it thinks we are at the threshold,
// doesn't wait.
TEST(SwappyCommonTest,
     DISABLED_AutoModeOnAutoPipeliningOffVariableWorkload30Hz) {
    SingleTest(base60HzSettings, autoModeOnAutoPipeliningOff30HzParameters, 6s,
               loHiLo2sWorkload(),
               {Result{10ms, 10ms, 60}, Result{33ms, 33ms, 60},
                Result{10ms, 10ms, 60}},
               {Result{3ms, 3ms, 2}, Result{3ms, 3ms, 2}, Result{3ms, 3ms, 2}});
}

TEST(SwappyCommonTest,
     AutoModeOnAutoPipeliningOffWorkloadJustBelowThreshold30Hz) {
    SingleTest(base60HzSettings, autoModeOnAutoPipeliningOff30HzParameters, 10s,
               {{mediumCpuWorkload, 0s}}, {Result{30ms, 10ms, 300}},
               {Result{1ms, 1ms, 2}});
}

TEST(SwappyCommonTest,
     AutoModeOnAutoPipeliningOffWorkloadAboveThreshold10pcOfTime30Hz) {
    SingleTest(base60HzSettings, autoModeOnAutoPipeliningOff30HzParameters, 10s,
               {{aboveThreshold10pc30HzWorkload(), 0s}},
               {Result{32ms, 10ms, 286}}, {Result{1ms, 1ms, 5}});
}

TEST(SwappyCommonTest, AutoModeOnAutoPipeliningOffSwitchDownFrom60Hz) {
    SingleTest(base60HzSettings, autoModeOnAutoPipeliningOffParameters, 5s,
               {{mediumCpuWorkload, 0s}},
               {Result{30ms, 10ms, 155, SwapEvents{{33333us, 2s}}}},
               {Result{1ms, 1ms, 5, SwapEvents{{1ms, 100ms}}}});
}
TEST(SwappyCommonTest, AutoModeOnAutoPipeliningOffSwitchDownFrom60HzAndBack) {
    SingleTest(base60HzSettings, autoModeOnAutoPipeliningOffParameters, 7s,
               {{mediumCpuWorkload, 0s}, {lightWorkload, 3s}},
               {Result{30ms, 10ms, 95, SwapEvents{{33333us, 2s}}},
                Result{10ms, 10ms, 190, SwapEvents{{16667us, 4600ms}}}},
               {Result{1ms, 1ms, 3, SwapEvents{{1ms, 100ms}}},
                Result{1ms, 1ms, 3, SwapEvents{{1ms, 200ms}}}});
}