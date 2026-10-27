package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.agentifiers.amp.model.ImageSource
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun
import java.util.Base64

data class MediaImage(val source: ImageSource, val caption: String?)

/** [subject] is the painter prompt, the viewed path, or the presentation title. */
data class MediaCall(val subject: String?, val images: List<MediaImage>)

object MediaCard : ToolCard<MediaCall> {
    override val names = listOf("painter", "view_media", "present_media")

    /**
     * How `view_media` and `present_media` deliver images is unobserved (screens.md, "Images and media"), so the card
     * takes every image it finds in the input, the result, and the progress, and gives a finished call without one to
     * the generic card.
     */
    override fun decode(call: ToolCall): MediaCall? {
        val input = call.input.value
        val images = (input.objects() + call.run.payload.objects()).mapNotNull(::imageOf).distinct().toList()
        if (images.isEmpty() && call.run is ToolRun.Done) return null
        return MediaCall(input.text("prompt") ?: input.text("path") ?: input.text("title"), images)
    }

    /**
     * The shapes the web reads (ampcode.com's web bundle, `function ol` and `function Lo`):
     * `{type: image, mimeType, url | data}`, a present_media item `{url, kind: image, caption?}`, and a message
     * image block `{type: image, source: {type: base64, mediaType, data} | {type: url, url}}`.
     */
    private fun imageOf(obj: JsonValue.Obj): MediaImage? {
        val caption = obj.text("caption")
        val source = obj["source"]
        return when {
            obj["type"].string == "image" && obj.text("mimeType") != null -> {
                val url = obj.text("url")
                val data = obj.text("data")
                when {
                    url != null -> MediaImage(ImageSource.Url(url), caption)
                    data != null -> MediaImage(ImageSource.Inline(obj.text("mimeType")!!, data), caption)
                    else -> null
                }
            }
            obj["type"].string == "image" && source["type"].string == "base64" ->
                MediaImage(ImageSource.Inline(source.text("mediaType") ?: return null, source.text("data") ?: return null), caption)
            obj["type"].string == "image" && source["type"].string == "url" -> MediaImage(ImageSource.Url(source.text("url") ?: return null), caption)
            obj["kind"].string == "image" -> MediaImage(ImageSource.Url(obj.text("url") ?: return null), caption)
            else -> null
        }
    }

    override fun summary(call: MediaCall, run: ToolRun): String {
        val images = if (call.images.isEmpty()) null else count(call.images.size, "image")
        return listOfNotNull(call.subject?.let(::firstLine), images).joinToString(" · ")
    }

    @Composable
    override fun Body(call: MediaCall, run: ToolRun, ctx: ToolCardContext) {
        call.subject?.let { FindText(it, ctx) }
        call.images.forEachIndexed { index, image ->
            val model = remember(image.source) { modelOf(image.source) }
            AsyncImage(
                model = model,
                contentDescription = image.caption ?: call.subject ?: "Image ${index + 1}",
                imageLoader = ctx.images,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                contentScale = ContentScale.Fit,
            )
            image.caption?.let { FindText(it, ctx, color = AmpTheme.colors.text.inkMuted) }
        }
    }
}

/** A URL loads through the account's loader; inline base64 decodes to bytes, which Coil reads directly. Bad base64 is null. */
internal fun modelOf(source: ImageSource): Any? = when (source) {
    is ImageSource.Url -> source.url
    is ImageSource.Inline -> runCatching { Base64.getMimeDecoder().decode(source.base64) }.getOrNull()
}
