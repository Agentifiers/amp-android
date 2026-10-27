package com.agentifiers.amp.fleet.newthread

import android.content.Context
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.OrbProvider
import com.agentifiers.amp.model.OrbProviderConfigId
import com.agentifiers.amp.model.OrbProviderOption
import com.agentifiers.amp.model.OrbProviderOwner

/**
 * The self-hosted providers the picker offers: the last list the account's web session returned, then the names the
 * user typed that the list lacks, in the order added. [last] is the provider picked last, null until the first pick.
 */
internal data class OrbProviderBook(
    val listed: List<OrbProviderOption> = emptyList(),
    val typed: List<OrbProvider.SelfHosted.Named> = emptyList(),
    val last: OrbProvider? = null,
) {
    val offered: List<OrbProvider.SelfHosted> get() = listed.map { it.provider } + typed.filter { listedNamed(it.name) == null }

    /** The web composer's rule (`cbbea68c.js`, `ze()`): the last pick if still offered, else the workspace default, else Built-in. */
    val initial: OrbProvider get() = last?.let(::merged)?.takeIf { it == OrbProvider.Amp || it in offered }
        ?: listed.firstOrNull { it.isDefault }?.provider
        ?: OrbProvider.Amp

    fun option(provider: OrbProvider.SelfHosted): OrbProviderOption? = listed.firstOrNull { it.provider == provider }

    /** A typed name that matches a listed provider's name becomes that provider. */
    fun merged(provider: OrbProvider): OrbProvider = (provider as? OrbProvider.SelfHosted.Named)?.let { listedNamed(it.name) } ?: provider

    fun listed(options: List<OrbProviderOption>): OrbProviderBook = copy(listed = options)

    fun add(provider: OrbProvider.SelfHosted.Named): OrbProviderBook = if (provider in typed) this else copy(typed = typed + provider)

    fun forget(provider: OrbProvider.SelfHosted.Named): OrbProviderBook = copy(typed = typed - provider, last = last.takeIf { it != provider })

    fun used(provider: OrbProvider): OrbProviderBook = copy(last = provider)

    private fun listedNamed(name: String): OrbProvider.SelfHosted.Listed? = listed.firstOrNull { it.provider.name == name }?.provider
}

/** [OrbProviderBook] per account, in the SharedPreferences file `orb-providers-<accountId>`. */
internal class OrbProviderMemory(context: Context, account: AccountId) {
    private val prefs = context.getSharedPreferences("orb-providers-${account.raw}", Context.MODE_PRIVATE)

    fun load(): OrbProviderBook = OrbProviderCodec.decode(prefs.getString(LISTED, null), prefs.getString(NAMES, null), prefs.getString(LAST, null))

    fun save(book: OrbProviderBook) {
        val (listed, names, last) = OrbProviderCodec.encode(book)
        prefs.edit().putString(LISTED, listed).putString(NAMES, names).putString(LAST, last).apply()
    }

    private companion object {
        const val LISTED = "listed"
        const val NAMES = "self-hosted"
        const val LAST = "last"
    }
}

/**
 * The stored strings of an [OrbProviderBook]. Listed providers are one per line as `configID`, owner, default flag,
 * and name, separated by tabs; neither field may hold a control character, so tabs and newlines never appear inside.
 * The last pick is `builtin`, `config:<configID>`, or a typed name, the U55 format for names.
 */
internal object OrbProviderCodec {
    /** The CLI's own word for Built-in Orbs (`--orb-provider <name|config ID|builtin>`). */
    private const val BUILT_IN = "builtin"
    private const val CONFIG = "config:"

    fun encode(book: OrbProviderBook): Triple<String, String, String?> {
        val listed = book.listed.joinToString("\n") { option ->
            listOf(option.provider.configId.raw, option.owner.name, option.isDefault.toString(), option.provider.name).joinToString("\t")
        }
        val last = when (val provider = book.last) {
            null -> null
            OrbProvider.Amp -> BUILT_IN
            is OrbProvider.SelfHosted.Listed -> CONFIG + provider.configId.raw
            is OrbProvider.SelfHosted.Named -> provider.name
        }
        return Triple(listed, book.typed.joinToString("\n") { it.name }, last)
    }

    fun decode(listed: String?, names: String?, last: String?): OrbProviderBook {
        val options = listed?.split('\n').orEmpty().mapNotNull(::option)
        val typed = names?.split('\n').orEmpty().mapNotNull(OrbProvider.SelfHosted::named)
        val pick = when {
            last == null -> null
            last == BUILT_IN -> OrbProvider.Amp
            last.startsWith(CONFIG) -> options.firstOrNull { it.provider.configId.raw == last.removePrefix(CONFIG) }?.provider
            else -> OrbProvider.SelfHosted.named(last)?.takeIf { it in typed }
        }
        return OrbProviderBook(options, typed, pick)
    }

    private fun option(line: String): OrbProviderOption? {
        val fields = line.split('\t')
        if (fields.size != 4 || fields[0].isEmpty()) return null
        val owner = OrbProviderOwner.entries.firstOrNull { it.name == fields[1] } ?: return null
        val name = OrbProvider.SelfHosted.named(fields[3])?.name ?: return null
        return OrbProviderOption(OrbProvider.SelfHosted.Listed(OrbProviderConfigId(fields[0]), name), fields[2] == "true", owner)
    }
}
