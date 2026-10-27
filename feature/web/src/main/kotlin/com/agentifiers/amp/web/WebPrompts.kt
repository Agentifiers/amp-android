package com.agentifiers.amp.web

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.agentifiers.amp.design.WebTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** One question a page caused, answered by the user in a native dialog the page cannot draw over. */
internal class Confirm(val title: String, val body: String, val allow: String, val answer: CancellableContinuation<Boolean>)

/** The native questions and notices for one web surface. Questions queue, so two at once are asked in turn. */
@Stable
internal class WebPrompts {
    val queue = mutableStateListOf<Confirm>()
    var notice by mutableStateOf<String?>(null)

    suspend fun confirm(title: String, body: String, allow: String): Boolean = suspendCancellableCoroutine { answer ->
        val item = Confirm(title, body, allow, answer)
        queue += item
        answer.invokeOnCancellation { queue.remove(item) }
    }

    fun answer(item: Confirm, allowed: Boolean) {
        queue.remove(item)
        if (item.answer.isActive) item.answer.resume(allowed)
    }
}

@Composable
internal fun PromptDialog(prompts: WebPrompts) {
    val head = prompts.queue.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { prompts.answer(head, false) },
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text(head.title) },
        text = { Text(head.body) },
        confirmButton = {
            AmpButton(head.allow, { prompts.answer(head, true) }, WebTags.confirmAllow, rank = AmpButtonRank.Primary)
        },
        dismissButton = {
            AmpButton("Cancel", { prompts.answer(head, false) }, WebTags.confirmDeny)
        },
    )
}

@Composable
internal fun NoticeLine(prompts: WebPrompts, modifier: Modifier = Modifier) {
    val notice = prompts.notice ?: return
    Text(notice, color = AmpTheme.colors.text.inkMuted, modifier = modifier)
}
