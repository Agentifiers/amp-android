package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.FakeProject
import com.agentifiers.amp.fake.OrganizedThread
import com.agentifiers.amp.fake.PUCK_AGENT_MODE
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import java.time.Instant

/**
 * The 40-thread fleet of the U11 contract: every sidebar section, label, project, subagent child, Puck conversation,
 * teammate thread, and visibility the organize and search screens read. Ages count back from [FakeAmp.RECORDED_AT],
 * which the server serves as the moment it starts.
 * Every thread opens on the empty S4 thread; Puck conversations play the scripted Puck reply.
 */
internal object FleetOrganize : ScenarioSpec {
    override val name = "fleet-organize"

    const val TEAMMATE = "user_01JTEAMMATE00000000000000"
    private val ANDROID = FakeProject("proj-android", "amp-android")
    private val RELAY = FakeProject("proj-relay", "relay")

    fun id(n: Int): String = "T-f1ee7000-0000-4000-8000-0000000000%02d".format(n)

    override fun build(): Scenario = scenario(threads(FakeAmp.RECORDED_AT))

    /** Newest first, the order Amp lists threads in. */
    fun scenario(threads: List<OrganizedThread>): Scenario = Scenario(threads.sortedByDescending { it.lastActive }.map { it.seed() })

    fun threads(start: Instant): List<OrganizedThread> {
        fun ago(hours: Double): Instant = start.minusMillis((hours * 3_600_000).toLong())
        return listOf(
            OrganizedThread(id(1), "Fix crease snapping", ago(0.1), ANDROID, state = "working", labels = listOf("ui")),
            OrganizedThread(id(2), "Push relay retries", ago(0.5), RELAY, state = "working", labels = listOf("infra")),
            OrganizedThread(id(3), "Approve migration", ago(1.0), ANDROID, needsInput = "Bash: ./gradlew migrate"),
            OrganizedThread(id(4), "Deploy failed on orb", ago(2.0), RELAY, state = "error"),
            OrganizedThread(id(5), "Write release notes", ago(3.0), unread = true, labels = listOf("docs")),
            OrganizedThread(id(6), "Pinned roadmap", ago(200.0), ANDROID, labels = listOf("docs", "planning"), pinned = true),
            OrganizedThread(id(7), "Pinned on-call notes", ago(5.0), RELAY, pinned = true),
            OrganizedThread(id(8), "Sidebar polish", ago(4.0), ANDROID, labels = listOf("ui")),
            OrganizedThread(id(9), "Rail tap target bug", ago(6.0), ANDROID, labels = listOf("ui", "bug")),
            OrganizedThread(id(10), "Crash on fold", ago(8.0), ANDROID, labels = listOf("bug")),
            OrganizedThread(id(11), "Dark theme contrast", ago(10.0), ANDROID),
            OrganizedThread(id(12), "Keyboard shortcuts", ago(12.0), ANDROID),
            OrganizedThread(id(13), "Tablet layout audit", ago(20.0), ANDROID),
            OrganizedThread(id(14), "Notification channels", ago(30.0), ANDROID),
            OrganizedThread(id(15), "Widget refresh", ago(40.0), ANDROID),
            OrganizedThread(id(16), "Relay TLS rotation", ago(5.0), RELAY, labels = listOf("infra")),
            OrganizedThread(id(17), "Relay dropped pushes", ago(7.0), RELAY, labels = listOf("infra", "bug")),
            OrganizedThread(id(18), "Relay metrics", ago(9.0), RELAY),
            OrganizedThread(id(19), "Relay cost report", ago(48.0), RELAY),
            OrganizedThread(id(20), "Relay docs", ago(70.0), RELAY),
            OrganizedThread(id(21), "Quick question", ago(1.0)),
            OrganizedThread(id(22), "Scratch notes", ago(15.0)),
            OrganizedThread(id(23), "Old experiment", ago(60.0)),
            OrganizedThread(id(24), "Flaky test triage", ago(80.0), ANDROID, labels = listOf("bug")),
            OrganizedThread(id(25), "Icon refresh", ago(90.0), ANDROID, labels = listOf("ui")),
            OrganizedThread(id(26), "Relay load test", ago(100.0), RELAY),
            OrganizedThread(id(27), "Relay alerts", ago(120.0), RELAY),
            OrganizedThread(id(28), "Reading list", ago(150.0)),
            OrganizedThread(id(29), "Conference talk", ago(200.0)),
            OrganizedThread(id(30), "Gradle upgrade", ago(300.0), ANDROID),
            OrganizedThread(id(31), "Relay v1 retro", ago(500.0), RELAY),
            OrganizedThread(id(32), "Archived spike", ago(10.0), ANDROID, labels = listOf("ui"), archived = true),
            OrganizedThread(id(33), "Archived relay test", ago(100.0), RELAY, archived = true),
            OrganizedThread(id(34), "Archived notes", ago(400.0), archived = true),
            OrganizedThread(id(35), "Subagent: measure hinge", ago(0.2), ANDROID, parent = id(1)),
            OrganizedThread(id(36), "Subagent: write test", ago(0.3), ANDROID, parent = id(1)),
            OrganizedThread(id(37), "Puck: morning summary", ago(2.0), agentMode = PUCK_AGENT_MODE),
            OrganizedThread(id(38), "Puck: label cleanup", ago(30.0), agentMode = PUCK_AGENT_MODE),
            OrganizedThread(id(39), "Teammate spike", ago(2.0), ANDROID, creator = TEAMMATE, visibility = "private"),
            OrganizedThread(id(40), "Unlisted demo", ago(50.0), visibility = "public_unlisted"),
        )
    }
}
