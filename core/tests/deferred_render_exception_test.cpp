#include "util/deferred_exception.h"
#include <cassert>
#include <stdexcept>
#include <string>

int main() {
    DeferredException failures;
    bool stopped = false;
    // Models a generated CPU callback: unwinding across this boundary aborts.
    const auto jitCallback = [&]() noexcept {
        if (!failures.run([] { throw std::runtime_error("GPU allocation failed"); })) stopped = true;
    };
    jitCallback();
    assert(stopped);
    try {
        failures.rethrow();
        assert(false);
    } catch (const std::runtime_error& error) {
        assert(std::string(error.what()) == "GPU allocation failed");
    }
    failures.rethrow(); // Delivered once, not replayed in the following frame.
    assert(failures.run([] {}));
    assert(!failures.run([] { throw 42; }));
    try { failures.rethrow(); assert(false); } catch (int value) { assert(value == 42); }
    assert(!failures.run([] { throw std::runtime_error("old session"); }));
    failures.reset();
    failures.rethrow();
}
