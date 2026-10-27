package com.agentifiers.amp.settings

import androidx.compose.ui.unit.dp
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.model.WorkspaceRef
import com.agentifiers.amp.sync.api.CreditsGlance
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTextTest {
    @Test
    fun needsYouCountsOnlyWhenSomethingWaits() {
        assertEquals(listOf(null, "1 needs you", "2 need you"), listOf(0, 1, 2).map(::needsYouText))
    }

    @Test
    fun hostShowsOnlyOffAmpcode() {
        assertEquals(
            listOf(null, "127.0.0.1:59879", "amp.example.com"),
            listOf(
                ServerEnvironment.Production,
                ServerEnvironment("http://127.0.0.1:59879", "http://127.0.0.1:59879/actors"),
                ServerEnvironment("https://amp.example.com", "https://amp.example.com/actors"),
            ).map { it.shownHost() },
        )
    }

    @Test
    fun workspaceNamesTheTeamOrPersonal() {
        assertEquals(listOf("Personal", "Example"), listOf(WorkspaceRef.Personal, WorkspaceRef.Team("W-1", "Example")).map { it.label() })
    }

    @Test
    fun creditsShowTheBalanceAndAnyEntitlement() {
        assertEquals(
            listOf(
                emptyList(),
                emptyList(),
                listOf("$12.34 remaining"),
                listOf("$1,250.00 remaining", "Entitlement: $40 remaining of $100 monthly limit (60% used)"),
            ),
            listOf(
                CreditsGlance.Loading,
                CreditsGlance.Unavailable,
                CreditsGlance.Known(12.34, null),
                CreditsGlance.Known(1250.0, "Entitlement: $40 remaining of $100 monthly limit (60% used)"),
            ).map(::creditsLines),
        )
    }

    @Test
    fun verificationLinkDropsTheCodeQuery() {
        assertEquals(
            listOf("ampcode.com/auth/cli/device", "auth.ampcode.com/device", "127.0.0.1:59879/auth/cli/device"),
            listOf(
                "https://ampcode.com/auth/cli/device?user_code=GMKF-JRTX",
                "https://auth.ampcode.com/device/?user_code=GMKF-JRTX",
                "http://127.0.0.1:59879/auth/cli/device?user_code=GMKF-JRTX#approve",
            ).map(::verificationLinkText),
        )
    }

    @Test
    fun indexTakesTheFirstCreaseOnWidePanes() {
        assertEquals(
            listOf(null, 335.dp, 240.dp, 240.dp),
            listOf(
                indexWidth(382.dp, listOf(335.dp)),
                indexWidth(1080.dp, listOf(335.dp, 717.dp)),
                indexWidth(745.dp, emptyList()),
                indexWidth(745.dp, listOf(745.dp)),
            ),
        )
    }

    @Test
    fun signInKeepsToTheMiddlePanelBetweenTwoCreases() {
        assertEquals(351.dp..701.dp, fullScreenColumn(1080.dp, listOf(335.dp, 717.dp)))
    }

    @Test
    fun withoutCreasesSignInKeepsItsCenteredColumn() {
        assertEquals(
            listOf(24.dp..387.dp, 196.dp..596.dp, 340.dp..740.dp),
            listOf(fullScreenColumn(411.dp, emptyList()), fullScreenColumn(792.dp, emptyList()), fullScreenColumn(1080.dp, emptyList())),
        )
    }

    @Test
    fun aCreaseOnTheCenterLeavesTheWholeScreen() {
        assertEquals(220.dp..620.dp, fullScreenColumn(840.dp, listOf(420.dp)))
    }
}
