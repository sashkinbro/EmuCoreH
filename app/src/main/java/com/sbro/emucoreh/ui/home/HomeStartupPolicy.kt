package com.sbro.emucoreh.ui.home

/** SAF cache publication needs a retained read grant, but never a recursive provider query. */
internal fun canPublishHomeLibraryCache(paths: List<String>, persistedReadGrants: Set<String>): Boolean =
    paths.isNotEmpty() && paths.all { !it.startsWith("content://") || it in persistedReadGrants }
