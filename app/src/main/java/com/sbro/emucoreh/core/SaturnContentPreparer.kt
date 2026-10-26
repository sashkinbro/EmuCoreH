package com.sbro.emucoreh.core

import android.content.Context

/** The bounded CUE/playlist materializer is shared; firmware remains in Saturn's own system directory. */
object SaturnContentPreparer {
    fun prepare(context: Context, path: String): String = JGenesisContentPreparer.prepare(context, path)
    fun releasePreparedContent(context: Context): Boolean = JGenesisContentPreparer.releasePreparedContent(context)
    fun cleanupAbandonedContent(context: Context): Boolean = JGenesisContentPreparer.cleanupAbandonedContent(context)
}
