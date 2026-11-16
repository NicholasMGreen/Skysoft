package com.skysoft.features.combat

import com.skysoft.data.SkyBlockIsland
import com.skysoft.data.hypixel.HypixelLocationState
import com.skysoft.data.hypixel.SkyBlockProfileApi
import com.skysoft.data.skyblock.EliteLeaderboardCatalog
import com.skysoft.data.skyblock.EliteLeaderboardClient
import com.skysoft.data.skyblock.EliteLeaderboardInfo
import com.skysoft.data.skyblock.SkyBlockBestiaryFamilies
import com.skysoft.data.skyblock.withoutSkyBlockMobModifierPrefix
import com.skysoft.events.entity.ClientEntityMetadataEvent
import com.skysoft.events.entity.ClientEntityMetadataEvents
import com.skysoft.events.entity.EntityInteractionEvent
import com.skysoft.events.entity.EntityInteractionEvents
import com.skysoft.events.entity.EntityLifecycleEvents
import com.skysoft.events.input.ItemUseEvents
import com.skysoft.utils.SkysoftClientEvents
import com.skysoft.utils.WorldVec
import com.skysoft.utils.location
import java.util.Locale
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand

internal object EliteBestiaryKillTracker {
    private val trackedMobs = mutableMapOf<UUID, TrackedBestiaryMob>()
    private val attackedMobs = mutableMapOf<UUID, Long>()
    private val damagedMobs = mutableMapOf<UUID, Long>()
    private val countedKills = mutableMapOf<UUID, Long>()
    private var lastLocationVersion = -1L
    private var lastLocalActionAtMillis = 0L

    fun register() {
        EntityInteractionEvents.register("Elite Bestiary attack", ::isActive) { event ->
            if (event.action == EntityInteractionEvent.ActionType.ATTACK) recordAttack(event.clickedEntity)
            false
        }
        ItemUseEvents.register("Elite Bestiary item use", ::isActive) {
            lastLocalActionAtMillis = System.currentTimeMillis()
            false
        }
        ClientEntityMetadataEvents.register("Elite Bestiary damage splash", ::isActive, ::onEntityMetadata)
        EntityLifecycleEvents.onUnload("Elite Bestiary mob unload", ::isActive, ::onEntityUnload)
        SkysoftClientEvents.onEndTick(
            "Elite Bestiary kill tracker",
            isActive = { isActive() || trackedMobs.isNotEmpty() },
        ) { onEndTick() }
        HypixelLocationState.onChange("Elite Bestiary location reset", { true }) { clearTransient() }
        SkyBlockProfileApi.onProfileChange("Elite Bestiary profile reset", { true }) { clearTransient() }
        SkysoftClientEvents.onDisconnect("Elite Bestiary disconnect reset") { clearTransient() }
    }

    private fun isActive(): Boolean {
        if (!EliteLeaderboardClient.isTrackingLocalProgress() || !HypixelLocationState.inSkyBlock) return false
        val info = EliteLeaderboardCatalog.info(EliteLeaderboardClient.activeBoardId())
        return info.bestiaryId != null && info.category == BESTIARY_CATEGORY
    }

    private fun recordAttack(clickedEntity: Entity) {
        val now = System.currentTimeMillis()
        lastLocalActionAtMillis = now
        when (clickedEntity) {
            is ArmorStand -> {
                trackedMobs.values.firstOrNull { it.nameplateId == clickedEntity.id }?.let { mob ->
                    attackedMobs[mob.entityUuid] = now
                }
            }
            is LivingEntity -> attackedMobs[clickedEntity.uuid] = now
        }
    }

    private fun onEntityMetadata(event: ClientEntityMetadataEvent) {
        val now = System.currentTimeMillis()
        if (now - lastLocalActionAtMillis > RECENT_ACTION_WINDOW_MILLIS) return
        val level = Minecraft.getInstance().level ?: return
        val entity = level.getEntity(event.entityId) ?: return
        val splashes = DamageSplashText.fromMetadata(event.entityId, entity.location, event.packedItems)
        if (splashes.isEmpty()) return
        for (splash in splashes) {
            val nearest = trackedMobs.values
                .filter { it.location.distance(splash.location) <= MAX_SPLASH_DISTANCE }
                .minByOrNull { it.location.distanceSq(splash.location) }
                ?: continue
            damagedMobs[nearest.entityUuid] = now
        }
    }

