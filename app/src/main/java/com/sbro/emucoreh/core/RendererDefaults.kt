package com.sbro.emucoreh.core

object RendererDefaults {
    const val AUTO = -1
    const val OPENGL = 12
    const val SOFTWARE = 13
    const val VULKAN = 14

    // Flycast's libretro Vulkan context runs through the frontend's
    // negotiation interface, which is not stable on every Android driver yet.
    // OpenGL ES is the reliable default; Vulkan stays selectable per game.
    const val DEFAULT = VULKAN

    // Stable C API renderer values. Keep this translation at the frontend
    // boundary: Android's 12/13/14 values come from the inherited UI model and
    // must never leak into the native core contract.
    const val CORE_SOFTWARE = 0
    const val CORE_VULKAN = 1
    const val CORE_OPENGL = 2

    /** Select a stable default while allowing either hardware renderer. */
    fun defaultForHardware(
        @Suppress("UNUSED_PARAMETER")
        isMediaTekHardware: Boolean = GpuHardwareProfiles.isMediaTekHardware()
    ): Int = DEFAULT

    fun normalizeAndroidRenderer(
        value: Int,
        isMediaTekHardware: Boolean = GpuHardwareProfiles.isMediaTekHardware()
    ): Int {
        return when (value) {
            OPENGL, SOFTWARE, VULKAN -> value
            else -> defaultForHardware(isMediaTekHardware)
        }
    }

    fun toCoreRenderer(
        value: Int,
        isMediaTekHardware: Boolean = GpuHardwareProfiles.isMediaTekHardware()
    ): Int = when (normalizeAndroidRenderer(value, isMediaTekHardware)) {
        SOFTWARE -> CORE_SOFTWARE
        VULKAN -> CORE_VULKAN
        else -> CORE_OPENGL
    }

    fun coreRendererName(value: Int): String = when (value) {
        CORE_SOFTWARE -> "Software"
        CORE_VULKAN -> "Vulkan"
        CORE_OPENGL -> "OpenGL"
        else -> "Unknown($value)"
    }
}
