# GPU color regression probes

These isolated Android executables use the pinned production librashader binary and an identity preset. They do not launch the application or write game data.

- `gl-test.cpp` links `shader_chain_present.cpp` and `shader_chain.cpp` from the app. It uses an EGL ES3 pbuffer and checks RGB across three frames, including a changed source color with an inherited partial color mask.
- `vk-test.cpp` creates a BGRA source image and proves the old RGBA declaration swaps red/blue. The actual BGRA declaration must preserve RGB. The mutable-format flag is only for reproducing the old invalid interpretation in the test.
- Readbacks and queue waits exist only in these test executables, never in the app rendering fix.

Build with the Android NDK C++17 compiler, the generated librashader include directory and `liblibrashader_capi.so`. GL requires `EMUCOREA_HAVE_LIBRASHADER`, `LIBRA_RUNTIME_OPENGL`, `-lEGL -lGLESv3 -llog`; Vulkan requires `LIBRA_RUNTIME_VULKAN`, `-lvulkan -llog`. Use shared libc++ to match librashader. Place the executable, preset, shader, libc++_shared.so and librashader library in an isolated test directory, then run with `LD_LIBRARY_PATH=.` and `identity.slangp` as the argument.

Observed on 2026-09-20 before the request to stop device testing:

```
GL: (204,102,51), (51,204,51), (204,102,51), alpha=255, GL error=0
Vulkan old RGBA: (51,102,204)
Vulkan actual BGRA: (204,102,51)
```

These prove color transport for an identity shader, not correctness of every third-party preset or frame-rate benchmarks.
