package com.sbro.emucoreh.data

internal object BundledCoreMetadata {
    /** Historical backup compatibility token, independent of informational core build labels. */
    const val BACKUP_COMPATIBILITY_VERSION = "1.0.0"
    // The public/cloud metadata field is limited to 40 characters; source revisions are in About.
    const val DISPLAY_VERSION = "Flycast/jgenesis/Super3/YabaSanshiro"
}
