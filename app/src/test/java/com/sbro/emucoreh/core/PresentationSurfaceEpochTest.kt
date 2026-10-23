package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class PresentationSurfaceEpochTest {
    @Test fun realSurfaceRecreationCanReactivateTheLastDestroyedOwner() {
        val epoch = PresentationSurfaceEpoch()
        val surface = Any()
        epoch.changed(epoch.generation, surface)
        assertTrue(epoch.destroyed(epoch.generation, surface))
        assertNull(epoch.changed(epoch.generation, surface))
        assertTrue(epoch.created(epoch.generation, surface))
        assertNotNull(epoch.changed(epoch.generation, surface))
    }

    @Test fun hiddenSurfaceCreationCannotDisplaceTheCurrentVisibleOwner() {
        val epoch = PresentationSurfaceEpoch()
        val old = Any()
        val visible = Any()
        epoch.changed(epoch.generation, old)
        epoch.destroyed(epoch.generation, old)
        epoch.changed(epoch.generation, visible)
        assertFalse(epoch.created(epoch.generation, old))
        assertNull(epoch.changed(epoch.generation, old))
    }

    @Test fun supersededSurfaceCannotReclaimTheVisibleSessionsWindow() {
        val epoch = PresentationSurfaceEpoch()
        val hidden = Any()
        val visible = Any()
        epoch.changed(epoch.generation, hidden)
        val current = epoch.changed(epoch.generation, visible)!!
        assertNull(epoch.changed(epoch.generation, hidden))
        assertFalse(epoch.destroyed(epoch.generation, hidden))
        assertTrue(epoch.isCurrent(epoch.generation, current))
        assertNotNull(epoch.changed(epoch.generation, visible))
    }

    @Test fun destroyedOwnerCannotReturnUntilTheNextSession() {
        val epoch = PresentationSurfaceEpoch()
        val owner = Any()
        epoch.changed(epoch.generation, owner)
        assertTrue(epoch.destroyed(epoch.generation, owner))
        assertNull(epoch.changed(epoch.generation, owner))
        epoch.sessionEnded()
        assertNotNull(epoch.changed(epoch.generation, owner))
    }

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
