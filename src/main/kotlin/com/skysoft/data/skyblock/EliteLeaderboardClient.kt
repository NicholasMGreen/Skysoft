package com.skysoft.data.skyblock

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.skysoft.SkysoftMod
import com.skysoft.config.EliteSlayerBoard
import com.skysoft.config.SkysoftConfigGui
import com.skysoft.data.hypixel.HypixelLocationState
import com.skysoft.data.hypixel.SkyBlockProfileApi
import com.skysoft.utils.SkysoftClientEvents
import com.skysoft.utils.net.CancellableRequestGroup
import com.skysoft.utils.net.KeyedAsyncRequestSlots
import com.skysoft.utils.net.RefreshSchedule
import com.skysoft.utils.net.SkysoftHttp
import com.skysoft.utils.net.isCancellationFailure
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToLong
import net.minecraft.client.Minecraft

object EliteLeaderboardClient {
    private val config get() = SkysoftConfigGui.config().slayer.leaderboard
    private var requests = CancellableRequestGroup()
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
    private val localXpOffsets = mutableMapOf<RankLookupKey, Long>()

    fun register() {
        SkyBlockProfileApi.registerConsumer("Slayer Leaderboard") { isEnabled() }
        MayorPerkApi.registerConsumer("Slayer Leaderboard Local XP") { isEnabled() && config.settings.trackLocalXp }
        SkyBlockProfileApi.onProfileChange("Slayer Leaderboard profile", ::isEnabled) { profileKey ->
            if (profileKey != null) refreshActiveIfDue()
        }
        HypixelLocationState.onChange("Slayer Leaderboard location", ::isEnabled) { location ->
            if (location.inSkyBlock) refreshActiveIfDue()
        }
        SlayerQuestState.onQuestComplete("Slayer Leaderboard quest complete", ::onQuestComplete)
        SkysoftClientEvents.onEndTick("Slayer Leaderboard tick", ::isEnabled) {
            refreshActiveIfDue()
        }
        SkysoftClientEvents.onDisconnect("Slayer Leaderboard disconnect", ::cancelInFlight)
        SkysoftClientEvents.onClientStopping("Slayer Leaderboard shutdown") { cancelInFlight() }
    }

    internal fun snapshotFor(board: EliteSlayerBoard): EliteLeaderboardSnapshot? {
        val playerUuid = currentPlayerUuid() ?: return null
        val profileName = SkyBlockProfileApi.currentProfileKey ?: return null
        val profileId = profilesByPlayer[playerUuid]?.get(profileName) ?: return null
        val key = RankLookupKey(playerUuid, profileId, board.id)
        val snapshot = snapshots[key] ?: return null
        if (!config.settings.trackLocalXp) return snapshot.withoutAnchoredUpcoming()
        val localGain = localXpOffsets[key] ?: 0L
        return snapshot.withLocalXp(localGain)
    }

    private fun isEnabled(): Boolean = config.enabled

    private fun currentBoard(): EliteSlayerBoard {
        val settings = config.settings
        if (settings.followActiveQuest) {
            SlayerQuestState.scoreboardSlayerType?.let { return EliteSlayerBoard.fromSlayerType(it) }
        }
        return settings.board
    }

    private fun onQuestComplete(quest: SlayerQuestSnapshot) {
        if (!isEnabled() || !config.settings.trackLocalXp) return
        val slayerType = quest.slayerType ?: return
        val tier = quest.tier ?: return
        val gainedXp = slayerQuestXp(slayerType, tier, MayorPerkApi.slayerXpBuffActive)
        if (gainedXp <= 0L) return
        val playerUuid = currentPlayerUuid() ?: return
        val profileName = SkyBlockProfileApi.currentProfileKey ?: return
        val profileId = profilesByPlayer[playerUuid]?.get(profileName) ?: return
        val matchingBoards = listOf(EliteSlayerBoard.fromSlayerType(slayerType), EliteSlayerBoard.TOTAL)
        for (board in matchingBoards) {
            val key = RankLookupKey(playerUuid, profileId, board.id)
            if (key in snapshots) {
                localXpOffsets[key] = (localXpOffsets[key] ?: 0L) + gainedXp
            }
        }
    }

    private fun refreshActiveIfDue() {
        if (!HypixelLocationState.inSkyBlock || !isEnabled()) return
        val playerUuid = currentPlayerUuid() ?: return
        val profileName = SkyBlockProfileApi.currentProfileKey ?: return
        val profileId = profilesByPlayer[playerUuid]?.get(profileName)
        if (profileId == null) {
            fetchProfilesIfDue(playerUuid)
            return
        }
        val key = RankLookupKey(playerUuid, profileId, currentBoard().id)
        val schedule = rankSchedules.getOrPut(key, ::RefreshSchedule)
        if (schedule.isDue(System.currentTimeMillis())) fetchRank(key)
    }

