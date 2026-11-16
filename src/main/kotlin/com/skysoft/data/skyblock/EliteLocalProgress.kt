package com.skysoft.data.skyblock

import com.skysoft.features.combat.EliteBestiaryKillTracker
import java.util.Locale

internal object EliteLocalProgress {
    fun register() {
        MayorPerkApi.registerConsumer("Elite Leaderboard Local Progress") {
            EliteLeaderboardClient.isTrackingLocalProgress() &&
                EliteLeaderboardClient.activeBoardId() in SLAYER_BOARD_IDS
        }
        SlayerQuestState.onQuestComplete("Elite Leaderboard Slayer XP", ::onSlayerQuestComplete)
        GardenPestState.onKill(
            "Elite Leaderboard Pest Kills",
            isActive = { EliteLeaderboardClient.isTrackingLocalProgress() },
            listener = ::onPestKill,
        )
        SkillExpGainApi.onSkillExpGain(
            "Elite Leaderboard Skill XP",
            isActive = { EliteLeaderboardClient.isTrackingLocalProgress() },
            listener = ::onSkillExpGain,
        )
        EliteCollectionProgress.register()
        EliteBestiaryKillTracker.register()
    }

    private fun onSlayerQuestComplete(quest: SlayerQuestSnapshot) {
        if (!EliteLeaderboardClient.isTrackingLocalProgress()) return
        val slayerType = quest.slayerType ?: return
        val tier = quest.tier ?: return
        val gainedXp = slayerQuestXp(slayerType, tier, MayorPerkApi.slayerXpBuffActive)
        if (gainedXp <= 0L) return
        EliteLeaderboardClient.addLocalProgress(
            listOf(slayerBoardId(slayerType), TOTAL_SLAYER_BOARD_ID),
            gainedXp.toDouble(),
        )
    }

    private fun onPestKill(pestName: String) {
        if (!EliteLeaderboardClient.isTrackingLocalProgress()) return
        val boards = buildList {
            add("pests")
            add("pests-monthly")
            PEST_NAME_TO_BOARD_ID[pestName.trim().lowercase(Locale.ROOT)]?.let { slug ->
                add(slug)
                add("$slug-monthly")
            }
        }
        EliteLeaderboardClient.addLocalProgress(boards, 1.0)
    }

    private fun onSkillExpGain(event: SkillExpGainApi.SkillExpGain) {
        if (!EliteLeaderboardClient.isTrackingLocalProgress()) return
        val delta = when {
            !event.isFromActionBar -> event.gained
            event.totalXp != null && event.previousTotalXp != null ->
                (event.totalXp - event.previousTotalXp).coerceAtLeast(0.0)
            else -> 0.0
        }
        if (delta <= 0.0) return
        val slug = event.skill.name.lowercase(Locale.ROOT)
        EliteLeaderboardClient.addLocalProgress(listOf(slug, "$slug-monthly"), delta)
    }
}

internal fun slayerBoardId(slayerType: SkyBlockSlayerType): String = when (slayerType) {
    SkyBlockSlayerType.ZOMBIE -> "zombie-slayer"
    SkyBlockSlayerType.SPIDER -> "spider-slayer"
    SkyBlockSlayerType.WOLF -> "wolf-slayer"
    SkyBlockSlayerType.ENDERMAN -> "enderman-slayer"
    SkyBlockSlayerType.BLAZE -> "blaze-slayer"
    SkyBlockSlayerType.VAMPIRE -> "vampire-slayer"
}

private fun slayerQuestXp(slayerType: SkyBlockSlayerType, tier: Int, mayorXpBuffActive: Boolean): Long {
    val table = if (slayerType == SkyBlockSlayerType.VAMPIRE) VAMPIRE_SLAYER_XP_BY_TIER else STANDARD_SLAYER_XP_BY_TIER
    val baseXp = table.getOrNull(tier - 1) ?: return 0L
    return if (mayorXpBuffActive) (baseXp * AATROX_XP_NUMERATOR) / AATROX_XP_DENOMINATOR else baseXp
}

private const val TOTAL_SLAYER_BOARD_ID = "slayer-xp"
private val SLAYER_BOARD_IDS = setOf(
    "zombie-slayer",
    "spider-slayer",
    "wolf-slayer",
    "enderman-slayer",
    "blaze-slayer",
    "vampire-slayer",
    TOTAL_SLAYER_BOARD_ID,
)

private val STANDARD_SLAYER_XP_BY_TIER = longArrayOf(5L, 25L, 100L, 500L, 1_500L)
private val VAMPIRE_SLAYER_XP_BY_TIER = longArrayOf(10L, 25L, 60L, 120L, 150L)
private const val AATROX_XP_NUMERATOR = 5L
private const val AATROX_XP_DENOMINATOR = 4L

private val PEST_NAME_TO_BOARD_ID = mapOf(
    "mite" to "mite",
    "cricket" to "cricket",
    "moth" to "moth",
    "earthworm" to "earthworm",
    "slug" to "slug",
    "beetle" to "beetle",
    "locust" to "locust",
    "rat" to "rat",
    "mosquito" to "mosquito",
    "fly" to "fly",
    "field mouse" to "mouse",
    "lunar moth" to "lunar-moth",
    "dragonfly" to "dragonfly",
    "firefly" to "firefly",
    "praying mantis" to "mantis",
)
