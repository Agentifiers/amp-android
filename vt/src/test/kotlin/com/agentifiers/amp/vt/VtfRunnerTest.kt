package com.agentifiers.amp.vt

import org.junit.Assert.assertEquals
import org.junit.Test

class VtfRunnerTest {
    @Test
    fun reportsTheFirstFailedExpectation() {
        val fixtures = parseFixtures(
            "unit",
            "runner.vtf",
            """
            name: wrong row
            source: VtfRunnerTest
            size: 10x2
            input: hellx
            cursor: 5,0
            row 0: |hello|
            ---
            name: styled wide cell
            source: VtfRunnerTest
            size: 10x2
            input: \e[1;31mh\e[0m\u{4E2D}\x41
            cell 0,0: char=h bold fg=1
            cell 1,0: char=中 width=2 plain
            cell 3,0: char=A
            cell 5,0: char=
            """.trimIndent(),
        )
        assertEquals(listOf("row 0: |hello| -> got |hellx| want |hello|", null), fixtures.map { it.run() })
    }
}
