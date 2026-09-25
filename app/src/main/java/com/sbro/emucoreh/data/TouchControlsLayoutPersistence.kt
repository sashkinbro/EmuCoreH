package com.sbro.emucoreh.data

const val PER_GAME_TOUCH_CONTROLS_LAYOUT_KEY = "touchControlsLayout"
const val PER_GAME_CUSTOM_TOUCH_CONTROLS_KEY = "customTouchControls"

fun OverlayLayoutSnapshot.toTouchControlsLayoutProfile(): TouchControlsLayoutProfile {
    return TouchControlsLayoutProfile(
        dpadOffset = dpadOffset,
        lstickOffset = lstickOffset,
        rstickOffset = rstickOffset,
        actionOffset = actionOffset,
        lbtnOffset = lbtnOffset,
        rbtnOffset = rbtnOffset,
        centerOffset = centerOffset,
        stickScale = stickScale,
        controlLayouts = controlLayouts
    )
}

suspend fun AppPreferences.saveTouchControlsLayout(profile: TouchControlsLayoutProfile) {
    setControlsLayout(
        dpadX = profile.dpadOffset.first,
        dpadY = profile.dpadOffset.second,
        lstickX = profile.lstickOffset.first,
        lstickY = profile.lstickOffset.second,
        rstickX = profile.rstickOffset.first,
        rstickY = profile.rstickOffset.second,
        actionX = profile.actionOffset.first,
        actionY = profile.actionOffset.second,
        lbtnX = profile.lbtnOffset.first,
        lbtnY = profile.lbtnOffset.second,
        rbtnX = profile.rbtnOffset.first,
        rbtnY = profile.rbtnOffset.second,
        centerX = profile.centerOffset.first,
        centerY = profile.centerOffset.second,
        stickScaleVal = profile.stickScale,
        controlLayouts = profile.controlLayouts
    )
}

fun PerGameSettings?.withTouchControlsLayout(
    gameKey: String,
    gameTitle: String,
    gameSerial: String?,
    layout: TouchControlsLayoutProfile
): PerGameSettings {
    val existing = this
    val providedKeys = when {
        existing == null -> setOf(PER_GAME_TOUCH_CONTROLS_LAYOUT_KEY)
        existing.providedKeys == null -> null
        else -> existing.providedKeys + PER_GAME_TOUCH_CONTROLS_LAYOUT_KEY
    }
    return (existing ?: PerGameSettings(
        gameKey = gameKey,
        gameTitle = gameTitle,
        gameSerial = gameSerial,
        providedKeys = providedKeys
    )).copy(
        gameTitle = gameTitle,
        gameSerial = gameSerial ?: existing?.gameSerial,
        touchControlsLayout = layout,
        providedKeys = providedKeys
    )
}

fun PerGameSettings?.withCustomTouchControls(
    gameKey: String,
    gameTitle: String,
    gameSerial: String?,
    library: CustomTouchControlLibrary
): PerGameSettings {
    val existing = this
    val providedKeys = when {
        existing == null -> setOf(PER_GAME_CUSTOM_TOUCH_CONTROLS_KEY)
        existing.providedKeys == null -> null
        else -> existing.providedKeys + PER_GAME_CUSTOM_TOUCH_CONTROLS_KEY
    }
    return (existing ?: PerGameSettings(
        gameKey = gameKey,
        gameTitle = gameTitle,
        gameSerial = gameSerial,
        providedKeys = providedKeys
    )).copy(
        gameTitle = gameTitle,
        gameSerial = gameSerial ?: existing?.gameSerial,
        customTouchControls = library.sanitized(),
        providedKeys = providedKeys
    )
}

fun PerGameSettings.withoutTouchControlsLayout(): PerGameSettings? {
    val remainingKeys = providedKeys
        ?.minus(PER_GAME_TOUCH_CONTROLS_LAYOUT_KEY)
        ?.minus(PER_GAME_CUSTOM_TOUCH_CONTROLS_KEY)
    if (remainingKeys != null && remainingKeys.isEmpty()) return null
    return copy(
        touchControlsLayout = null,
        customTouchControls = null,
        providedKeys = remainingKeys
    )
}
