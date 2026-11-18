package com.skysoft.data.skyblock

import com.skysoft.features.combat.EliteBestiaryKillTracker
import java.util.Locale

/**
 * Wires in-client signal sources to [EliteSignalBus].
 *
 * Every listener here is active whenever local tracking is enabled, regardless of which board is
 * displayed. Board selection must never decide whether a signal is observed; the router decides
 * which boards a signal lands on.
 */
internal object EliteLocalProgress {
    fun register() {
        MayorPerkApi.registerConsumer("Elite Leaderboard Local Progress") {
            EliteLeaderboardClient.isTrackingLocalProgress()
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
        EliteSignalBus.publish(EliteLocalSignal.SlayerXp(slayerBoardSlug(slayerType), gainedXp.toDouble()))
    }

    private fun onPestKill(pestName: String) {
        if (!EliteLeaderboardClient.isTrackingLocalProgress()) return
        EliteSignalBus.publish(EliteLocalSignal.PestKill(pestName.trim()))
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
        EliteSignalBus.publish(EliteLocalSignal.SkillXp(event.skill.name.lowercase(Locale.ROOT), delta))
    }
}

/** Board slug (without the `-slayer` suffix) for a slayer type, e.g. `blaze`. */
internal fun slayerBoardSlug(slayerType: SkyBlockSlayerType): String = when (slayerType) {
    SkyBlockSlayerType.ZOMBIE -> "zombie"
    SkyBlockSlayerType.SPIDER -> "spider"
    SkyBlockSlayerType.WOLF -> "wolf"
    SkyBlockSlayerType.ENDERMAN -> "enderman"
    SkyBlockSlayerType.BLAZE -> "blaze"
    SkyBlockSlayerType.VAMPIRE -> "vampire"
}

internal fun slayerBoardId(slayerType: SkyBlockSlayerType): String = "${slayerBoardSlug(slayerType)}$SLAYER_SUFFIX"

private fun slayerQuestXp(slayerType: SkyBlockSlayerType, tier: Int, mayorXpBuffActive: Boolean): Long {
    val table = if (slayerType == SkyBlockSlayerType.VAMPIRE) VAMPIRE_SLAYER_XP_BY_TIER else STANDARD_SLAYER_XP_BY_TIER
    val baseXp = table.getOrNull(tier - 1) ?: return 0L
    return if (mayorXpBuffActive) (baseXp * AATROX_XP_NUMERATOR) / AATROX_XP_DENOMINATOR else baseXp
}

private val STANDARD_SLAYER_XP_BY_TIER = longArrayOf(5L, 25L, 100L, 500L, 1_500L)
private val VAMPIRE_SLAYER_XP_BY_TIER = longArrayOf(10L, 25L, 60L, 120L, 150L)
private const val AATROX_XP_NUMERATOR = 5L
private const val AATROX_XP_DENOMINATOR = 4L
