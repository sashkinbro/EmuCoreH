package com.sbro.emucoreh.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine

/** Configuration can change while the retained Home owner keeps the same preference snapshot. */
internal fun <T> coverSettingsOnConfiguration(
    snapshots: Flow<T>,
    uiModes: Flow<Int>,
    resolve: (T) -> CoverDisplaySettings
): Flow<CoverDisplaySettings> = combine(snapshots, uiModes.distinctUntilChanged()) { snapshot, _ ->
    resolve(snapshot)
}.distinctUntilChanged()
