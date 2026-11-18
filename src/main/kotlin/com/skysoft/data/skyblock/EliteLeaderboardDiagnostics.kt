package com.skysoft.data.skyblock

import com.skysoft.SkysoftMod

/**
 * Debug logging for the Elite overlay.
 *
 * Kept out of `EliteLeaderboardClient` both to respect detekt's per-object function budget and
 * because these lines exist purely to make silent metadata fallback visible: without them a board
 * resolved from the fallback table looks identical to one resolved from the live catalog.
 */
internal object EliteLeaderboardDiagnostics {
    private var lastReportedBoardId: String? = null
    private var lastReportedSnapshotId: String? = null

    fun logActiveBinding(info: EliteLeaderboardInfo) {
        if (!SkysoftMod.LOGGER.isDebugEnabled || info.id == lastReportedBoardId) return
        lastReportedBoardId = info.id
        SkysoftMod.LOGGER.debug(
            "Elite active board {} -> {}({}) via {}",
            info.id,
            eliteBoardSignal(info),
            info.bestiaryId ?: info.collectionId ?: "-",
            EliteLeaderboardCatalog.sourceOf(info.id),
        )
    }

    fun logLocalGain(gain: EliteBoardGain, total: Double, anchored: Boolean) {
        if (!SkysoftMod.LOGGER.isDebugEnabled) return
        SkysoftMod.LOGGER.debug(
            "Elite local gain {} +{} (total {}) {}",
            gain.boardId,
            gain.amount,
            total,
            if (anchored) "applied to snapshot" else "pending first fetch",
        )
    }

    /**
     * Logged at INFO whenever the active board's snapshot first arrives, because "the overlay shows
     * nothing useful" is otherwise indistinguishable between an unranked board, a board below its
     * minimum score, and a failed fetch. [source] names where the metadata came from.
     */
    fun logSnapshot(boardId: String, snapshot: EliteLeaderboardSnapshot, localGain: Double, source: String) {
        val key = "$boardId|${snapshot.rank}|${snapshot.amount}|$localGain"
        if (key == lastReportedSnapshotId) return
        lastReportedSnapshotId = key
        SkysoftMod.LOGGER.info(
            "Elite snapshot {} via {}: rank={} amount={} min={} localGain={} ranked={}",
            boardId,
            source,
            snapshot.rank,
            snapshot.amount,
            snapshot.minAmount,
            localGain,
            snapshot.isRanked,
        )
    }

    fun reset() {
        lastReportedBoardId = null
        lastReportedSnapshotId = null
    }

    /**
     * A signal that resolved to no board. Logged because it is otherwise completely silent: the kill
     * happened, nothing changed on screen, and there is no evidence of which stage dropped it.
     */
    fun logSignalDropped(signal: EliteLocalSignal) {
        SkysoftMod.LOGGER.info("Elite signal {} matched no board", describe(signal))
    }

    fun logSignalRouted(signal: EliteLocalSignal, gains: List<EliteBoardGain>) {
        if (!SkysoftMod.LOGGER.isDebugEnabled) return
        SkysoftMod.LOGGER.debug(
            "Elite signal {} -> {}",
            describe(signal),
            gains.joinToString { "${it.boardId}+${it.amount}" },
        )
    }

    private fun describe(signal: EliteLocalSignal): String = when (signal) {
        is EliteLocalSignal.MobKill -> "mob='${signal.mobName}' island=${signal.island}"
        is EliteLocalSignal.PestKill -> "pest='${signal.pestName}'"
        is EliteLocalSignal.CollectionGain -> "item='${signal.itemId}' amount=${signal.amount}"
        is EliteLocalSignal.SkillXp -> "skill='${signal.skillSlug}' amount=${signal.amount}"
        is EliteLocalSignal.SlayerXp -> "slayer='${signal.slayerSlug}' amount=${signal.amount}"
    }
}
