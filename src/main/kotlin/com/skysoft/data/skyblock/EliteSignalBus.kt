package com.skysoft.data.skyblock

import com.skysoft.data.SkyBlockIsland

/**
 * A locally observed gain that may advance one or more Elite boards.
 *
 * Sources publish these without knowing which board is displayed; [EliteBoardBindingRouter] decides
 * which boards (plural, including interval variants) the gain belongs to.
 */
internal sealed interface EliteLocalSignal {
    val amount: Double

    data class MobKill(val mobName: String, val island: SkyBlockIsland?, override val amount: Double = 1.0) :
        EliteLocalSignal

    data class PestKill(val pestName: String, override val amount: Double = 1.0) : EliteLocalSignal

    data class CollectionGain(val itemId: String, override val amount: Double) : EliteLocalSignal

    data class SkillXp(val skillSlug: String, override val amount: Double) : EliteLocalSignal

    data class SlayerXp(val slayerSlug: String, override val amount: Double) : EliteLocalSignal
}

/**
 * Fan-out point between signal sources and the leaderboard client.
 *
 * Every publisher calls [publish], which routes the signal to the active local-progress consumer. The
 * bus deliberately holds no board state so that a signal is never dropped just because no board has
 * been fetched yet.
 */
internal object EliteSignalBus {
    private var consumer: ((List<EliteBoardGain>) -> Unit)? = null

    fun subscribe(listener: (List<EliteBoardGain>) -> Unit) {
        consumer = listener
    }

    fun unsubscribe() {
        consumer = null
    }

    fun publish(signal: EliteLocalSignal) {
        if (signal.amount <= 0.0) return
        val gains = EliteBoardBindingRouter.resolve(signal)
        if (gains.isEmpty()) {
            EliteLeaderboardDiagnostics.logSignalDropped(signal)
            return
        }
        EliteLeaderboardDiagnostics.logSignalRouted(signal, gains)
        consumer?.invoke(gains)
    }
}

/** One board's share of a local gain. */
internal data class EliteBoardGain(val boardId: String, val amount: Double)