    private fun fetchProfilesIfDue(playerUuid: String) {
        val schedule = profileSchedules.getOrPut(playerUuid, ::RefreshSchedule)
        if (!schedule.isDue(System.currentTimeMillis())) return
        profileSlots.startIfIdle(
            playerUuid,
            requestFactory = { fetchJson("$API_BASE_URL/profiles/$playerUuid", ::parseProfilesMap) },
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

    private fun fetchRank(key: RankLookupKey) {
        val schedule = rankSchedules.getOrPut(key, ::RefreshSchedule)
        val url = buildRankUrl(key)
        rankSlots.startIfIdle(
            key,
            requestFactory = { fetchJson(url) { body -> parseLeaderboardRank(key.boardId, body) } },
        ) { snapshot, error ->
            val now = System.currentTimeMillis()
            if (error == null && snapshot != null) {
                reconcileLocalOffset(key, snapshots[key], snapshot)
                snapshots[key] = snapshot
                schedule.schedule(now, REFRESH_INTERVAL_MILLIS)
            } else if (error?.isCancellationFailure() != true) {
                schedule.schedule(now, FAILURE_RETRY_MILLIS)
                SkysoftMod.LOGGER.warn("Failed to refresh Elite SkyBlock leaderboard ${key.boardId}", error)
            }
        }
    }

    private fun buildRankUrl(key: RankLookupKey): String {
        val previous = snapshots[key]
        val localGain = if (config.settings.trackLocalXp) localXpOffsets[key] ?: 0L else 0L
        val baseUrl = "$API_BASE_URL/leaderboard/${key.boardId}/${key.playerUuid}/${key.profileId}"
        if (previous != null && previous.isRanked && localGain > 0L) {
            val localTotalXp = (previous.amount + localGain).roundToLong()
            return "$baseUrl?upcoming=$UPCOMING_COUNT&atAmount=$localTotalXp"
        }
        return "$baseUrl?upcoming=$UPCOMING_COUNT"
    }

    private fun reconcileLocalOffset(
        key: RankLookupKey,
        previous: EliteLeaderboardSnapshot?,
        current: EliteLeaderboardSnapshot,
    ) {
        if (previous == null) {
            localXpOffsets.remove(key)
            return
        }
        if (current.amount == previous.amount) return
        val previousOffset = localXpOffsets[key] ?: 0L
        val serverDelta = (current.amount - previous.amount).roundToLong()
        val remainingOffset = if (serverDelta > 0L) (previousOffset - serverDelta).coerceAtLeast(0L) else 0L
        if (remainingOffset > 0L) {
            localXpOffsets[key] = remainingOffset
        } else {
            localXpOffsets.remove(key)
        }
    }

    private fun <T> fetchJson(url: String, parse: (String) -> T): CompletableFuture<T> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("User-Agent", userAgent())
            .GET()
            .build()
        val future = synchronized(this) {
            requests.track(SkysoftHttp.sendString(request, MAXIMUM_RESPONSE_BYTES))
        }
        return future.thenApply { response ->
            check(response.statusCode() in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                "GET $url returned HTTP ${response.statusCode()}"
            }
            parse(response.body())
        }
    }

    private fun cancelInFlight() {
        profileSlots.cancelAll()
        rankSlots.cancelAll()
        val cancelled = synchronized(this) {
            requests.also { requests = CancellableRequestGroup() }
        }
        cancelled.cancel()
    }

    private fun currentPlayerUuid(): String? =
        Minecraft.getInstance().player?.uuid?.toString()?.replace("-", "")

    private fun userAgent(): String =
        "Skysoft/${SkysoftMod.VERSION} (+https://github.com/NicholasMGreen/Skysoft)"

    private const val API_BASE_URL = "https://api.eliteskyblock.com"
    private const val REQUEST_TIMEOUT_SECONDS = 15L
    private const val MAXIMUM_RESPONSE_BYTES = 1024L * 1024L
    private const val REFRESH_INTERVAL_MILLIS = 10L * 60L * 1000L
    private const val FAILURE_RETRY_MILLIS = 60L * 1000L
    private const val UPCOMING_COUNT = 20
    private const val HTTP_SUCCESS_MIN = 200
    private const val HTTP_SUCCESS_MAX = 299
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

    fun withLocalXp(localGain: Long): EliteLeaderboardSnapshot {
        if (localGain <= 0L) return this
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
    val playerUuid: String,
    val profileId: String,
    val boardId: String,
)

private val STANDARD_SLAYER_XP_BY_TIER = longArrayOf(5L, 25L, 100L, 500L, 1_500L)
private val VAMPIRE_SLAYER_XP_BY_TIER = longArrayOf(10L, 25L, 60L, 120L, 150L)
private const val AATROX_XP_NUMERATOR = 5L
private const val AATROX_XP_DENOMINATOR = 4L

private fun slayerQuestXp(slayerType: SkyBlockSlayerType, tier: Int, mayorXpBuffActive: Boolean): Long {
    val table = if (slayerType == SkyBlockSlayerType.VAMPIRE) VAMPIRE_SLAYER_XP_BY_TIER else STANDARD_SLAYER_XP_BY_TIER
    val baseXp = table.getOrNull(tier - 1) ?: return 0L
    return if (mayorXpBuffActive) (baseXp * AATROX_XP_NUMERATOR) / AATROX_XP_DENOMINATOR else baseXp
}

private fun parseProfilesMap(body: String): Map<String, String> {
    val array = JsonParser.parseString(body).takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
    return buildMap {
        for (element in array) {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val profileId = obj.stringOrNull("profileId") ?: continue
            val profileName = obj.stringOrNull("profileName")?.trim()?.lowercase(Locale.US) ?: continue
            if (profileName.isNotEmpty()) put(profileName, profileId)
        }
    }
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
            val ign = player.stringOrNull("ign") ?: return@mapNotNull null
            val playerAmount = player.doubleOrNull("amount") ?: return@mapNotNull null
            val playerRank = player.intOrNull("rank") ?: -1
            EliteLeaderboardAhead(ign = ign, amount = playerAmount, rank = playerRank)
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
