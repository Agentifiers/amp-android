package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Moves fixture times to the day the fake runs, so a recorded thread is exactly as old when served as it was at
 * [FakeAmp.RECORDED_AT], and order and gaps stay as recorded. Captures and scenarios are authored in recorded time, so
 * any time before [servedAt] is a fixture time and moves by `servedAt - RECORDED_AT`. A time the fake stamps while
 * running, or one the app sent, is at or after [servedAt] and passes through. Times are ISO-8601 and RFC 1123 strings
 * and epoch-millisecond numbers.
 */
internal class FixtureTime(private val servedAt: Instant) {
    private val shift: Duration = Duration.between(FakeAmp.RECORDED_AT, servedAt)
    private val shiftMs = BigDecimal.valueOf(shift.toMillis())
    private val servedAtMs = BigDecimal.valueOf(servedAt.toEpochMilli())

    fun served(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { served(it.value) })
        is JsonArray -> JsonArray(element.map(::served))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(served(element.content)) else served(element)
    }

    /** Exact decimal arithmetic, so a fractional `mtimeMs` keeps its digits. */
    private fun served(number: JsonPrimitive): JsonPrimitive {
        val ms = number.content.toBigDecimalOrNull() ?: return number
        return if (ms >= EPOCH_MS_FLOOR && ms < servedAtMs) JsonPrimitive(ms + shiftMs) else number
    }

    private fun served(text: String): String {
        ISO.matchEntire(text)?.let { match ->
            val at = OffsetDateTime.parse(text)
            if (!at.toInstant().isBefore(servedAt)) return text
            return at.plus(shift).format(isoFormat(fractionDigits = match.groupValues[1].length))
        }
        if (RFC_1123.matches(text)) {
            val at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
            if (!at.toInstant().isBefore(servedAt)) return text
            return at.plus(shift).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        }
        return text
    }

    private fun isoFormat(fractionDigits: Int): DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
        .apply { if (fractionDigits > 0) appendFraction(ChronoField.NANO_OF_SECOND, fractionDigits, fractionDigits, true) }
        .appendOffset("+HH:MM", "Z")
        .toFormatter()

    private companion object {
        /** 2001-09-09: no count or size the fake serves reaches it, and every epoch-millisecond time does. */
        val EPOCH_MS_FLOOR: BigDecimal = BigDecimal.valueOf(1_000_000_000_000L)
        val ISO = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.(\d{1,9}))?(?:Z|[+-]\d{2}:\d{2})""")
        val RFC_1123 = Regex("""[A-Z][a-z]{2}, \d{1,2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} (?:GMT|[+-]\d{4})""")
    }
}
