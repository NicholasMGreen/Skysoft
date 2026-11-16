package com.skysoft.data.skyblock

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.skysoft.SkysoftMod
import com.skysoft.config.SkysoftConfigGui
import com.skysoft.data.hypixel.HypixelLocationState
import com.skysoft.data.hypixel.SkyBlockProfileApi
import com.skysoft.utils.SkysoftClientEvents
import com.skysoft.utils.net.KeyedAsyncRequestSlots
import com.skysoft.utils.net.RefreshSchedule
import com.skysoft.utils.net.isCancellationFailure
import java.util.Locale
import kotlin.math.roundToLong
import net.minecraft.client.Minecraft

object EliteLeaderboardClient {
    private val config get() = SkysoftConfigGui.config().gui.information.eliteLeaderboard
    private val profileSlots = KeyedAsyncRequestSlots<String, Map<String, String>>(
        completionExecutor = Minecraft.getInstance(),
    )
    private val rankSlots = KeyedAsyncRequestSlots<RankLookupKey, EliteLeaderboardSnapshot>(
        completionExecutor = Minecraft.getInstance(),
    )
    private val profilesByPlayer = mutableMapOf<String, Map<String, String>>()
    private val profileSchedules = mutableMapOf<String, RefreshSchedule>()
    private val snapshots = mutableMapOf<RankLookupKey, EliteLeaderboardSnapshot>()
    private val rankSchedules = mutableMapOf<RankLookupKey, RefreshSchedule>()
    private val localOffsets = mutableMapOf<RankLookupKey, Double>()

    fun register() {
        SkyBlockProfileApi.registerConsumer("Elite Leaderboard") { isEnabled() }
        SkyBlockProfileApi.onProfileChange("Elite Leaderboard profile", ::isEnabled) { profileKey ->
            if (profileKey != null) refreshActiveIfDue()
        }
        HypixelLocationState.onChange("Elite Leaderboard location", ::isEnabled) { location ->
            if (location.inSkyBlock) refreshActiveIfDue()
        }
        SkysoftClientEvents.onEndTick("Elite Leaderboard tick", ::isEnabled) {
            refreshActiveIfDue()
        }
        SkysoftClientEvents.onDisconnect("Elite Leaderboard disconnect", ::cancelInFlight)
        SkysoftClientEvents.onClientStopping("Elite Leaderboard shutdown") { cancelInFlight() }
    }

    internal fun isTrackingLocalProgress(): Boolean = isEnabled() && config.settings.trackLocalProgress

    internal fun activeBoardId(): String {
        val settings = config.settings
        if (settings.followActiveSlayerQuest) {
            SlayerQuestState.scoreboardSlayerType?.let { return slayerBoardId(it) }
        }
        return normalizeEliteBoardId(settings.boardId)
    }

    internal fun snapshotFor(boardId: String): EliteLeaderboardSnapshot? {
        val playerUuid = currentPlayerUuid() ?: return null
        val profileName = SkyBlockProfileApi.currentProfileKey ?: return null
        val profileId = profilesByPlayer[playerUuid]?.get(profileName) ?: return null
        val info = EliteLeaderboardCatalog.info(boardId)
        val key = lookupKey(playerUuid, profileId, info)
        val snapshot = snapshots[key] ?: return null
        if (!config.settings.trackLocalProgress) return snapshot.withoutAnchoredUpcoming()
        val localGain = localOffsets[key] ?: 0.0
        return snapshot.withLocalProgress(localGain)
    }

    internal fun addLocalProgress(boardIds: Iterable<String>, delta: Double) {
        if (!isTrackingLocalProgress() || delta <= 0.0) return
        val playerUuid = currentPlayerUuid() ?: return
        val profileName = SkyBlockProfileApi.currentProfileKey ?: return
        val profileId = profilesByPlayer[playerUuid]?.get(profileName) ?: return
        for (boardId in boardIds) {
            val info = EliteLeaderboardCatalog.info(boardId)
            val key = lookupKey(playerUuid, profileId, info)
            if (key in snapshots) {
                localOffsets[key] = (localOffsets[key] ?: 0.0) + delta
            }
        }
    }

    private fun isEnabled(): Boolean = config.enabled

    private fun refreshActiveIfDue() {
        if (!HypixelLocationState.inSkyBlock || !isEnabled()) return
        EliteLeaderboardCatalog.ensureLoaded()
        val playerUuid = currentPlayerUuid() ?: return
        val profileName = SkyBlockProfileApi.currentProfileKey ?: return
        val profileId = profilesByPlayer[playerUuid]?.get(profileName)
        if (profileId == null) {
            fetchProfilesIfDue(playerUuid)
            return
        }
        val info = EliteLeaderboardCatalog.info(activeBoardId())
        val key = lookupKey(playerUuid, profileId, info)
        val schedule = rankSchedules.getOrPut(key, ::RefreshSchedule)
        if (schedule.isDue(System.currentTimeMillis())) fetchRank(key, info)
    }

