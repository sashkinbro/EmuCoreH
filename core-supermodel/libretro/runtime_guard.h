// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <exception>
namespace model3retro {
class RuntimeGuard {
public:
  void Reset() { failed_ = false; }
  bool Failed() const { return failed_; }
  template<typename Initialize, typename Frame, typename Report>
  void Run(bool available, Initialize initialize, Frame frame, Report report) {
    if (failed_ || !available) return;
    try {
      if (const char* reason = initialize()) {
        failed_ = true;
        report(reason);
        return;
      }
      frame();
    } catch (const std::exception& error) {
      if (!failed_) {
        failed_ = true;
        report(error.what());
      }
    }
  }
private:
  bool failed_ = false;
};
}
