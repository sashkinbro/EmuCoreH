package com.sbro.emucoreh.core

/** Rejects callbacks queued by a SurfaceView that belongs to an ended session. */
internal class PresentationSurfaceEpoch {
    var generation: Long = 0L
        private set
    var eventVersion: Long = 0L
        private set
    private var owner: Any? = null
    // Pending callbacks keep their Surface alive; completed views must not be
    // retained just to reject a callback that can no longer arrive.
    private val retiredOwners = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
    private var destroyedOwner: java.lang.ref.WeakReference<Any>? = null

    fun accepts(callbackGeneration: Long): Boolean = callbackGeneration == generation

    fun created(callbackGeneration: Long, surfaceOwner: Any): Boolean {
        if (!accepts(callbackGeneration)) return false
        if (surfaceOwner !in retiredOwners) return true
        // SurfaceHolder may reuse its Surface after context/window loss. A fresh
        // creation can reactivate only the last destroyed, still unclaimed owner.
        if (owner != null || destroyedOwner?.get() !== surfaceOwner) return false
        retiredOwners.remove(surfaceOwner)
        destroyedOwner = null
        return true
    }

    fun changed(callbackGeneration: Long, surfaceOwner: Any? = null): Long? {
        if (!accepts(callbackGeneration) || (surfaceOwner != null && surfaceOwner in retiredOwners)) return null
        if (owner !== surfaceOwner) owner?.let(retiredOwners::add)
        owner = surfaceOwner
        destroyedOwner = null
        return ++eventVersion
    }

    fun isCurrent(callbackGeneration: Long, callbackEvent: Long): Boolean =
        accepts(callbackGeneration) && callbackEvent == eventVersion

    fun destroyed(callbackGeneration: Long, surfaceOwner: Any? = null): Boolean {
        if (!accepts(callbackGeneration) || (surfaceOwner != null && owner !== surfaceOwner)) return false
        owner?.let(retiredOwners::add)
        destroyedOwner = owner?.let { java.lang.ref.WeakReference(it) }
        owner = null
        eventVersion++
        return true
    }

    fun sessionEnded(): Long {
        owner = null
        retiredOwners.clear()
        destroyedOwner = null
        eventVersion++
        return ++generation
    }
}
