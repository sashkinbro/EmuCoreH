package com.sbro.emucoreh.ui.home

import com.sbro.emucoreh.data.LibraryPlatform

/** An empty selection represents All; platform chips independently add or remove membership. */
internal fun toggleHomePlatformFilter(
    selected: Set<LibraryPlatform>,
    platform: LibraryPlatform?
): Set<LibraryPlatform> = when {
    platform == null -> emptySet()
    platform in selected -> selected - platform
    else -> selected + platform
}

internal fun pruneHomePlatformFilters(
    selected: Set<LibraryPlatform>,
    available: Set<LibraryPlatform>
): Set<LibraryPlatform> = selected.intersect(available)

internal fun matchesHomePlatformFilters(
    selected: Set<LibraryPlatform>,
    platform: LibraryPlatform
): Boolean = selected.isEmpty() || platform in selected
