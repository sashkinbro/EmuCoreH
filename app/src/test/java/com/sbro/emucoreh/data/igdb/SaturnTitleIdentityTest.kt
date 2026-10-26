package com.sbro.emucoreh.data.igdb

import org.junit.Assert.*
import org.junit.Test

class SaturnTitleIdentityTest {
    @Test fun dumpMetadataAndWhitespaceAreRemovedButRealIdentitySurvives() {
        assertEquals("panzer dragoon", SaturnTitleIdentity.normalize("  Panzer\u00a0Dragoon (USA) (5S).chd  "))
        assertEquals("sonic r demo", SaturnTitleIdentity.normalize("Sonic R (Demo) (USA).chd"))
        assertEquals("daytona usa championship circuit edition", SaturnTitleIdentity.normalize("Daytona USA (Championship Circuit Edition)"))
        assertFalse(SaturnTitleIdentity.permitsFuzzy("Sonic R (Special Edition)"))
        assertTrue(SaturnTitleIdentity.permitsFuzzy("Panzer Dragoon (USA) (5S).chd"))
        assertEquals("aliens vs predator 1999", SaturnTitleIdentity.normalize("Aliens vs. Predator (1999)"))
        assertEquals("panzer dragoon", SaturnTitleIdentity.normalize("Panzer Dragoon (USA) (1995).chd"))
    }

    @Test fun reviewedPortDefaultMustBelongToTheExactDatabaseCandidates() {
        assertEquals(139169L, SaturnTitleIdentity.resolveExactCandidates(setOf(5375L,139169L),139169L))
        assertEquals(259943L, SaturnTitleIdentity.resolveExactCandidates(setOf(170995L,259943L),259943L))
        assertNull(SaturnTitleIdentity.resolveExactCandidates(setOf(166555L,176115L),null))
        assertNull(SaturnTitleIdentity.resolveExactCandidates(setOf(72822L,336383L),null))
        assertNull(SaturnTitleIdentity.resolveExactCandidates(setOf(72822L,336383L),139169L))
    }

    @Test fun unknownSequelsAndEditionsNeverBecomeTheBaseGame() {
        for ((left,right) in listOf("daytona usa 2" to "daytona usa", "panzer dragoon 999" to "panzer dragoon",
            "virtua fighter 999" to "virtua fighter", "sonic r demo" to "sonic r", "sonic r special edition" to "sonic r",
            "daytona usa championship circuit edition" to "daytona usa")) {
            assertEquals(left, 0.0, SaturnTitleIdentity.similarity(left,right),0.0)
        }
        assertEquals(1.0,SaturnTitleIdentity.similarity("virtua fighter ii","virtua fighter 2"),0.0)
    }
}
