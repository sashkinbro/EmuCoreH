package com.sbro.emucoreh.ui.emulation

internal data class TouchButtonTarget(
    val id: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val tapToHold: Boolean = false,
    val haptics: Boolean = true
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom
}

/** Runtime button ownership, independent of Compose and native emulator input. */
internal class TouchButtonDispatcher(
    private val targets: List<TouchButtonTarget>,
    private val onPressChange: (String, Boolean) -> Unit,
    private val onClick: (String) -> Unit = {},
    private val onHaptic: (Boolean) -> Unit = {},
    private val onStateChanged: (Set<String>) -> Unit = {}
) {
    private val targetById = targets.associateBy { it.id }
    private val activeTargets = mutableMapOf<Long, Set<String>>()
    private val downTargets = mutableMapOf<Long, Set<String>>()
    private val latchedTargets = mutableSetOf<String>()

    private fun isActive(id: String): Boolean = activeTargets.values.any { id in it }

    private fun notifyStateChanged() {
        onStateChanged(activeTargets.values.flatten().toSet() + latchedTargets)
    }

    private fun hitTargets(x: Float, y: Float): Set<String> =
        targets.filter { it.contains(x, y) }.mapTo(linkedSetOf()) { it.id }

    fun pointerDown(pointerId: Long, x: Float, y: Float): Boolean {
        val hits = hitTargets(x, y)
        if (hits.isEmpty()) return false
        downTargets[pointerId] = hits
        updatePointerTargets(pointerId, hits)
        return true
    }

    fun pointerMove(pointerId: Long, x: Float, y: Float): Boolean {
        if (pointerId !in downTargets) return false
        updatePointerTargets(pointerId, hitTargets(x, y))
        return true
    }

    fun pointerUp(pointerId: Long, x: Float, y: Float): Boolean {
        val downHits = downTargets.remove(pointerId) ?: return false
        val upHits = hitTargets(x, y)
        updatePointerTargets(pointerId, emptySet())
        (downHits intersect upHits).forEach { id ->
            if (targetById.getValue(id).tapToHold) {
                val pressed = if (latchedTargets.remove(id)) false else {
                    latchedTargets.add(id)
                    true
                }
                onPressChange(id, pressed)
                notifyStateChanged()
            } else {
                onClick(id)
            }
        }
        return true
    }

    private fun updatePointerTargets(pointerId: Long, next: Set<String>, emitReleaseHaptic: Boolean = true) {
        val previous = activeTargets[pointerId].orEmpty()
        if (previous == next) return
        // Compare aggregate membership before and after updating this finger. A
        // second finger holding the same button must prevent a premature release.
        val newlyPressed = (next - previous).filterNot(::isActive)
        if (next.isEmpty()) activeTargets.remove(pointerId) else activeTargets[pointerId] = next
        val released = (previous - next).filterNot(::isActive)
        released.forEach { id ->
            if (!targetById.getValue(id).tapToHold) onPressChange(id, false)
        }
        newlyPressed.forEach { id ->
            if (!targetById.getValue(id).tapToHold) onPressChange(id, true)
        }
        if (newlyPressed.any { targetById.getValue(it).haptics }) {
            onHaptic(true)
        } else if (next.isEmpty() && emitReleaseHaptic && released.any { targetById.getValue(it).haptics }) {
            onHaptic(false)
        }
        notifyStateChanged()
    }

    fun cancelPointers() {
        activeTargets.keys.toList().forEach { updatePointerTargets(it, emptySet(), emitReleaseHaptic = false) }
        downTargets.clear()
    }

    fun reset() {
        cancelPointers()
        latchedTargets.forEach { onPressChange(it, false) }
        latchedTargets.clear()
        notifyStateChanged()
    }
}
