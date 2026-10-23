package com.sbro.emucoreh.data

import com.sbro.emucoreh.core.EmulationPlatform

/** The existing editor IDs remain stable; only their hardware labels and routing vary. */
object SegaTouchLayouts {
    private val directions = linkedMapOf("dpad_up" to 19, "dpad_down" to 20, "dpad_left" to 21, "dpad_right" to 22)
    fun isSixButton(platform: EmulationPlatform): Boolean = platform in setOf(
        EmulationPlatform.GENESIS, EmulationPlatform.SEGA_CD, EmulationPlatform.SEGA_32X)

    fun controlIds(platform: EmulationPlatform): Set<String> = buildSet {
        if (!platform.isSega) return@buildSet
        addAll(directions.keys)
        addAll(listOf("a", "b"))
        if (isSixButton(platform)) addAll(listOf("x", "y", "l1", "r1", "select"))
        if (platform != EmulationPlatform.SG_1000) add("start")
    }

    fun defaultProfile(platform: EmulationPlatform): TouchControlsLayoutProfile =
        if (!platform.isSega) TouchControlsLayoutProfile() else
            TouchControlsLayoutProfile(controlLayouts = controlIds(platform).associateWith { OverlayControlLayout() })

    fun canonicalId(id: String): String = when (id) {
        "cross" -> "a"; "circle" -> "b"; "square" -> "x"; "triangle" -> "y"
        "up" -> "dpad_up"; "down" -> "dpad_down"; "left" -> "dpad_left"; "right" -> "dpad_right"
        else -> id
    }

    fun keyCode(platform: EmulationPlatform, id: String): Int? {
        val canonical = canonicalId(id)
        if (canonical == "fast_forward" && platform.isSega) return 126
        if (canonical !in controlIds(platform)) return null
        return directions[canonical] ?: when (canonical) {
            "a" -> 96; "b" -> 97; "x" -> 99; "y" -> 100
            "l1" -> 102; "r1" -> 103; "select" -> 109; "start" -> 108
            else -> null
        }
    }

    fun label(platform: EmulationPlatform, id: String): String? {
        val canonical = canonicalId(id)
        if (canonical !in controlIds(platform)) return null
        return when (canonical) {
            "a" -> if (isSixButton(platform)) "B" else "1"
            "b" -> if (isSixButton(platform)) "C" else "2"
            "x" -> "A"; "y" -> "Y"; "l1" -> "X"; "r1" -> "Z"
            "select" -> "Mode"
            "start" -> if (platform == EmulationPlatform.MASTER_SYSTEM) "Pause" else "Start"
            else -> null
        }
    }

    fun supportedActions(platform: EmulationPlatform): Set<String> =
        CustomTouchControl.ALLOWED_ACTION_IDS.filterTo(linkedSetOf()) { keyCode(platform, it) != null }

    fun sanitizeCustomControls(platform: EmulationPlatform, library: CustomTouchControlLibrary): CustomTouchControlLibrary =
        library.sanitized().copy(controls = library.sanitized().controls.filter { keyCode(platform, it.actionId) != null }
            .map { it.copy(secondaryActionId = it.secondaryActionId?.takeIf { action -> keyCode(platform, action) != null }) })
}
