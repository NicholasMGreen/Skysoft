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

    fun register() {
        SkyBlockProfileApi.registerConsumer("Slayer Leaderboard") { isEnabled() }
        SkyBlockProfileApi.onProfileChange("Slayer Leaderboard profile", ::isEnabled) { profileKey ->
            if (profileKey != null) refreshActiveNow()
        }
        HypixelLocationState.onChange("Slayer Leaderboard location", ::isEnabled) { location ->
            if (location.inSkyBlock) refreshActiveNow()
        }
        SlayerQuestState.onQuestComplete("Slayer Leaderboard quest complete") { quest ->
            if (!isEnabled()) return@onQuestComplete
            invalidateOnQuestComplete(quest.slayerType)
            refreshActiveNow()
        }
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
        return snapshots[RankLookupKey(playerUuid, profileId, board.id)]
    }

    private fun isEnabled(): Boolean = config.enabled

    private fun currentBoard(): EliteSlayerBoard {
        val settings = config.settings
        if (settings.followActiveQuest) {
            SlayerQuestState.scoreboardSlayerType?.let { return EliteSlayerBoard.fromSlayerType(it) }
        }
        return settings.board
    }

    private fun refreshActiveNow() {
        if (!HypixelLocationState.inSkyBlock || !isEnabled()) return
        val playerUuid = currentPlayerUuid() ?: return
        val profileName = SkyBlockProfileApi.currentProfileKey ?: return
        val profileId = profilesByPlayer[playerUuid]?.get(profileName)
        if (profileId == null) {
            fetchProfilesIfDue(playerUuid, force = true)
            return
        }
        val key = RankLookupKey(playerUuid, profileId, currentBoard().id)
        rankSchedules.getOrPut(key, ::RefreshSchedule).reset()
        fetchRank(key)
    }

    private fun refreshActiveIfDue() {
        if (!HypixelLocationState.inSkyBlock || !isEnabled()) return
        val playerUuid = currentPlayerUuid() ?: return
        val profileName = SkyBlockProfileApi.currentProfileKey ?: return
        val profileId = profilesByPlayer[playerUuid]?.get(profileName)
        if (profileId == null) {
            fetchProfilesIfDue(playerUuid, force = false)
            return
        }
        val key = RankLookupKey(playerUuid, profileId, currentBoard().id)
        val schedule = rankSchedules.getOrPut(key, ::RefreshSchedule)
        if (schedule.isDue(System.currentTimeMillis())) fetchRank(key)
    }

    private fun invalidateOnQuestComplete(slayerType: SkyBlockSlayerType?) {
        val playerUuid = currentPlayerUuid() ?: return
        val profileName = SkyBlockProfileApi.currentProfileKey ?: return
        val profileId = profilesByPlayer[playerUuid]?.get(profileName) ?: return
        rankSchedules[RankLookupKey(playerUuid, profileId, currentBoard().id)]?.reset()
        rankSchedules[RankLookupKey(playerUuid, profileId, EliteSlayerBoard.TOTAL.id)]?.reset()
        slayerType?.let { type ->
            val boardId = EliteSlayerBoard.fromSlayerType(type).id
            rankSchedules[RankLookupKey(playerUuid, profileId, boardId)]?.reset()
        }
    }

    private fun fetchProfilesIfDue(playerUuid: String, force: Boolean) {
        val schedule = profileSchedules.getOrPut(playerUuid, ::RefreshSchedule)
        if (force) schedule.reset()
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
        val url = "$API_BASE_URL/leaderboard/${key.boardId}/${key.playerUuid}/${key.profileId}?upcoming=1"
        rankSlots.startIfIdle(
            key,
            requestFactory = { fetchJson(url) { body -> parseLeaderboardRank(key.boardId, body) } },
        ) { snapshot, error ->
            val now = System.currentTimeMillis()
            if (error == null && snapshot != null) {
                snapshots[key] = snapshot
                schedule.schedule(now, REFRESH_INTERVAL_MILLIS)
            } else if (error?.isCancellationFailure() != true) {
                schedule.schedule(now, FAILURE_RETRY_MILLIS)
                SkysoftMod.LOGGER.warn("Failed to refresh Elite SkyBlock leaderboard ${key.boardId}", error)
            }
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
    val ahead: EliteLeaderboardAhead?,
) {
    val isRanked: Boolean get() = rank > 0 && amount >= minAmount
}

private data class RankLookupKey(
    val playerUuid: String,
    val profileId: String,
    val boardId: String,
)

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
    val ahead = json.arrayOrNull("upcomingPlayers")
        ?.firstOrNull()
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
        ?.let { player ->
            val ign = player.stringOrNull("ign") ?: return@let null
            val playerAmount = player.doubleOrNull("amount") ?: return@let null
            val playerRank = player.intOrNull("rank") ?: -1
            EliteLeaderboardAhead(ign = ign, amount = playerAmount, rank = playerRank)
        }
    return EliteLeaderboardSnapshot(
        boardId = boardId,
        rank = rank,
        amount = amount,
        minAmount = minAmount,
        ahead = ahead,
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
