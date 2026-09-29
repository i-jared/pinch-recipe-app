package com.example.pinch.parsing

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds cooking durations in text ("simmer for 45 minutes", "1 hr 15 min", "10-12 min") so
 * steps can offer one-tap timers and metadata can be normalized. Mirrors iOS `DurationParser`.
 */
object DurationParser {
    data class Match(
        /** UTF-16 offset and length in the source string, same as iOS NSRange. */
        val location: Int,
        val length: Int,
        /** Lower bound in seconds; timers start from this. */
        val seconds: Int,
        val upperSeconds: Int?,
        /** Short label such as "45 min" or "10–12 min". */
        val label: String,
    )

    /** Longer durations are misread text (1000 hours). */
    private const val MAX_SECONDS = 3_600_000.0

    private const val NUMBER = """(?:\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?(?:\s?[½⅓⅔¼¾⅛⅜⅝⅞])?|[½⅓⅔¼¾⅛⅜⅝⅞]|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|ninety)"""
    private const val UNIT = """(hours?|hrs?\.?|minutes?|mins?\.?|seconds?|secs?\.?)"""

    private val spelledOut = TextKit.regex(
        """(?<![\w/.])($NUMBER)(?:\s*(?:-|–|—|to|or)\s*($NUMBER))?\s*-?\s*$UNIT(?![a-z])"""
    )
    private val compact = TextKit.regex("""(?<![\w.])(\d+)\s?h(?:\s?(\d{1,2})\s?m?)?(?![a-z])|(?<![\w.])(\d{1,3})(m|s)(?![a-z])""")
    private val halfHour = TextKit.regex("""\b(?:half an? hour|a half hour|half-hour)\b""")
    private val hourAndHalf = TextKit.regex("""\b(an?|one)\s+hour\s+and\s+a\s+half\b""")

    /** Every duration in the text, in order, with adjacent "1 hour 15 minutes" merged. */
    fun matches(text: String): List<Match> {
        val found = ArrayList<Match>()

        for (m in hourAndHalf.findAll(text)) {
            found.add(Match(m.range.first, m.value.length, 5400, null, "1 hr 30 min"))
        }
        for (m in halfHour.findAll(text)) {
            if (!overlaps(m.range, found)) found.add(Match(m.range.first, m.value.length, 1800, null, "30 min"))
        }
        for (m in spelledOut.findAll(text)) {
            if (overlaps(m.range, found)) continue
            val lowText = m.groups[1]?.value ?: continue
            val unitText = m.groups[3]?.value ?: continue
            val low = TextKit.numberValue(lowText) ?: continue
            val multiplier = unitSeconds(unitText)
            val high = m.groups[2]?.value?.let { TextKit.numberValue(it) }
            if (lowText.lowercase().startsWith("a") && high != null) continue
            if (low * multiplier > MAX_SECONDS) continue
            val lowSeconds = (low * multiplier).roundToInt()
            val highSeconds = high?.let { it * multiplier }?.takeIf { it <= MAX_SECONDS }?.roundToInt()
            if (lowSeconds <= 0) continue
            found.add(Match(m.range.first, m.value.length, lowSeconds, highSeconds, label(lowSeconds, highSeconds)))
        }
        for (m in compact.findAll(text)) {
            if (overlaps(m.range, found)) continue
            var seconds = 0
            val hours = m.groups[1]?.value?.toIntOrNull()
            if (m.groups[1] != null && (hours == null || hours > 1000)) continue
            if (hours != null) {
                seconds = hours * 3600
                m.groups[2]?.value?.toIntOrNull()?.let { seconds += it * 60 }
            } else {
                val value = m.groups[3]?.value?.toIntOrNull()
                val unit = m.groups[4]?.value
                if (value != null && unit != null) seconds = if (unit.lowercase() == "m") value * 60 else value
            }
            if (seconds <= 0) continue
            found.add(Match(m.range.first, m.value.length, seconds, null, label(seconds, null)))
        }

        found.sortBy { it.location }
        return mergeCompound(found, text)
    }

    /** Minutes for the first duration in the text, or null. A bare number means minutes. */
    fun minutes(text: String): Int? {
        val first = if (text.isEmpty()) null else matches(text).firstOrNull()
        if (first == null) {
            val n = text.trim().toIntOrNull()
            return if (n != null && n > 0) n else null
        }
        return max(1, (first.seconds / 60.0).roundToInt())
    }

    /** Normalizes time metadata: "1 hour 30 minutes" → "1 hr 30 min". Unparseable text is kept. */
    fun normalized(text: String): String {
        val trimmed = text.trim()
        val found = matches(trimmed)
        return if (found.size == 1) found[0].label else trimmed
    }

    fun formatMinutes(minutes: Int): String {
        if (minutes < 60) return "$minutes min"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "$h hr" else "$h hr $m min"
    }

    fun formatSeconds(seconds: Int): String {
        if (seconds < 60) return "$seconds sec"
        if (seconds % 60 == 0) return formatMinutes(seconds / 60)
        val m = seconds / 60
        val s = seconds % 60
        return if (m < 60) "$m min $s sec" else formatMinutes(m)
    }

    /** Countdown text such as "4:05" or "1:02:30". */
    fun clock(seconds: Int): String {
        val s = max(0, seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun label(low: Int, high: Int?): String {
        if (high == null || high <= low) return formatSeconds(low)
        if (low % 3600 == 0 && high % 3600 == 0) return "${low / 3600}–${high / 3600} hr"
        if (low % 60 == 0 && high % 60 == 0 && high < 3600) return "${low / 60}–${high / 60} min"
        return "${formatSeconds(low)}–${formatSeconds(high)}"
    }

    private fun unitSeconds(unit: String): Double {
        val u = unit.lowercase()
        return when {
            u.startsWith("h") -> 3600.0
            u.startsWith("m") -> 60.0
            else -> 1.0
        }
    }

    private fun overlaps(range: IntRange, existing: List<Match>): Boolean = existing.any {
        val start = it.location
        val end = it.location + it.length - 1
        range.first <= end && start <= range.last
    }

    /** Joins "1 hour" + "15 minutes" when only "and", "," or spaces separate them. */
    private fun mergeCompound(matches: List<Match>, text: String): List<Match> {
        val result = ArrayList<Match>()
        for (match in matches) {
            val last = result.lastOrNull()
            if (last != null && last.upperSeconds == null && match.upperSeconds == null &&
                last.seconds % 3600 == 0 && match.seconds < 3600
            ) {
                val gapStart = last.location + last.length
                val gap = text.substring(gapStart, max(gapStart, match.location))
                val cleaned = gap.lowercase().replace("and", "").trim(' ', ',', '&')
                if (cleaned.isEmpty() && gap.length <= 6) {
                    val total = last.seconds + match.seconds
                    result[result.size - 1] = Match(
                        last.location, match.location + match.length - last.location, total, null, formatSeconds(total)
                    )
                    continue
                }
            }
            result.add(match)
        }
        return result
    }
}