    private fun fetchProfilesIfDue(playerUuid: String) {
        val schedule = profileSchedules.getOrPut(playerUuid, ::RefreshSchedule)
        if (!schedule.isDue(System.currentTimeMillis())) return
        val url = "${EliteLeaderboardCatalog.API_BASE_URL}/profiles/$playerUuid"
        profileSlots.startIfIdle(
            playerUuid,
            requestFactory = { EliteLeaderboardCatalog.fetchJson(url, ::parseProfilesMap) },
        ) { profiles, error ->
            val now = System.currentTimeMillis()
            if (error == null && profiles != null) {
                profilesByPlayer[playerUuid] = profiles
                schedule.schedule(now, REFRESH_INTERVAL_MILLIS)
                refreshActiveIfDue()
            } else if (error?.isCancellationFailure() != true) {
                schedule.schedule(now, FAILURE_RETRY_MILLIS)
                SkysoftMod.LOGGER.warn("Failed to resolve Elite SkyBlock profiles", error)
            }
        }
    }

    private fun fetchRank(key: RankLookupKey, info: EliteLeaderboardInfo) {
        val schedule = rankSchedules.getOrPut(key, ::RefreshSchedule)
        val url = buildRankUrl(key, info)
        rankSlots.startIfIdle(
            key,
            requestFactory = {
                EliteLeaderboardCatalog.fetchJson(url) { body -> parseLeaderboardRank(info.id, body) }
            },
        ) { snapshot, error ->
            val now = System.currentTimeMillis()
            if (error == null && snapshot != null) {
                reconcileLocalOffset(key, snapshots[key], snapshot)
                snapshots[key] = snapshot
                schedule.schedule(now, REFRESH_INTERVAL_MILLIS)
            } else if (error?.isCancellationFailure() != true) {
                schedule.schedule(now, FAILURE_RETRY_MILLIS)
                SkysoftMod.LOGGER.warn("Failed to refresh Elite SkyBlock leaderboard ${info.id}", error)
            }
        }
    }

    private fun buildRankUrl(key: RankLookupKey, info: EliteLeaderboardInfo): String {
        val previous = snapshots[key]
        val localGain = if (config.settings.trackLocalProgress) localOffsets[key] ?: 0.0 else 0.0
        val baseUrl = if (info.isProfileBoard) {
            "${EliteLeaderboardCatalog.API_BASE_URL}/leaderboard/${info.id}/${key.profileId}"
        } else {
            "${EliteLeaderboardCatalog.API_BASE_URL}/leaderboard/${info.id}/${key.playerScopeUuid}/${key.profileId}"
        }
        if (previous != null && previous.isRanked && localGain > PROGRESS_EPSILON) {
            val localTotal = formatAtAmount(previous.amount + localGain, info.scoreDataType)
            return "$baseUrl?upcoming=$UPCOMING_COUNT&atAmount=$localTotal"
        }
        return "$baseUrl?upcoming=$UPCOMING_COUNT"
    }

    private fun formatAtAmount(value: Double, scoreDataType: EliteScoreDataType): String = when (scoreDataType) {
        EliteScoreDataType.LONG -> value.roundToLong().toString()
        EliteScoreDataType.DECIMAL -> String.format(Locale.US, "%.2f", value)
    }

    private fun reconcileLocalOffset(
        key: RankLookupKey,
        previous: EliteLeaderboardSnapshot?,
        current: EliteLeaderboardSnapshot,
    ) {
        if (previous == null) {
            localOffsets.remove(key)
            return
        }
        if (current.amount == previous.amount) return
        if (current.amount < previous.amount) {
            localOffsets.remove(key)
            return
        }
        val previousOffset = localOffsets[key] ?: 0.0
        val serverDelta = current.amount - previous.amount
        val remainingOffset = (previousOffset - serverDelta).coerceAtLeast(0.0)
        if (remainingOffset > PROGRESS_EPSILON) {
            localOffsets[key] = remainingOffset
        } else {
            localOffsets.remove(key)
        }
    }

    private fun cancelInFlight() {
        profileSlots.cancelAll()
        rankSlots.cancelAll()
    }

    private fun lookupKey(playerUuid: String, profileId: String, info: EliteLeaderboardInfo): RankLookupKey =
        RankLookupKey(
            playerScopeUuid = if (info.isProfileBoard) "" else playerUuid,
            profileId = profileId,
            boardId = info.id,
        )

    private fun currentPlayerUuid(): String? =
        Minecraft.getInstance().player?.uuid?.toString()?.replace("-", "")

