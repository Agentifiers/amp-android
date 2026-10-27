package com.agentifiers.amp.thread.composer.attachments

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Its own class so the manifest merger never collides with another module's `androidx.core.content.FileProvider`. */
class AttachmentFileProvider : FileProvider()

/** A fresh file in `cacheDir/attachments`, the only directory [AttachmentFileProvider] serves. */
internal fun newAttachmentFile(context: Context, prefix: String, extension: String): File {
    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
    return File(dir, "$prefix-${System.currentTimeMillis()}.$extension")
}

internal fun attachmentUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
