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

    /** Model 3 exposes only OpenGL in the app; its native Vulkan backend remains available for development. */
    const val MODEL3_DEFAULT = OPENGL

    // Stable C API renderer values. Keep this translation at the frontend
    // boundary: Android's 12/13/14 values come from the inherited UI model and
    // must never leak into the native core contract.
    const val CORE_SOFTWARE = 0
    const val CORE_VULKAN = 1
    const val CORE_OPENGL = 2

    /** Select a stable default while allowing either hardware renderer. */
    fun defaultForHardware(): Int = DEFAULT

    fun normalizeAndroidRenderer(value: Int): Int {
        return when (value) {
            OPENGL, SOFTWARE, VULKAN -> value
            else -> defaultForHardware()
        }
    }

    /** Ignore legacy/backup Vulkan selections while the Model 3 UI is OpenGL-only. */
    fun normalizeModel3Renderer(value: Int?): Int =
        value?.takeIf { it == OPENGL } ?: MODEL3_DEFAULT

    fun toCoreRenderer(value: Int): Int = when (normalizeAndroidRenderer(value)) {
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

    /**
     * Android value (12/13/14) of a core renderer. State that has to remember
     * which backend a running session actually presents on — the renderer to
     * fall back to when a live switch fails — uses this instead of the user
     * preference, which already holds the renderer that failed.
     */
    fun androidRendererForCore(value: Int): Int = when (value) {
        CORE_VULKAN -> VULKAN
        CORE_OPENGL -> OPENGL
        else -> SOFTWARE
    }

    /** Name for a stored UI preference value (12/13/14) used in crash reports. */
    fun androidRendererName(value: Int): String = when (normalizeAndroidRenderer(value)) {
        SOFTWARE -> "Software"
        VULKAN -> "Vulkan"
        else -> "OpenGL"
    }
}
