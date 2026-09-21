package com.google.androidgamesdk

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryVersionsTest {

    @Test
    fun parseGood() {
        val example_expected = listOf(
            Pair(
                listOf("# A test", "name jname None 1.0.0"),
                listOf(
                    LibraryInfo(
                        "name",
                        "jname",
                        null,
                        SemanticVersion(1, 0, 0),
                        ""
                    )
                )
            ),
            Pair(
                listOf("# A test", "name jname pname 9.8.7 rc3", "# # #"),
                listOf(
                    LibraryInfo(
                        "name",
                        "jname",
                        "pname",
                        SemanticVersion(9, 8, 7),
                        "rc3"
                    )
                )
            )
        )
        for (ee in example_expected) {
            val result = LibraryVersions.parseLines(ee.first)
            assertEquals(result.size, ee.second.size)
            result.forEachIndexed { i, r ->
                assertEquals(ee.second[i], r)
            }
        }
    }
}