    private fun onEntityUnload(entity: Entity) {
        if (trackedMobs.isEmpty()) return
        val mob = trackedMobs[entity.uuid]
            ?: trackedMobs.values.firstOrNull { it.nameplateId == entity.id }
            ?: return
        val now = System.currentTimeMillis()
        val playerLocation = Minecraft.getInstance().player?.location ?: return
        val isDead = (entity is LivingEntity && entity.isDeadOrDying) ||
            SkyBlockMobTracker.isDeathConfirmed(mob.entityUuid) ||
            (entity.uuid == mob.entityUuid && mob.location.distance(playerLocation) <= MAX_KILL_RANGE)
        if (isDead && isAttributedToLocalPlayer(mob, playerLocation, now)) {
            countKill(mob, now)
        }
    }

    private fun onEndTick() {
        if (!isActive()) {
            clearTransient()
            return
        }
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val locationVersion = HypixelLocationState.locationVersion
        if (lastLocationVersion != locationVersion) {
            lastLocationVersion = locationVersion
            clearTransient()
        }

        val now = System.currentTimeMillis()
        if (minecraft.options.keyAttack.isDown || minecraft.options.keyUse.isDown) {
            lastLocalActionAtMillis = now
        }
        pruneExpired(now)

        val playerLocation = player.location
        val visible = SkyBlockMobTracker.visibleMobs()
        val visibleByUuid = visible.associateBy(SkyBlockMob::entityUuid)

        processDisappearedMobs(level, playerLocation, visibleByUuid, now)
        updateVisibleMobs(visible, playerLocation, now)
    }

    private fun processDisappearedMobs(
        level: net.minecraft.client.multiplayer.ClientLevel,
        playerLocation: WorldVec,
        visibleByUuid: Map<UUID, SkyBlockMob>,
        now: Long,
    ) {
        val disappeared = trackedMobs.values.filter { it.entityUuid !in visibleByUuid }
        for (mob in disappeared) {
            trackedMobs.remove(mob.entityUuid)
            val liveEntity = level.getEntity(mob.entityId)
            val isDead = SkyBlockMobTracker.isDeathConfirmed(mob.entityUuid) ||
                liveEntity == null ||
                liveEntity.isRemoved ||
                (liveEntity is LivingEntity && liveEntity.isDeadOrDying)
            if (isDead && mob.location.distance(playerLocation) <= MAX_KILL_RANGE &&
                isAttributedToLocalPlayer(mob, playerLocation, now)
            ) {
                countKill(mob, now)
            }
        }
    }

    private fun updateVisibleMobs(
        visible: List<SkyBlockMob>,
        playerLocation: WorldVec,
        now: Long,
    ) {
        for (mob in visible) {
            val previous = trackedMobs[mob.entityUuid]
            val currentHealth = mob.health?.current
            val healthDropped = previous?.lastHealth != null &&
                currentHealth != null &&
                currentHealth < previous.lastHealth
            val healthDroppedAt = if (healthDropped) {
                if (now - lastLocalActionAtMillis <= RECENT_ACTION_WINDOW_MILLIS &&
                    mob.location.distance(playerLocation) <= MAX_KILL_RANGE
                ) {
                    damagedMobs[mob.entityUuid] = now
                }
                now
            } else {
                previous?.lastHealthDropAtMillis ?: 0L
            }
            val entityId = mob.entity?.id ?: previous?.entityId ?: -1
            if (entityId < 0) continue
            trackedMobs[mob.entityUuid] = TrackedBestiaryMob(
                entityUuid = mob.entityUuid,
                entityId = entityId,
                nameplateId = mob.nameplate?.id ?: previous?.nameplateId,
                name = mob.name,
                location = mob.location,
                lastHealth = currentHealth ?: previous?.lastHealth,
                lastHealthDropAtMillis = healthDroppedAt,
                lastSeenAtMillis = now,
            )
        }
    }

