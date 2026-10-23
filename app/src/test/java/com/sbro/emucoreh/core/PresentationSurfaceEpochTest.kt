package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class PresentationSurfaceEpochTest {
    @Test fun replacedViewWithinSameGenerationCannotDestroyTheCurrentSurface() {
        val surface = PresentationSurfaceEpoch()
        val oldView = Any()
        val newView = Any()
        surface.changed(surface.generation, oldView)
        val newAttach = surface.changed(surface.generation, newView)!!
        assertFalse(surface.destroyed(surface.generation, oldView))
        assertTrue(surface.isCurrent(surface.generation, newAttach))
        assertTrue(surface.destroyed(surface.generation, newView))
        assertFalse(surface.isCurrent(surface.generation, newAttach))
    }

    @Test fun oldSurfaceDestructionCannotInvalidateNewSessionsQueuedAttach() {
        val surface = PresentationSurfaceEpoch()
        val oldGeneration = surface.generation
        val oldAttach = surface.changed(oldGeneration)!!
        val newGeneration = surface.sessionEnded()
        val newAttach = surface.changed(newGeneration)!!
        assertFalse(surface.destroyed(oldGeneration))
        assertTrue(surface.isCurrent(newGeneration, newAttach))
        assertFalse(surface.isCurrent(oldGeneration, oldAttach))
    }

    @Test fun queuedOldAttachCannotRebindAfterSessionEndsAndCurrentDestroyCancelsAttach() {
        val surface = PresentationSurfaceEpoch()
        val oldGeneration = surface.generation
        val oldAttach = surface.changed(oldGeneration)!!
        val currentGeneration = surface.sessionEnded()
        assertFalse(surface.isCurrent(oldGeneration, oldAttach))
        assertNull(surface.changed(oldGeneration))
        val currentAttach = surface.changed(currentGeneration)!!
        assertTrue(surface.destroyed(currentGeneration))
        assertFalse(surface.isCurrent(currentGeneration, currentAttach))
    }
}
