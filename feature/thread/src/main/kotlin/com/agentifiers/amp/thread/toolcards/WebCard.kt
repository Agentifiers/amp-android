package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.agentifiers.amp.design.markdown.Markdown
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun

data class WebLink(val url: String, val title: String?)

/** [links] are the http(s) URLs the result lists; [result] the page or answer as Markdown. */
data class WebCall(val target: WebTarget, val links: List<WebLink>, val result: String?)

sealed interface WebTarget {
    /** `read_web_page {url, objective?}`. */
    data class Page(val url: String, val objective: String?) : WebTarget

    /** `web_search {query}`, or `{objective}` when the agent searched by goal. */
    data class Search(val query: String) : WebTarget
}

object WebCard : ToolCard<WebCall> {
    override val names = listOf("read_web_page", "web_search")

    private val HTTP = Regex("^https?://", RegexOption.IGNORE_CASE)

    override fun decode(call: ToolCall): WebCall? {
        val input = call.input.value
        val target = when (call.name.raw) {
            "read_web_page" -> WebTarget.Page(input.text("url")?.takeIf { HTTP.containsMatchIn(it) } ?: return null, input.text("objective"))
            "web_search" -> WebTarget.Search(input.text("query") ?: input.text("objective") ?: return null)
            else -> return null
        }
        val result = call.run.result
        val links = result.objects().mapNotNull { obj ->
            obj.text("url")?.takeIf { HTTP.containsMatchIn(it) }?.let { WebLink(it, obj.text("title")) }
        }.distinctBy { it.url }.toList()
        return WebCall(target, links, result.prose())
    }

    override fun summary(call: WebCall, run: ToolRun): String = when (val target = call.target) {
        is WebTarget.Page -> target.url
        is WebTarget.Search -> if (call.links.isEmpty()) target.query else "${target.query} · ${count(call.links.size, "result")}"
    }

    @Composable
    override fun Body(call: WebCall, run: ToolRun, ctx: ToolCardContext) {
        when (val target = call.target) {
            is WebTarget.Page -> {
                Link(target.url, target.url, ctx)
                target.objective?.let { FindText(it, ctx) }
            }
            is WebTarget.Search -> FindText(target.query, ctx)
        }
        for (link in call.links) Link(link.title ?: link.url, link.url, ctx)
        call.result?.let { Markdown(it, highlight = ctx.highlight, onLink = ctx.openUrl) }
    }

    @Composable
    private fun Link(text: String, url: String, ctx: ToolCardContext) {
        FindText(text, ctx, Modifier.clickable(role = Role.Button) { ctx.openUrl(url) }, color = AmpTheme.colors.status.link)
    }
}
