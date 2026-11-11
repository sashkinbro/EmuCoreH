#pragma once
#include <exception>
#include <mutex>
#include <utility>

// Generated CPU code has no C++ unwind tables. Preserve failures inside its
// callbacks and rethrow only after returning to an ordinary C++ stack frame.
class DeferredException {
public:
    template<class Callback> bool run(Callback&& callback) noexcept {
        try {
            std::forward<Callback>(callback)();
            return true;
        } catch (...) {
            std::lock_guard<std::mutex> lock(mutex);
            if (!failure) failure = std::current_exception();
            return false;
        }
    }

    void rethrow() {
        std::exception_ptr pending;
        {
            std::lock_guard<std::mutex> lock(mutex);
            std::swap(pending, failure);
        }
        if (pending) std::rethrow_exception(pending);
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex);
        failure = nullptr;
    }

private:
    std::mutex mutex;
    std::exception_ptr failure;
};
