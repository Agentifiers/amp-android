package com.agentifiers.amp.vt

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class FixtureConformanceTest {
    @Test
    fun everyFixturePasses() {
        val root = File(requireNotNull(javaClass.getResource("/vt-fixtures")) { "vt-fixtures missing" }.toURI())
        val fixtures = loadFixtures(root)
        assertTrue("no fixtures under $root", fixtures.isNotEmpty())
        val results = fixtures.map { it to it.run() }
        for ((suite, inSuite) in results.groupBy { it.first.suite }.toSortedMap()) {
            println("vt-fixtures $suite: ${inSuite.count { it.second == null }}/${inSuite.size} passed")
        }
        println("vt-fixtures total: ${results.count { it.second == null }}/${results.size} passed")
        val unexpected = results.filter { (f, problem) -> problem != null && f.id !in KNOWN_FAILURES }
        val fixed = results.filter { (f, problem) -> problem == null && f.id in KNOWN_FAILURES }.map { it.first.id }
        val stale = KNOWN_FAILURES.keys - fixtures.map { it.id }.toSet()
        if (unexpected.isEmpty() && fixed.isEmpty() && stale.isEmpty()) return
        fail(
            buildString {
                for ((f, problem) in unexpected) appendLine("FAIL ${f.id} (${f.source}): $problem")
                for (id in fixed) appendLine("PASSES but listed in KNOWN_FAILURES: $id")
                for (id in stale) appendLine("KNOWN_FAILURES names a missing fixture: $id")
            },
        )
    }

    private companion object {
        /** Fixture id (`suite/name`) to the reason the Kotlin core does not pass it. */
        val KNOWN_FAILURES: Map<String, String> = mapOf()
    }
}
