package com.skysoft.data.skyblock

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/**
 * Buffer for local gains observed before the player's Elite profile id is known.
 *
 * A kill in the first seconds after a join would otherwise be dropped while `/profiles/{uuid}` is
 * still in flight. Extracted from `EliteLeaderboardClient` to keep that object under detekt's
 * per-object function budget.
 */
internal class ElitePendingGains {
    private val gains = mutableMapOf<String, Double>()

    val isEmpty: Boolean
        get() = gains.isEmpty()

    fun add(boardId: String, amount: Double) {
        if (amount <= 0.0) return
        gains[boardId] = (gains[boardId] ?: 0.0) + amount
    }

    /** Removes and returns the buffered totals, keyed by board. */
    fun drain(): Map<String, Double> {
        if (gains.isEmpty()) return emptyMap()
        val buffered = gains.toMap()
        gains.clear()
        return buffered
    }

    fun clear() = gains.clear()
}

/**
 * Resolved Elite profile ids for one player.
 *
 * [byName] is keyed by Elite's profile name. [selected] is the id Elite itself flags as the active
 * profile, used as a fallback when the locally detected profile name does not match any entry (a
 * renamed profile, or a chat line carrying a repeat annotation such as `Pineapple (×7)`).
 */
internal data class EliteProfileIndex(
    val byName: Map<String, String>,
    val selected: String?,
) {
    fun resolve(profileName: String?): String? =
        profileName?.let { byName[it.trim().lowercase(Locale.US)] } ?: selected

    companion object {
        val EMPTY = EliteProfileIndex(emptyMap(), null)
    }
}

/** Parses Elite's `/profiles/{uuid}` response, preferring the `selected: true` entry on name clashes. */
internal fun parseProfilesResponse(body: String): EliteProfileIndex {
    val array = JsonParser.parseString(body).takeIf { it.isJsonArray }?.asJsonArray
        ?: return EliteProfileIndex.EMPTY
    val matched = linkedMapOf<String, Pair<String, Boolean>>()
    var selectedId: String? = null
    array.asSequence()
        .mapNotNull { it.takeIf { elem -> elem.isJsonObject }?.asJsonObject }
        .forEach { obj ->
            val profileId = obj.eliteStringOrNull("profileId")
            val profileName = obj.eliteStringOrNull("profileName")?.trim()?.lowercase(Locale.US)
            if (profileId != null && !profileName.isNullOrEmpty()) {
                val selected = obj.get("selected")?.takeUnless { it.isJsonNull }?.asBoolean == true
                if (selected) selectedId = profileId
                val existing = matched[profileName]
                if (existing == null || (selected && !existing.second)) {
                    matched[profileName] = profileId to selected
                }
            }
        }
    return EliteProfileIndex(matched.mapValues { it.value.first }, selectedId)
}

private fun JsonObject.eliteStringOrNull(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.asString
