package com.agentifiers.amp.sync.internal.portals

import com.agentifiers.amp.model.Children
import com.agentifiers.amp.model.FileContent
import com.agentifiers.amp.model.FileNode
import com.agentifiers.amp.model.Portal
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.Portals
import com.agentifiers.amp.sync.internal.files.FilesEngine
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Portals of one thread. The list comes from the executor's JSON manifests in `.amp/portals/`
 * (Amp's docs); login URLs come from internal RPC (`captures/s13-portal.jsonl:1-5`).
 */
internal class PortalsEngine(
    private val thread: ThreadId,
    private val http: AmpHttp,
    private val files: FilesEngine,
    scope: CoroutineScope,
) : Portals {
    override val list: StateFlow<PersistentList<Portal>> = flow { emit(manifests()) }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_MS), persistentListOf())

    override suspend fun loginUrl(port: Int): Outcome<String> {
        val params = buildJsonObject {
            put("threadID", thread.raw)
            put("port", port)
        }
        return when (val resolved = http.rpc("resolveThreadPortalURL", params) { urlOf(it) }) {
            is Outcome.Ok -> loginUrlFor(resolved.value)
            else -> resolved
        }
    }

    override suspend fun loginUrlFor(portalUrl: String): Outcome<String> =
        http.rpc("mintPortalLoginURL", buildJsonObject { put("url", portalUrl) }) { urlOf(it) }

    /** A missing folder, an unreadable manifest, or a lost socket all read as no portals. */
    private suspend fun manifests(): PersistentList<Portal> {
        val folder = files.list(MANIFEST_DIR) as? Children.Loaded ?: return persistentListOf()
        return folder.nodes.filterIsInstance<FileNode.File>().filter { it.path.endsWith(".json") }.flatMap { manifest ->
            val text = ((files.read(manifest.path) as? Outcome.Ok<*>)?.value as? FileContent.Text)?.text
            text?.let { portals(it) }.orEmpty()
        }.toPersistentList()
    }

    private companion object {
        const val MANIFEST_DIR = ".amp/portals"
        const val STOP_MS = 5_000L
        val PORT_LABEL = Regex("-p(\\d+)$")

        fun urlOf(result: JsonElement): String? = (result as? JsonObject)?.string("url")

        /** `{"links":[{label, url, note?} | {folder, links:[{label, url, note?}]}]}`; folders nest one level. */
        fun portals(text: String): List<Portal> {
            val manifest = try {
                AmpJson.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
            return manifest?.array("links").orEmpty().mapNotNull { it as? JsonObject }.flatMap { entry ->
                val folder = entry.string("folder")
                if (folder == null) {
                    listOfNotNull(portal(entry, null))
                } else {
                    entry.array("links").orEmpty().mapNotNull { (it as? JsonObject)?.let { link -> portal(link, folder) } }
                }
            }
        }

        fun portal(link: JsonObject, folder: String?): Portal? {
            val url = link.string("url") ?: return null
            val label = link.string("label") ?: return null
            return Portal(url, label, folder, link.string("note"), portOf(url))
        }

        /** `t-<id>-p8765.onamp.dev` names its port; a custom hostname does not. */
        fun portOf(url: String): Int? {
            val label = url.toHttpUrlOrNull()?.host?.substringBefore('.') ?: return null
            return PORT_LABEL.find(label)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
