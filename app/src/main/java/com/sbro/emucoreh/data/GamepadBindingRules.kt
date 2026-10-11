package com.sbro.emucoreh.data

object GamepadBindingRules {
    private val arcadeActions = setOf("coin", "test", "service", "gun_trigger", "gun_pedal", "gun_reload", "gun_recalibrate")
    fun isArcadeAction(actionId: String): Boolean = actionId in arcadeActions

    /** Arcade and ordinary controls may share a physical button across their contexts. */
    fun assign(bindings: Map<String, Int>, actionId: String, keyCode: Int): Map<String, Int> {
        val updated = bindings.toMutableMap()
        updated.entries.removeAll { (existingAction, existingKey) ->
            existingKey == keyCode && isArcadeAction(existingAction) == isArcadeAction(actionId)
        }
        updated[actionId] = keyCode
        return updated
    }

    fun merge(base: Map<Int, Map<String, Int>>, overrides: Map<Int, Map<String, Int>>): Map<Int, Map<String, Int>> {
        val result = base.toMutableMap()
        overrides.forEach { (padIndex, padOverrides) ->
            var bindings = result[padIndex].orEmpty()
            padOverrides.forEach { (actionId, keyCode) -> bindings = assign(bindings, actionId, keyCode) }
            result[padIndex] = bindings
        }
        return result
    }
}
