package com.sbro.emucoreh.ui.emulation

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import com.sbro.emucoreh.core.NativeApp

/** Observes the final pass so overlay buttons and sticks have first use of a press. */
internal fun Modifier.lightGunPointerInput(
    enabled: Boolean,
    port: Int,
    onAim: (Offset) -> Boolean,
    onButton: (Int, Int, Boolean) -> Unit
): Modifier = pointerInput(enabled, port) {
    if (!enabled) return@pointerInput
    val touchPointers = mutableSetOf<Long>()
    var triggerHeld = false
    var reloadHeld = false
    var mouseOwnsTrigger = false
    var mouseOwnsReload = false
    var previousMouseTrigger = false
    var previousMouseReload = false
    fun publishButtons(trigger: Boolean, reload: Boolean) {
        if (trigger != triggerHeld) {
            onButton(port, NativeApp.PAD_GUN_TRIGGER, trigger)
            triggerHeld = trigger
        }
        if (reload != reloadHeld) {
            onButton(port, NativeApp.PAD_GUN_RELOAD, reload)
            reloadHeld = reload
        }
    }
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                var mouseTrigger = false
                var mouseReload = false
                var sawMouse = false
                event.changes.forEach { change ->
                    val id = change.id.value
                    if (change.type == PointerType.Mouse) {
                        sawMouse = true
                        val primary = event.buttons.isPrimaryPressed
                        val secondary = event.buttons.isSecondaryPressed
                        val hasAim = !change.isConsumed && onAim(change.position)
                        if (!primary) mouseOwnsTrigger = false
                        else if (!previousMouseTrigger) mouseOwnsTrigger = hasAim
                        if (!secondary) mouseOwnsReload = false
                        else if (!previousMouseReload) mouseOwnsReload = hasAim
                        mouseTrigger = primary && mouseOwnsTrigger
                        mouseReload = secondary && mouseOwnsReload
                    } else if (change.isConsumed) {
                        touchPointers.remove(id)
                    } else if (change.pressed && !change.previousPressed) {
                        if (onAim(change.position)) touchPointers.add(id)
                    } else if (id in touchPointers) {
                        onAim(change.position)
                        if (!change.pressed) touchPointers.remove(id)
                    }
                }
                if (sawMouse) {
                    previousMouseTrigger = event.buttons.isPrimaryPressed
                    previousMouseReload = event.buttons.isSecondaryPressed
                }
                publishButtons(touchPointers.isNotEmpty() || mouseTrigger, mouseReload)
            }
        }
    } finally {
        publishButtons(false, false)
    }
}
