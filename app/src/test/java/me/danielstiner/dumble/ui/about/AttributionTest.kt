package me.danielstiner.dumble.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionTest {

    @Test fun matchesExactGroup() {
        assertEquals(License.MIT, attributionFor("org.slf4j")?.license)
    }

    @Test fun matchesSubgroupByPrefix() {
        assertEquals(License.APACHE_2_0, attributionFor("androidx.compose.material3")?.license)
    }

    /** "org.jetbrains" is a prefix of "org.jetbrains.kotlin"; the more specific entry must win. */
    @Test fun prefersLongestPrefix() {
        assertEquals("org.jetbrains.kotlin", attributionFor("org.jetbrains.kotlin")?.groupPrefix)
        assertEquals("org.jetbrains", attributionFor("org.jetbrains")?.groupPrefix)
    }

    /** A prefix must match on a dot boundary, never mid-segment. */
    @Test fun doesNotMatchPartialSegment() {
        assertNull(attributionFor("org.slf4jx"))
        assertNull(attributionFor("androidxtra.core"))
    }

    @Test fun unknownGroupIsUnattributed() {
        assertNull(attributionFor("com.example.unknown"))
    }

    @Test fun protobufIsBsdAndVendoredSchemaIsAttributed() {
        assertEquals(License.BSD_3_CLAUSE, attributionFor("com.google.protobuf")?.license)
        assertNotNull(MUMBLE_SCHEMA)
        assertEquals(License.BSD_3_CLAUSE, MUMBLE_SCHEMA.license)
    }

    @Test fun bsdSectionIncludesBothProtobufAndVendoredSchema() {
        val bsd = attributionsFor(License.BSD_3_CLAUSE)
        assertTrue(bsd.contains(MUMBLE_SCHEMA))
        assertTrue(bsd.any { it.groupPrefix == "com.google.protobuf" })
        assertTrue(bsd.contains(LIBOPUS))
        assertEquals(3, bsd.size)
    }

    /** Every license whose text we bundle must actually cover something, or the About screen shows an empty section. */
    @Test fun everyLicenseCoversAtLeastOneComponent() {
        License.entries.forEach { assertTrue(it.name, attributionsFor(it).isNotEmpty()) }
    }

    @Test fun groupPrefixesAreUnique() {
        assertEquals(ATTRIBUTIONS.size, ATTRIBUTIONS.map { it.groupPrefix }.toSet().size)
    }

    /**
     * Transitive arrivals appear in no diff; shipped-groups.txt is the committed record of what
     * actually resolves, kept current by the verifyShippedGroups Gradle task.
     */
    @Test fun everyShippedGroupIsAttributed() {
        val groups = javaClass.getResourceAsStream("/shipped-groups.txt")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }
        assertTrue("shipped-groups.txt is empty", groups.isNotEmpty())
        assertEquals(emptyList<String>(), groups.filter { attributionFor(it) == null })
    }

    @Test
    fun libopusIsAttributedUnderBsd() {
        val bsd = attributionsFor(License.BSD_3_CLAUSE)
        assertTrue(
            "libopus ships inside libdumble.so and must be attributed; had $bsd",
            bsd.any { it.description.contains("libopus") },
        )
    }

    @Test
    fun everyShippedSubmoduleIsAttributed() {
        val manifest = checkNotNull(
            javaClass.classLoader!!.getResourceAsStream("shipped-submodules.txt"),
        ) { "shipped-submodules.txt missing — run ./gradlew verifyShippedGroups" }
        val paths = manifest.bufferedReader().readLines().filter { it.isNotBlank() }
        // Vendored components carry an empty groupPrefix, so they are matched by name rather
        // than by attributionFor(), which only resolves Maven groups.
        val vendored = (ATTRIBUTIONS + MUMBLE_SCHEMA + LIBOPUS).filter { it.groupPrefix.isEmpty() }
        for (path in paths) {
            val name = path.substringAfterLast('/')
            assertTrue(
                "submodule $path is shipped but nothing in Attribution.kt mentions '$name'",
                vendored.any { it.description.contains(name, ignoreCase = true) },
            )
        }
    }
}
