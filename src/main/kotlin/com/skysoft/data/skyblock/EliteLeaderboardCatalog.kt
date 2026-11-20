package com.skysoft.data.skyblock

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.skysoft.SkysoftMod
import com.skysoft.utils.SkysoftClientEvents
import com.skysoft.utils.net.AsyncRequestSlot
import com.skysoft.utils.net.CancellableRequestGroup
import com.skysoft.utils.net.RefreshSchedule
import com.skysoft.utils.net.SkysoftHttp
import com.skysoft.utils.net.isCancellationFailure
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import net.minecraft.client.Minecraft

object EliteLeaderboardCatalog {
    private var requests = CancellableRequestGroup()
    private val slot = AsyncRequestSlot<Map<String, EliteLeaderboardInfo>>(
        completionExecutor = Minecraft.getInstance(),
    )
    private val schedule = RefreshSchedule()

    @Volatile
    private var catalog: Map<String, EliteLeaderboardInfo> = emptyMap()

    @Volatile
    var status: EliteCatalogStatus = EliteCatalogStatus.IDLE
        private set

    fun register() {
        SkysoftClientEvents.onClientStopping("Elite Leaderboard Catalog shutdown") { cancelInFlight() }
    }

    fun ensureLoaded() {
        val now = System.currentTimeMillis()
        if (!schedule.isDue(now)) return
        if (slot.isPending) return
        status = if (catalog.isEmpty()) EliteCatalogStatus.LOADING else status
        slot.startIfIdle(
            requestFactory = { fetchJson("$API_BASE_URL/leaderboards", ::parseLeaderboardsCatalog) },
        ) { parsed, error ->
            val completedAt = System.currentTimeMillis()
            if (error == null && parsed != null) {
                catalog = parsed
                version++
                status = EliteCatalogStatus.LOADED
                schedule.schedule(completedAt, CATALOG_REFRESH_INTERVAL_MILLIS)
                SkysoftMod.LOGGER.info("Elite leaderboard catalog loaded: {} boards", parsed.size)
            } else if (error?.isCancellationFailure() != true) {
                if (catalog.isEmpty()) status = EliteCatalogStatus.FAILED
                schedule.schedule(completedAt, FAILURE_RETRY_MILLIS)
                SkysoftMod.LOGGER.warn("Failed to load Elite SkyBlock leaderboards catalog", error)
            }
        }
    }

    /**
     * Metadata for [boardId].
     *
     * The fallback table is consulted for *labels* only, and a board that is in neither source is
     * reported as [EliteLeaderboardInfo.isKnown] `false` instead of being given fabricated metadata.
     * Fabricating it is what previously made a board look fetchable while silently disabling its
     * local tracking.
     */
    fun info(boardId: String): EliteLeaderboardInfo {
        val normalized = normalizeEliteBoardId(boardId)
        return catalog[normalized] ?: FALLBACK_BOARDS[normalized] ?: unknownBoardInfo(normalized)
    }

    fun findOrNull(boardId: String): EliteLeaderboardInfo? {
        val normalized = normalizeEliteBoardId(boardId)
        return catalog[normalized] ?: FALLBACK_BOARDS[normalized]
    }

    /** Where [boardId]'s metadata came from, for diagnostics. */
    fun sourceOf(boardId: String): String {
        val normalized = normalizeEliteBoardId(boardId)
        return when {
            catalog.containsKey(normalized) -> "catalog"
            FALLBACK_BOARDS.containsKey(normalized) -> "fallback"
            else -> "unknown"
        }
    }

    /** Increments whenever the parsed catalog is replaced, so derived indexes can invalidate. */
    @Volatile
    var version: Long = 0L
        private set

    /**
     * Boards whose local tracking cannot work, hidden from the picker.
     *
     * Ghost kills are not observable in-client: the mob never surfaces a nameplate the tracker can
     * pair with an entity, so no kill signal is ever produced and a locally tracked gain is
     * impossible. The board still fetches from Elite every 10 minutes, which made the gap look like
     * a bug rather than a missing capability. Offering it invited exactly that confusion, so it is
     * withheld until local tracking has a signal to work with.
     */
    internal fun isUnsupportedBoard(boardId: String): Boolean =
        normalizeEliteBoardId(boardId) in UNSUPPORTED_BOARD_IDS

    fun entries(): List<EliteLeaderboardInfo> {
        val source = catalog.ifEmpty { FALLBACK_BOARDS }
        return source.values
            .filterNot { info -> isUnsupportedBoard(info.id) }
            .sortedWith(
                compareBy<EliteLeaderboardInfo> { it.category }
                    .thenBy { it.intervalType.sortOrder }
                    .thenBy { it.title },
            )
    }

    fun categories(): List<String> =
        entries().map(EliteLeaderboardInfo::category).distinct()

