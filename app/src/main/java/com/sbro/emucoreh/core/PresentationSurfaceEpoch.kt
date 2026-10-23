package com.sbro.emucoreh.core

/** Rejects callbacks queued by a SurfaceView that belongs to an ended session. */
internal class PresentationSurfaceEpoch {
    var generation: Long = 0L
        private set
    var eventVersion: Long = 0L
        private set
    private var owner: Any? = null

    fun accepts(callbackGeneration: Long): Boolean = callbackGeneration == generation

    fun changed(callbackGeneration: Long, surfaceOwner: Any? = null): Long? {
        if (!accepts(callbackGeneration)) return null
        owner = surfaceOwner
        return ++eventVersion
    }

    fun isCurrent(callbackGeneration: Long, callbackEvent: Long): Boolean =
        accepts(callbackGeneration) && callbackEvent == eventVersion

    fun destroyed(callbackGeneration: Long, surfaceOwner: Any? = null): Boolean {
        if (!accepts(callbackGeneration) || (surfaceOwner != null && owner !== surfaceOwner)) return false
        owner = null
        eventVersion++
        return true
    }

    fun sessionEnded(): Long {
        owner = null
        eventVersion++
        return ++generation
    }
}