    private const val REFRESH_INTERVAL_MILLIS = 10L * 60L * 1000L
    private const val FAILURE_RETRY_MILLIS = 60L * 1000L
    private const val UPCOMING_COUNT = 20
    private const val PROGRESS_EPSILON = 1e-6
}

internal data class EliteLeaderboardAhead(
    val ign: String,
    val amount: Double,
    val rank: Int,
)

internal data class EliteLeaderboardSnapshot(
    val boardId: String,
    val rank: Int,
    val amount: Double,
    val minAmount: Double,
    val upcomingRank: Int,
    val upcomingPlayers: List<EliteLeaderboardAhead>,
) {
    val isRanked: Boolean get() = rank > 0 && amount >= minAmount
    val ahead: EliteLeaderboardAhead? get() = upcomingPlayers.firstOrNull()

    fun withoutAnchoredUpcoming(): EliteLeaderboardSnapshot {
        val next = upcomingPlayers.firstOrNull()
        return if (rank > 0 && next != null && next.rank != rank - 1) {
            copy(upcomingRank = 0, upcomingPlayers = emptyList())
        } else {
            this
        }
    }

    fun withLocalProgress(localGain: Double): EliteLeaderboardSnapshot {
        if (localGain <= 0.0) return this
        val updatedAmount = amount + localGain
        if (!isRanked) return copy(amount = updatedAmount)
        val nextIndex = upcomingPlayers.indexOfFirst { it.amount > updatedAmount }
        return when {
            nextIndex >= 0 -> {
                val nextAhead = upcomingPlayers[nextIndex]
                val updatedRank = nextAhead.rank.takeIf { it > 0 }?.plus(1) ?: rank
                copy(
                    rank = updatedRank,
                    amount = updatedAmount,
                    upcomingRank = nextAhead.rank,
                    upcomingPlayers = upcomingPlayers.subList(nextIndex, upcomingPlayers.size),
                )
            }
            upcomingPlayers.isNotEmpty() -> {
                val lastPassed = upcomingPlayers.last()
                val updatedRank = lastPassed.rank.takeIf { it in 1 until rank } ?: rank
                copy(rank = updatedRank, amount = updatedAmount, upcomingRank = 0, upcomingPlayers = emptyList())
            }
            upcomingRank == 0 -> copy(rank = 1, amount = updatedAmount, upcomingPlayers = emptyList())
            else -> copy(amount = updatedAmount)
        }
    }
}

private data class RankLookupKey(
    val playerScopeUuid: String,
    val profileId: String,
    val boardId: String,
)

private fun parseProfilesMap(body: String): Map<String, String> {
    val array = JsonParser.parseString(body).takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
    val matched = linkedMapOf<String, Pair<String, Boolean>>()
    array.asSequence()
        .mapNotNull { it.takeIf { elem -> elem.isJsonObject }?.asJsonObject }
        .forEach { obj ->
            val profileId = obj.stringOrNull("profileId")
            val profileName = obj.stringOrNull("profileName")?.trim()?.lowercase(Locale.US)
            if (profileId != null && !profileName.isNullOrEmpty()) {
                val selected = obj.get("selected")?.takeUnless { it.isJsonNull }?.asBoolean == true
                val existing = matched[profileName]
                if (existing == null || (selected && !existing.second)) {
                    matched[profileName] = profileId to selected
                }
            }
        }
    return matched.mapValues { it.value.first }
}

private fun parseLeaderboardRank(boardId: String, body: String): EliteLeaderboardSnapshot {
    val json = JsonParser.parseString(body).asJsonObject
    val rank = json.intOrNull("rank") ?: -1
    val amount = json.doubleOrNull("amount") ?: 0.0
    val minAmount = json.doubleOrNull("minAmount") ?: 0.0
    val upcomingRank = json.intOrNull("upcomingRank") ?: 0
    val upcomingPlayers = json.arrayOrNull("upcomingPlayers")
        ?.mapNotNull { element ->
            val player = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val displayName = player.stringOrNull("ign") ?: player.stringOrNull("profile") ?: return@mapNotNull null
            val playerAmount = player.doubleOrNull("amount") ?: return@mapNotNull null
            val playerRank = player.intOrNull("rank") ?: -1
            EliteLeaderboardAhead(ign = displayName, amount = playerAmount, rank = playerRank)
        }
        .orEmpty()
    return EliteLeaderboardSnapshot(
        boardId = boardId,
        rank = rank,
        amount = amount,
        minAmount = minAmount,
        upcomingRank = upcomingRank,
        upcomingPlayers = upcomingPlayers,
    )
}

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.asString

private fun JsonObject.intOrNull(name: String): Int? =
    get(name)?.takeUnless { it.isJsonNull }?.asInt

private fun JsonObject.doubleOrNull(name: String): Double? =
    get(name)?.takeUnless { it.isJsonNull }?.asDouble

private fun JsonObject.arrayOrNull(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray
