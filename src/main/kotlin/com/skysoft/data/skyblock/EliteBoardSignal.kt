package com.skysoft.data.skyblock

import com.skysoft.data.SkyBlockIsland
import java.util.Locale

/**
 * The in-client signal that can advance a board between Elite fetches.
 *
 * A board owns exactly one signal, derived purely from Elite's catalog metadata, so adding a new
 * board to Elite never requires a code change here.
 */
internal enum class EliteBoardSignal {
    /** A specific mob family was killed (Bestiary plus Garden critters). */
    MOB_KILL,

    /** Aggregate Garden pest kill count. */
    PEST_KILL,

    /** SkyBlock collection gains. */
    COLLECTION,

    /** Skill experience. */
    SKILL_XP,

    /** Slayer experience. */
    SLAYER_XP,

    /** No in-client signal exists; the board renders the Elite snapshot only. */
    NONE,
}

/**
 * Normalises a mob or Bestiary family name so catalog ids and in-game nameplates meet in the middle.
 *
 * Apostrophes and hyphens are the reason this exists: Elite's `combat_1:arachnes_brood` must match the
 * nameplate `Arachne's Brood`, and `crimson_isle:millennia_aged_blaze` must match `Millennia-Aged Blaze`.
 */
internal fun eliteMobFamilyKey(name: String): String = name
    .lowercase(Locale.ROOT)
    .replace("'", "")
    .replace("\u2019", "")
    .replace(MOB_KEY_SEPARATOR, " ")
    .trim()

/**
 * Join key for collection boards.
 *
 * Inventory changes report raw NBT ids (`INK_SACK:3`) while sack and item-name lookups report
 * canonical ids (`INK_SACK-3`). Normalising both sides here is what makes the ten colon-bearing
 * collections (cocoa, lapis, red sand, the logs, the fish) actually accumulate.
 */
internal fun eliteCollectionJoinKey(itemId: String): String =
    itemId.trim().uppercase(Locale.ROOT).replace(':', '-')

/** `mining_3:glacite_walker` -> `glacite walker`. */
internal fun eliteBestiaryFamilyKey(bestiaryId: String): String =
    eliteMobFamilyKey(bestiaryId.substringAfter(':', bestiaryId))

/** Maps an Elite bestiary bucket (`mining_3`) to the island it belongs to, when it is island-specific. */
internal fun eliteBestiaryIsland(bestiaryId: String): SkyBlockIsland? {
    val bucket = bestiaryId.substringBefore(':', bestiaryId)
    return SkyBlockIsland.getByLocation(bucket, null) ?: BESTIARY_BUCKET_ISLAND_ALIASES[bucket]
}

/** Strips Elite's interval suffix so `farming-monthly` and `farming` share one binding key. */
internal fun eliteBoardBaseId(boardId: String): String =
    boardId.removeSuffix(MONTHLY_SUFFIX).removeSuffix(WEEKLY_SUFFIX)

/**
 * The one signal that can advance [info], derived only from Elite's catalog metadata.
 *
 * Precedence matters: a Garden pest board carries a `bestiary` id (`garden:mite`), so it resolves to
 * [EliteBoardSignal.MOB_KILL] like any other bestiary board. [EliteBoardSignal.PEST_KILL] is reserved
 * for the aggregate `pests` boards, which is why it is only reached when there is no `bestiary` id.
 */
internal fun eliteBoardSignal(info: EliteLeaderboardInfo): EliteBoardSignal = when {
    info.bestiaryId != null -> EliteBoardSignal.MOB_KILL
    info.collectionId != null -> EliteBoardSignal.COLLECTION
    info.category == SKILLS_CATEGORY -> EliteBoardSignal.SKILL_XP
    info.category == SLAYERS_CATEGORY -> EliteBoardSignal.SLAYER_XP
    info.category == PESTS_CATEGORY -> EliteBoardSignal.PEST_KILL
    else -> EliteBoardSignal.NONE
}

/** Slug used to match a skill gain to its board, e.g. `farming-monthly` -> `farming`. */
internal fun eliteSkillSlug(info: EliteLeaderboardInfo): String? =
    eliteBoardBaseId(info.id).takeIf { info.category == SKILLS_CATEGORY }

/** Slug used to match a slayer completion to its board, e.g. `blaze-slayer` -> `blaze`. */
internal fun eliteSlayerSlug(info: EliteLeaderboardInfo): String? {
    if (info.category != SLAYERS_CATEGORY) return null
    val baseId = eliteBoardBaseId(info.id)
    if (baseId == TOTAL_SLAYER_BOARD_ID) return null
    return baseId.removeSuffix(SLAYER_SUFFIX).takeIf(String::isNotEmpty)
}

private val MOB_KEY_SEPARATOR = Regex("""[^a-z0-9]+""")

internal const val MONTHLY_SUFFIX = "-monthly"
internal const val WEEKLY_SUFFIX = "-weekly"
internal const val SLAYER_SUFFIX = "-slayer"
internal const val TOTAL_SLAYER_BOARD_ID = "slayer-xp"
internal const val GARDEN_BESTIARY_BUCKET = "garden"
internal const val SKILLS_CATEGORY = "Skills"
internal const val SLAYERS_CATEGORY = "Slayers"
internal const val PESTS_CATEGORY = "Pests"

private val BESTIARY_BUCKET_ISLAND_ALIASES = mapOf(
    "catacombs" to SkyBlockIsland.DUNGEONS,
    "jerry" to SkyBlockIsland.JERRYS_WORKSHOP,
)
