package com.sbro.emucoreh.data

import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.GameMetadata

/** Platform detection is separate: listing a disc must not scrape its metadata. */
internal fun readLibraryScanMetadata(
    platform: EmulationPlatform,
    inferFromName: () -> GameMetadata,
    readPlatformMetadata: () -> GameMetadata,
    learnedSerial: () -> String?
): GameMetadata {
    val metadata = if (platform.supportsDisc) inferFromName() else readPlatformMetadata()
    return if (metadata.serial.isNullOrBlank()) {
        learnedSerial()?.let { metadata.copy(serial = it) } ?: metadata
    } else metadata
}
