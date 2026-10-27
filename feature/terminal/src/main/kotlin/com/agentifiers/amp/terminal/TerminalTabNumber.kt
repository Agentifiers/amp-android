package com.agentifiers.amp.terminal

import com.agentifiers.amp.model.TerminalSessionKey

/** Terminal tab 1 to 5 (terminal.md, "Several terminals per thread"). Tab 1 has no session key; tab N uses `tab-N`. */
@JvmInline
value class TerminalTabNumber private constructor(val value: Int) : Comparable<TerminalTabNumber> {
    val key: TerminalSessionKey? get() = if (value == 1) null else checkNotNull(TerminalSessionKey.parse("tab-$value"))

    /** The chip label: the OSC 0 or 2 title when the shell set one. */
    fun label(title: String): String = title.ifBlank { if (value == 1) "Terminal" else "Terminal $value" }

    override fun compareTo(other: TerminalTabNumber): Int = value.compareTo(other.value)

    companion object {
        const val MAX = 5
        val all: List<TerminalTabNumber> = (1..MAX).map(::TerminalTabNumber)
        val First: TerminalTabNumber = all.first()

        fun of(value: Int): TerminalTabNumber? = all.getOrNull(value - 1)

        /** The tab a session key belongs to, or null for a key other than none and `tab-2` to `tab-5`. */
        fun of(key: TerminalSessionKey?): TerminalTabNumber? = all.firstOrNull { it.key == key }
    }
}
