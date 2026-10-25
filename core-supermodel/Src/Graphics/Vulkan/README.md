# Real3D Vulkan backend

This is an opt-in backend (`MODEL3_ENABLE_EXPERIMENTAL_VULKAN=ON`, default OFF).
The libretro frontend selects it through `GET_PREFERRED_HW_RENDER`; GLES remains
available in the same core. The core borrows the frontend Vulkan device and
graphics queue through `GET_HW_RENDER_INTERFACE`.

`CNew3D` retains the existing CPU display-list traversal and produces a borrowed
`SceneFrame`. Vulkan consumes the actual triangle meshes, texture RAM, material,
viewport and lighting state. TileGen remains a native 496 by 384 CPU surface,
uploaded as bottom/top layers around the GPU 3D passes. There is no CPU 3D blit.

The renderer ports the existing full Real3D GLSL shader, decodes all twelve
texture formats, and implements mipmaps, microtextures, independent transparency
layers, priority/overlay depth, stencil, fog, scroll fog and guest LOS samples.
`compile_shaders.py` generates Vulkan GLSL and SPIR-V from the checked-in GLES
shader on every source change. Generated binaries/header stay in the build
directory. CMake requires Python and the NDK `glslc`; no opaque shader blob is
checked in. Source is GPL-3.0-or-later; the libretro Vulkan ABI header retains its
upstream MIT license.

## Synchronization and ownership

Submission uses the frontend graphics queue under its lock/unlock callbacks.
At frame start and destruction, a locked queue-idle wait retires frontend reads
of the single output image. Each core submission also waits for its own fence.
The persistent `retro_vulkan_image` is published in shader-read-only layout with
zero wait semaphores and ignored queue-family transfer. This conservative route
does not depend on frontend command-buffer or semaphore callback support.

This is deliberately synchronous. Replacing the queue-idle wait requires output
images/resources per frontend sync index and correct retirement; an individual
core fence does not retire frontend reads. Do not remove it as a performance
cleanup. Readback/LOS includes transfer-to-host visibility barriers.

## Verification

Configure the standalone Android CMake project with
`MODEL3_BUILD_LIFECYCLE_TESTS=ON`. Useful targets:

- `model3_scene_consumer_lifecycle`: scene extraction with GL calls rejected.
- `model3_vulkan_scene_lifecycle`: real GPU pixel checks, allocation-failure
  cleanup, and host visibility checks. `--validation` requires an actual Khronos
  validation layer and fails on errors; it does not silently skip the layer.
- `model3_vulkan_core_lifecycle`: owner-supplied retail ROM/system directory,
  nonzero PCM, 1/2/4x resize, save/load and context recreation. Optional baseline
  state input supports quick same-state comparisons.
- `model3_gles_state_compare`: fresh GLES renderer, restore the same CPU state,
  one successor frame. `MODEL3_TEST_ACCURATE_GLES=ON` selects the accurate GLES
  reference in a QA-only core. It reports GL errors separately.
- `model3_graphics_lifecycle`: independent GLES transparency attachments and
  shader resource recreation. GLES draw output i must target attachment i;
  layer selection physically reattaches the corresponding texture.

The separate `model3_vulkan_validation` NativeActivity library can be packaged
with the official Khronos Android layer in a debuggable QA APK. It runs the
synthetic pixel suite with validation and writes `files/vulkan-validation.txt`.
This avoids device-global layer settings. The normal application is unaffected.
For the retail adapter suite, copy owner content into the QA package's files
directory, retaining the canonical ROM-set basename (for example `srally2.zip`).
Put that basename in `retail.enabled`, the matching exported baseline in
`state.rstate`, and ROM definitions in `system/supermodel/Games.xml`.

Observed on the Anbernic test device: synthetic pixel/resource suite passes
with Khronos validation enabled and zero errors. Sega Rally 2 reaches actual
car/track gameplay and nonzero PCM at 1800 frames, and passes state/context and
1/2/4x lifecycle checks. The same-state accurate GLES successor matches Vulkan
within approximately 0.02/255 mean absolute error per color channel. This is
evidence for that scene and device, not a claim that every Model 3 game or all
hardware is verified. The restored retail scene also passes Khronos validation
with zero errors across successor frames, 1/2/4x resize, save/load and context
recreation. Frontend integration, broader
game coverage, and asynchronous performance remain separate gates. This backend
does not add a PowerPC JIT.
