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
                status = EliteCatalogStatus.LOADED
                schedule.schedule(completedAt, CATALOG_REFRESH_INTERVAL_MILLIS)
            } else if (error?.isCancellationFailure() != true) {
                if (catalog.isEmpty()) status = EliteCatalogStatus.FAILED
                schedule.schedule(completedAt, FAILURE_RETRY_MILLIS)
                SkysoftMod.LOGGER.warn("Failed to load Elite SkyBlock leaderboards catalog", error)
            }
        }
    }

    fun info(boardId: String): EliteLeaderboardInfo {
        val normalized = normalizeEliteBoardId(boardId)
        return catalog[normalized] ?: FALLBACK_BOARDS[normalized] ?: syntheticBoardInfo(normalized)
    }

    fun findOrNull(boardId: String): EliteLeaderboardInfo? {
        val normalized = normalizeEliteBoardId(boardId)
        return catalog[normalized] ?: FALLBACK_BOARDS[normalized]
    }

    fun entries(): List<EliteLeaderboardInfo> {
        val source = catalog.ifEmpty { FALLBACK_BOARDS }
        return source.values.sortedWith(
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
    internal const val REQUEST_TIMEOUT_SECONDS = 15L
    internal const val HTTP_SUCCESS_MIN = 200
    internal const val HTTP_SUCCESS_MAX = 299
    private const val MAXIMUM_CATALOG_RESPONSE_BYTES = 2L * 1024L * 1024L
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

private fun syntheticBoardInfo(boardId: String): EliteLeaderboardInfo {
    val interval = when {
        boardId.endsWith("-monthly") -> EliteIntervalType.MONTHLY
        boardId.endsWith("-weekly") -> EliteIntervalType.WEEKLY
        else -> EliteIntervalType.CURRENT
    }
    val baseId = boardId.removeSuffix("-monthly").removeSuffix("-weekly")
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
    return EliteLeaderboardInfo(
        id = id,
        title = title,
        shortTitle = shortTitle,
        category = category,
        isProfileBoard = isProfileBoard,
        minimumScore = minimumScore,
        scoreDataType = scoreDataType,
        intervalType = intervalType,
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
): Pair<String, EliteLeaderboardInfo> = id to EliteLeaderboardInfo(
    id = id,
    title = title,
    shortTitle = shortTitle,
    category = category,
    isProfileBoard = isProfileBoard,
    minimumScore = minimumScore,
    scoreDataType = scoreDataType,
    intervalType = intervalType,
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
    fallbackBoard("farmingweight", "Farming Weight", "Farming Weight", "General", EliteScoreDataType.DECIMAL, minimumScore = 500.0),
    fallbackBoard("skyblockxp", "Skyblock Level", "Skyblock Level", "General", EliteScoreDataType.DECIMAL, minimumScore = 5000.0),
)
