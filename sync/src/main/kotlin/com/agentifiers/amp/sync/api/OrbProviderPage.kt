package com.agentifiers.amp.sync.api

import com.agentifiers.amp.model.OrbProvider
import com.agentifiers.amp.model.OrbProviderConfigId
import com.agentifiers.amp.model.OrbProviderListing
import com.agentifiers.amp.model.OrbProviderOption
import com.agentifiers.amp.model.OrbProviderOwner
import com.agentifiers.amp.sync.internal.wire.Devalue
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Reads the message :feature:web's hidden page posts after it fetches the web app's `__data.json`, the page data that
 * carries the composer's `orbProviderOptions` (web chunk `aa7cdc32.js`, `l.data.orbProviderOptions`). The page sends
 * `{error}` when the fetch threw, `{status}` for a non-2xx answer, `{status, type}` for a `redirect`, and for `data`
 * the devalue slots of the node holding `orbProviderOptions`, with every slot but each option's four fields nulled, or
 * null `values` when no node holds it. `signedIn` is present when some node has a `user`, and false when none is set.
 */
public object OrbProviderPage {
    private val json = Json { ignoreUnknownKeys = true }

    public fun parse(message: String): OrbProviderListing {
        val reply = runCatching { json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return unexpected
        reply.string("error")?.let { return OrbProviderListing.Failed("Couldn't reach ampcode.com.") }
        val status = (reply["status"] as? JsonPrimitive)?.intOrNull ?: return unexpected
        return when {
            status == 401 || status == 403 -> OrbProviderListing.SignedOut
            status == 404 -> OrbProviderListing.Failed("ampcode.com no longer serves the orb list (404).")
            status !in 200..299 -> OrbProviderListing.Failed("ampcode.com answered $status.")
            reply.string("type") == "redirect" -> OrbProviderListing.SignedOut
            reply.string("type") != "data" -> unexpected
            reply.bool("signedIn") == false -> OrbProviderListing.SignedOut
            else -> reply.array("values")?.let(::listed) ?: notListed
        }
    }

    private fun listed(values: JsonArray): OrbProviderListing {
        val options = (Devalue.unflatten(values) as? JsonObject)?.array("orbProviderOptions") ?: return notListed
        return OrbProviderListing.Listed(options.mapNotNull { (it as? JsonObject)?.let(::option) })
    }

    private fun option(entry: JsonObject): OrbProviderOption? {
        val configId = entry.string("configID")?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: return null
        val name = entry.string("name")?.let(OrbProvider.SelfHosted::named)?.name ?: return null
        val owner = if (entry.string("owner") == "personal") OrbProviderOwner.Personal else OrbProviderOwner.Workspace
        return OrbProviderOption(OrbProvider.SelfHosted.Listed(OrbProviderConfigId(configId), name), entry.bool("isDefault") == true, owner)
    }

    private val unexpected = OrbProviderListing.Failed("ampcode.com sent a page this app can't read.")
    private val notListed = OrbProviderListing.Failed("ampcode.com did not list self-hosted orbs.")
}
