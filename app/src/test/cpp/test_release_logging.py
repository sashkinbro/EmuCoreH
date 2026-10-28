"""Compile the actual frontend/Flycast logging policies in both configurations."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[4]
compiler = shutil.which("g++") or "C:/msys64/ucrt64/bin/g++.exe"
env = dict(os.environ)
env["PATH"] = str(Path(compiler).parent) + os.pathsep + env.get("PATH", "")
with tempfile.TemporaryDirectory(prefix="emucoreh-release-logs-") as directory:
    work = Path(directory)
    (work / "android").mkdir()
    (work / "android/log.h").write_text(
        "#pragma once\n#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n"
        "#define ANDROID_LOG_ERROR 6\nint __android_log_print(int,const char*,const char*,...);\n"
    )
    for release in (False, True):
        binary = work / ("release.exe" if release else "debug.exe")
        subprocess.run([
            compiler, "-std=c++17", "-O2", "-DLIBRETRO",
            *(["-DNDEBUG"] if release else []),
            "-I" + str(work), "-I" + str(root / "app/src/main/cpp"),
            "-I" + str(root / "core/core"),
            str(root / "app/src/test/cpp/release_logging_test.cpp"), "-o", str(binary)
        ], check=True, env=env)
        subprocess.run([str(binary)], check=True, env=env)
        print("PASS", "release: routine diagnostics removed, failures and functional game IDs retained" if release
              else "debug: diagnostics and game IDs retained")

    # Compile the real release crash reporter: it must not install handlers or
    # create a report even if a native caller invokes it directly.
    crash_main = work / "crash_main.cpp"
    crash_main.write_text('#include "crash_reporter.h"\nint main(int argc,char** argv) { '
                          'emucoreh::crash::InstallCrashReporter(argv[1]); '
                          'emucoreh::crash::LogNativeError("release sentinel"); }\n')
    crash_binary = work / "crash_release.exe"
    subprocess.run([compiler, "-std=c++17", "-O2", "-DNDEBUG",
                    "-I" + str(root / "app/src/main/cpp"), str(crash_main),
                    str(root / "app/src/main/cpp/crash_reporter.cpp"),
                    "-o", str(crash_binary)], check=True, env=env)
    report = work / "must-not-exist.log"
    subprocess.run([str(crash_binary), str(report)], check=True, env=env)
    assert not report.exists(), "Release native reporter wrote a crash file"
    print("PASS release: actual native crash reporter creates no file")