    internal fun <T> fetchJson(url: String, parse: (String) -> T): CompletableFuture<T> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("User-Agent", userAgent())
            .GET()
            .build()
        val future = synchronized(this) {
            requests.track(SkysoftHttp.sendString(request, MAXIMUM_CATALOG_RESPONSE_BYTES))
        }
        return future.thenApply { response ->
            check(response.statusCode() in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                "GET $url returned HTTP ${response.statusCode()}"
            }
            parse(response.body())
        }
    }

    internal fun userAgent(): String =
        "Skysoft/${SkysoftMod.VERSION} (+https://github.com/NicholasMGreen/Skysoft)"

    private fun cancelInFlight() {
        slot.cancel()
        val cancelled = synchronized(this) {
            requests.also { requests = CancellableRequestGroup() }
        }
        cancelled.cancel()
    }

    internal const val API_BASE_URL = "https://api.eliteskyblock.com"
    internal const val REQUEST_TIMEOUT_SECONDS = 20L
    internal const val HTTP_SUCCESS_MIN = 200
    internal const val HTTP_SUCCESS_MAX = 299

    /**
     * `/profiles/{uuid}` grows with account history and has been observed at ~2.8 MB for an old
     * account, so the shared 2 MB cap silently failed profile resolution for exactly those players.
     */
    private const val MAXIMUM_CATALOG_RESPONSE_BYTES = 16L * 1024L * 1024L
    private const val CATALOG_REFRESH_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
    private const val FAILURE_RETRY_MILLIS = 60L * 1000L
}

enum class EliteCatalogStatus {
    IDLE,
    LOADING,
    LOADED,
    FAILED,
}

enum class EliteScoreDataType {
    LONG,
    DECIMAL,
    ;

    companion object {
        fun fromApi(value: String?): EliteScoreDataType =
            if (value.equals("Decimal", ignoreCase = true)) DECIMAL else LONG
    }
}

enum class EliteIntervalType(val label: String, val sortOrder: Int) {
    CURRENT("Current", 0),
    MONTHLY("Monthly", 1),
    WEEKLY("Weekly", 2),
    ;

    companion object {
        fun fromApi(value: String?): EliteIntervalType = when {
            value.equals("Monthly", ignoreCase = true) -> MONTHLY
            value.equals("Weekly", ignoreCase = true) -> WEEKLY
            else -> CURRENT
        }
    }
}

data class EliteLeaderboardInfo(
    val id: String,
    val title: String,
    val shortTitle: String,
    val category: String,
    val isProfileBoard: Boolean,
    val minimumScore: Double,
    val scoreDataType: EliteScoreDataType,
    val intervalType: EliteIntervalType,
    val collectionId: String? = null,
    val bestiaryId: String? = null,
    /** False when the board is in neither the live catalog nor the fallback table. */
    val isKnown: Boolean = true,
) {
    fun url(): String = "$ELITE_LEADERBOARD_BASE_URL/$id"

    fun displayTitle(): String = when (intervalType) {
        EliteIntervalType.CURRENT -> title
        EliteIntervalType.MONTHLY, EliteIntervalType.WEEKLY -> "$title (${intervalType.label})"
    }

    fun matchesQuery(query: String): Boolean {
        if (query.isBlank()) return true
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
        val haystack = "$id $title $shortTitle $category ${intervalType.label}".lowercase(Locale.ROOT)
        return terms.all { it in haystack }
    }

    private companion object {
        const val ELITE_LEADERBOARD_BASE_URL = "https://eliteskyblock.com/leaderboard"
    }
}

internal fun normalizeEliteBoardId(boardId: String): String =
    boardId.trim().lowercase(Locale.ROOT).ifEmpty { DEFAULT_ELITE_BOARD_ID }

internal const val DEFAULT_ELITE_BOARD_ID = "blaze-slayer"

/**
 * Boards withheld from the picker because they have no in-client kill signal.
 *
 * `bestiary-ghost` is the only entry today: Ghosts expose no nameplate the mob tracker can pair with
 * an entity, so a kill can never be observed and the local offset can never advance.
 */
private val UNSUPPORTED_BOARD_IDS = setOf("bestiary-ghost")

/**
 * Placeholder for a board id in neither the live catalog nor the fallback table.
 *
 * Marked [EliteLeaderboardInfo.isKnown] `false` so local tracking and formatting can react honestly
 * instead of silently pretending the board supports a signal it does not have.
 */
private fun unknownBoardInfo(boardId: String): EliteLeaderboardInfo {
    val interval = when {
        boardId.endsWith(MONTHLY_SUFFIX) -> EliteIntervalType.MONTHLY
        boardId.endsWith(WEEKLY_SUFFIX) -> EliteIntervalType.WEEKLY
        else -> EliteIntervalType.CURRENT
    }
    val baseId = eliteBoardBaseId(boardId)
    val words = baseId.split('-').filter(String::isNotEmpty).joinToString(" ") { part ->
        part.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }.ifEmpty { boardId }
    return EliteLeaderboardInfo(
        id = boardId,
        title = words,
        shortTitle = words,
        category = "Custom",
        isProfileBoard = false,
        minimumScore = 0.0,
        scoreDataType = EliteScoreDataType.LONG,
        intervalType = interval,
        isKnown = false,
    )
}