    private fun isAttributedToLocalPlayer(
        mob: TrackedBestiaryMob,
        playerLocation: WorldVec,
        now: Long,
    ): Boolean {
        if (mob.entityUuid in countedKills) return false
        val attackedAt = attackedMobs[mob.entityUuid]
        if (attackedAt != null && now - attackedAt <= ATTRIBUTION_WINDOW_MILLIS) return true
        val damagedAt = damagedMobs[mob.entityUuid]
        if (damagedAt != null && now - damagedAt <= ATTRIBUTION_WINDOW_MILLIS) return true
        return now - mob.lastHealthDropAtMillis <= RECENT_ACTION_WINDOW_MILLIS &&
            now - lastLocalActionAtMillis <= RECENT_ACTION_WINDOW_MILLIS &&
            mob.location.distance(playerLocation) <= CLOSE_HEALTH_DROP_RANGE
    }

    private fun countKill(mob: TrackedBestiaryMob, now: Long) {
        countedKills[mob.entityUuid] = now
        attackedMobs.remove(mob.entityUuid)
        damagedMobs.remove(mob.entityUuid)
        val matchingBoards = matchingBestiaryBoards(mob.name, HypixelLocationState.currentIsland)
        if (matchingBoards.isNotEmpty()) {
            EliteLeaderboardClient.addLocalProgress(matchingBoards, 1.0)
        }
    }

    private fun matchingBestiaryBoards(mobName: String, island: SkyBlockIsland?): List<String> {
        val normalized = normalizeSkyBlockMobName(mobName).withoutSkyBlockMobModifierPrefix(ignoreCase = true)
        val boards = EliteLeaderboardCatalog.bestiaryBoards()
        val duplicateShorts = boards.groupingBy { it.shortTitle.lowercase(Locale.ROOT) }.eachCount()
        return boards.mapNotNull { board ->
            if (!matchesIslandScope(board, duplicateShorts, island)) return@mapNotNull null
            val names = SkyBlockBestiaryFamilies.mobNames(listOf(board.shortTitle))
            board.id.takeIf { names.any { it.equals(normalized, ignoreCase = true) } }
        }
    }

    private fun matchesIslandScope(
        board: EliteLeaderboardInfo,
        duplicateShorts: Map<String, Int>,
        island: SkyBlockIsland?,
    ): Boolean {
        if ((duplicateShorts[board.shortTitle.lowercase(Locale.ROOT)] ?: 0) <= 1) return true
        val bestiaryId = board.bestiaryId ?: return true
        return when {
            bestiaryId.startsWith("dynamic:") -> island == SkyBlockIsland.PRIVATE_ISLAND
            bestiaryId.startsWith("catacombs:") -> island == SkyBlockIsland.DUNGEONS
            bestiaryId.startsWith("combat_3:") -> island == SkyBlockIsland.THE_END
            else -> true
        }
    }

    private fun pruneExpired(now: Long) {
        attackedMobs.entries.removeIf { (_, at) -> now - at > ATTRIBUTION_WINDOW_MILLIS }
        damagedMobs.entries.removeIf { (_, at) -> now - at > ATTRIBUTION_WINDOW_MILLIS }
        countedKills.entries.removeIf { (_, at) -> now - at > COUNTED_KILL_TTL_MILLIS }
    }

    private fun clearTransient() {
        trackedMobs.clear()
        attackedMobs.clear()
        damagedMobs.clear()
        countedKills.clear()
    }

    private data class TrackedBestiaryMob(
        val entityUuid: UUID,
        val entityId: Int,
        val nameplateId: Int?,
        val name: String,
        val location: WorldVec,
        val lastHealth: Long?,
        val lastHealthDropAtMillis: Long,
        val lastSeenAtMillis: Long,
    )

    private const val BESTIARY_CATEGORY = "Bestiary"
    private const val ATTRIBUTION_WINDOW_MILLIS = 10_000L
    private const val RECENT_ACTION_WINDOW_MILLIS = 2_000L
    private const val COUNTED_KILL_TTL_MILLIS = 30_000L
    private const val MAX_KILL_RANGE = 40.0
    private const val CLOSE_HEALTH_DROP_RANGE = 16.0
    private const val MAX_SPLASH_DISTANCE = 4.0
}
