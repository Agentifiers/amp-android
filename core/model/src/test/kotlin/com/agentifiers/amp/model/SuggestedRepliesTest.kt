package com.agentifiers.amp.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestedRepliesTest {
    private fun parsed(prose: String, vararg replies: SuggestedReply) = AssistantText(prose, replies.toList().toPersistentList())

    @Test
    fun `the user's block leaves the prose and three replies whose bold words lead`() {
        val raw = "Both apps deploy now.\n\n<suggested-replies>\n" +
            "**Register a fixed console hostname** for sign-in and deploy dev\n" +
            "Commit and push everything to both repos\n" +
            "**Tunnel the apps database** too\n" +
            "</suggested-replies>\n"
        val text = AssistantText.parse(raw)
        assertEquals(
            parsed(
                "Both apps deploy now.",
                SuggestedReply("Register a fixed console hostname", " for sign-in and deploy dev"),
                SuggestedReply("", "Commit and push everything to both repos"),
                SuggestedReply("Tunnel the apps database", " too"),
            ),
            text,
        )
        assertEquals(
            listOf("Register a fixed console hostname for sign-in and deploy dev", "Commit and push everything to both repos", "Tunnel the apps database too"),
            text.replies.map { it.text },
        )
    }

    @Test
    fun `blank lines, list markers and a later bold inside the block are dropped`() {
        assertEquals(
            parsed("Done.", SuggestedReply("", "Ship it"), SuggestedReply("", "Add a test for the sort order"), SuggestedReply("Show", " me the diff now")),
            AssistantText.parse("Done.\n<suggested-replies>\n\n- Ship it\n\n  2. Add a test for the **sort order**\n**Show** me the diff **now**\n\n</suggested-replies>"),
        )
    }

    @Test
    fun `a block still streaming hides from its opening tag, and so does a half-written closing tag`() {
        assertEquals(
            parsed("Deploying now.", SuggestedReply("Register a fixed console hostname", " for sign")),
            AssistantText.parse("Deploying now.\n\n<suggested-replies>\n**Register a fixed console hostname** for sign"),
        )
        assertEquals(
            parsed("Deploying now.", SuggestedReply("", "Ship it")),
            AssistantText.parse("Deploying now.\n<suggested-replies>\nShip it\n</sugg"),
        )
        assertEquals(parsed("Deploying now."), AssistantText.parse("Deploying now.\n<suggested-replies>"))
    }

    @Test
    fun `an opening tag still arriving is hidden once it has two characters`() {
        assertEquals(
            listOf("Deploying now.", "Deploying now.", "Deploying now.\n<"),
            listOf("Deploying now.\n\n<sugg", "Deploying now.\n<s", "Deploying now.\n<").map { AssistantText.parse(it).prose },
        )
    }

    @Test
    fun `a message with no block is unchanged`() {
        val raw = "Renamed `add` to `add_numbers`.\n\n- [x] tests pass\n\n**Done.**  "
        assertEquals(parsed(raw), AssistantText.parse(raw))
    }

    @Test
    fun `text that only mentions the tag mid-line or before more prose stays as written`() {
        val cases = listOf(
            "End a reply with a <suggested-replies> block.",
            "Use the tag <suggested-replies>\nShip it\n</suggested-replies>",
            "<suggested-replies> wraps one reply per line.",
            "<suggested-replies>\nShip it\n</suggested-replies>\nThen the agent kept talking.",
            "The prose ends in <sugg",
        )
        assertEquals(cases.map { parsed(it) }, cases.map(AssistantText::parse))
    }

    @Test
    fun `the last block wins and an empty block offers nothing`() {
        assertEquals(
            parsed("A\n<suggested-replies>\nOld\n</suggested-replies>\nB", SuggestedReply("", "New")),
            AssistantText.parse("A\n<suggested-replies>\nOld\n</suggested-replies>\nB\n<suggested-replies>\nNew\n</suggested-replies>"),
        )
        assertEquals(parsed("Nothing to do."), AssistantText.parse("Nothing to do.\n<suggested-replies>\n</suggested-replies>"))
    }

    private val replies = persistentListOf(
        SuggestedReply("", "One"), SuggestedReply("", "Two"), SuggestedReply("", "Three"), SuggestedReply("", "Four"),
    )

    private fun state(
        end: TurnEnd = TurnEnd.Done,
        activity: AgentActivity = AgentActivity.Idle,
        pending: Boolean = false,
        link: Link = Link.Live,
    ): ThreadState {
        val id = ThreadId.parse("T-0a0b0c0d-0000-7000-8000-000000000044")!!
        val turn = Turn(TurnKey(MessageId("U-1")), null, persistentListOf(), end, replies)
        return ThreadState.initial(id).copy(
            activity = activity,
            link = link,
            transcript = TranscriptView.Ready(persistentListOf(turn), EarlierHistory.None, Compaction.None, null),
            pending = if (pending) persistentListOf(PendingSend(MessageId("U-2"), "One", false, SendState.Sending)) else persistentListOf(),
        )
    }

    @Test
    fun `a settled thread offers its last turn's first three replies, and nothing while busy, sending, open or archived`() {
        assertEquals(
            listOf(listOf("One", "Two", "Three"), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            listOf(
                state(),
                state(activity = AgentActivity.Streaming),
                state(pending = true),
                state(end = TurnEnd.Open),
                state(end = TurnEnd.Interrupted),
                state(link = Link.Stopped(StopReason.Archived)),
            ).map { s -> s.offeredReplies().map { it.text } },
        )
    }
}