private fun parseLeaderboardsCatalog(body: String): Map<String, EliteLeaderboardInfo> {
    val root = JsonParser.parseString(body).asJsonObject
    val boards = root.getAsJsonObject("leaderboards") ?: return emptyMap()
    return buildMap {
        for ((id, element) in boards.entrySet()) {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val normalizedId = normalizeEliteBoardId(id)
            put(normalizedId, parseLeaderboardInfo(normalizedId, obj))
        }
    }
}

private fun parseLeaderboardInfo(id: String, obj: JsonObject): EliteLeaderboardInfo {
    val title = obj.catalogString("title") ?: id
    val shortTitle = obj.catalogString("short") ?: title
    val category = obj.catalogString("category") ?: "General"
    val isProfileBoard = obj.get("profile")?.takeUnless { it.isJsonNull }?.asBoolean == true
    val minimumScore = obj.get("minimumScore")?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0
    val scoreDataType = EliteScoreDataType.fromApi(obj.catalogString("scoreDataType"))
    val intervalType = EliteIntervalType.fromApi(obj.catalogString("intervalType"))
    val rawCollection = obj.catalogString("collection")
        ?: obj.catalogString("itemId")?.takeIf { category == "Milestones" }
    // Kept raw (`INK_SACK:3`); the router folds both sides through eliteCollectionJoinKey.
    val collectionId = rawCollection
    val bestiaryId = obj.catalogString("bestiary")
    return EliteLeaderboardInfo(
        id = id,
        title = title,
        shortTitle = shortTitle,
        category = category,
        isProfileBoard = isProfileBoard,
        minimumScore = minimumScore,
        scoreDataType = scoreDataType,
        intervalType = intervalType,
        collectionId = collectionId,
        bestiaryId = bestiaryId,
    )
}

private fun JsonObject.catalogString(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

private fun fallbackBoard(
    id: String,
    title: String,
    shortTitle: String,
    category: String,
    scoreDataType: EliteScoreDataType = EliteScoreDataType.LONG,
    intervalType: EliteIntervalType = EliteIntervalType.CURRENT,
    minimumScore: Double = 0.0,
    isProfileBoard: Boolean = false,
    collectionId: String? = null,
    bestiaryId: String? = null,
): Pair<String, EliteLeaderboardInfo> = id to EliteLeaderboardInfo(
    id = id,
    title = title,
    shortTitle = shortTitle,
    category = category,
    isProfileBoard = isProfileBoard,
    minimumScore = minimumScore,
    scoreDataType = scoreDataType,
    intervalType = intervalType,
    collectionId = collectionId,
    bestiaryId = bestiaryId,
)

private val FALLBACK_BOARDS: Map<String, EliteLeaderboardInfo> = mapOf(
    fallbackBoard(
        "zombie-slayer", "Zombie Slayer Experience", "Zombie Slayer XP", "Slayers",
        EliteScoreDataType.DECIMAL, minimumScore = 1000.0,
    ),
    fallbackBoard(
        "spider-slayer", "Spider Slayer Experience", "Spider Slayer XP", "Slayers",
        EliteScoreDataType.DECIMAL, minimumScore = 1000.0,
    ),
    fallbackBoard("wolf-slayer", "Wolf Slayer Experience", "Wolf Slayer XP", "Slayers", EliteScoreDataType.DECIMAL, minimumScore = 1500.0),
    fallbackBoard(
        "enderman-slayer", "Enderman Slayer Experience", "Enderman Slayer XP", "Slayers",
        EliteScoreDataType.DECIMAL, minimumScore = 1500.0,
    ),
    fallbackBoard(
        "blaze-slayer", "Blaze Slayer Experience", "Blaze Slayer XP", "Slayers",
        EliteScoreDataType.DECIMAL, minimumScore = 1500.0,
    ),
    fallbackBoard(
        "vampire-slayer", "Vampire Slayer Experience", "Vampire Slayer XP", "Slayers",
        EliteScoreDataType.DECIMAL, minimumScore = 75.0,
    ),
    fallbackBoard("slayer-xp", "Total Slayer Experience", "Slayer XP", "Slayers", EliteScoreDataType.DECIMAL, minimumScore = 1000.0),
    fallbackBoard("pests", "Pest Kills", "Pest Kills", "Pests", minimumScore = 100.0),
    fallbackBoard("pests-monthly", "Pest Kills", "Pest Kills", "Pests", intervalType = EliteIntervalType.MONTHLY, minimumScore = 100.0),
    fallbackBoard("coal", "Coal Collection", "Coal", "Mining", minimumScore = 500000.0, collectionId = "COAL"),
    fallbackBoard(
        "bestiary-glacite-walker", "Glacite Walker Kills", "Glacite Walker", "Bestiary",
        minimumScore = 20.0, bestiaryId = "mining_3:glacite_walker",
    ),
    fallbackBoard("farmingweight", "Farming Weight", "Farming Weight", "General", EliteScoreDataType.DECIMAL, minimumScore = 500.0),
    fallbackBoard("skyblockxp", "Skyblock Level", "Skyblock Level", "General", EliteScoreDataType.DECIMAL, minimumScore = 5000.0),
)
