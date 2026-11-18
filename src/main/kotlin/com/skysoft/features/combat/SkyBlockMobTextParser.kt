package com.skysoft.features.combat

import com.skysoft.utils.NumberUtilities.parseCompactNumberOrNull

internal data class SkyBlockMobHealth(
    val current: Long,
    val max: Long?,
)

internal object SkyBlockMobTextParser {
    fun parseHealth(text: String): SkyBlockMobHealth? =
        healthMatch(text)?.health

    fun parseName(text: String): String? {
        val match = healthMatch(text) ?: run {
            // No health line on the nameplate. Take the whole label as the name rather than
            // rejecting the mob: several Bestiary mobs (Ghosts among them) often show a bare name,
            // and rejecting them means their kills are never tracked at all.
            val typedName = typedNamePattern.matchEntire(text)
                ?.takeIf { CRITTER_TYPE_GLYPH in it.groupValues[1] }
                ?.groups?.get("name")?.value?.trim()?.takeIf(String::isNotEmpty)
            return typedName ?: nameWithoutHealthLine(text)
        }
        return levelPrefixPattern
            .replace(text.take(match.start).trim(), "")
            .trimStart { !it.isLetterOrDigit() }
            .trim()
            .takeIf(String::isNotEmpty)
    }

    /** Strips a trailing level/tier marker from a nameplate that has no health segment. */
    private fun nameWithoutHealthLine(text: String): String? = levelPrefixPattern
        .replace(text.trim(), "")
        .trimStart { !it.isLetterOrDigit() }
        .trim()
        .takeIf { it.isNotEmpty() && it.none(Char::isDigit) }

    private fun healthMatch(text: String): HealthMatch? {
        fullHealthPattern.find(text)?.let { match ->
            val current = match.groups["current"]?.value?.parseCompactNumber()
            val max = match.groups["max"]?.value?.parseCompactNumber()
            if (current != null && max != null) return HealthMatch(match.range.first, SkyBlockMobHealth(current, max))
        }
        return currentHealthPattern.find(text)?.let { match ->
            match.groups["current"]?.value?.parseCompactNumber()?.let { current ->
                HealthMatch(match.range.first, SkyBlockMobHealth(current, null))
            }
        }
    }

    private fun String.parseCompactNumber(): Long? = parseCompactNumberOrNull()?.value?.toLong()

    private data class HealthMatch(
        val start: Int,
        val health: SkyBlockMobHealth,
    )

    private val fullHealthPattern = Regex(
        """(?<current>[0-9,.]+[KMBkmb]?)(?:§.)?\s*/\s*(?<max>[0-9,.]+[KMBkmb]?)""",
    )
    private val currentHealthPattern = Regex("""(?<current>[0-9,.]+[KMBkmb]?)(?:§.)?❤""")
    private val typedNamePattern = Regex("""\[Lv\d+] (?<types>\p{Co}+) (?<name>.+)""")
    private val levelPrefixPattern = Regex("""^\[Lv\d+]\s*""", RegexOption.IGNORE_CASE)
    private const val CRITTER_TYPE_GLYPH = '\uE087'
}
