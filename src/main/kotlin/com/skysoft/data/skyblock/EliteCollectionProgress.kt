package com.skysoft.data.skyblock

import com.skysoft.data.hypixel.HypixelLocationState
import com.skysoft.data.hypixel.SkyBlockProfileApi
import com.skysoft.utils.ElapsedTimeMark
import com.skysoft.utils.MinecraftClient
import com.skysoft.utils.NumberUtilities.formatIntOrNull
import com.skysoft.utils.SkysoftClientEvents
import com.skysoft.utils.chat.ChatEvents
import com.skysoft.utils.chat.ChatMessageVisibility
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

internal object EliteCollectionProgress {
    private val gainSuppressions = CollectionItemSuppressions()
    private val removalSuppressions = CollectionItemSuppressions()
    private val pendingCompactionRemovals = CollectionPendingRemovals()
    private var cachedCatalogVersion = Long.MIN_VALUE
    private var cachedDataVersion = Long.MIN_VALUE
    private var itemContributions: Map<String, List<CollectionContribution>> = emptyMap()
    private var craftingInputs: Map<String, CraftingConversion> = emptyMap()

    fun register() {
        val active: () -> Boolean = ::isCollectionTrackingActive
        SkyBlockDroppedItems.onDrop("Elite Leaderboard dropped items", active) { drop ->
            gainSuppressions.add(drop.itemId, drop.amount, DROPPED_SUPPRESSION_MILLIS)
            removalSuppressions.add(drop.itemId, drop.amount, DROPPED_SUPPRESSION_MILLIS)
        }
        AttributeShardTransfers.onTransfer("Elite Leaderboard Hunting Box transfers", active) { transfer ->
            if (transfer.direction == AttributeShardTransferDirection.FROM_BOX) {
                gainSuppressions.add(transfer.itemId, transfer.amount, HUNTING_BOX_SUPPRESSION_MILLIS)
            }
        }
        SkyBlockSackTransfers.onTransfer("Elite Leaderboard Sack transfers", active) { transfer ->
            gainSuppressions.add(transfer.itemId, transfer.amount, TRANSFER_SUPPRESSION_MILLIS)
            removalSuppressions.add(transfer.itemId, transfer.amount, TRANSFER_SUPPRESSION_MILLIS)
        }
        SkyBlockBazaarTransfers.onTransfer("Elite Leaderboard Bazaar transfers", active) { transfer ->
            SkyBlockItemNames.itemId(transfer.displayName)?.let { itemId ->
                if (transfer.direction == SkyBlockBazaarTransferDirection.TO_PLAYER) {
                    gainSuppressions.add(itemId, transfer.amount, TRANSFER_SUPPRESSION_MILLIS)
                } else {
                    removalSuppressions.add(itemId, transfer.amount, TRANSFER_SUPPRESSION_MILLIS)
                }
            }
        }
        SkyBlockSupercrafts.onCraft("Elite Leaderboard Supercraft", active) { crafted ->
            SkyBlockItemNames.itemId(crafted.displayName)?.let { itemId ->
                gainSuppressions.add(itemId, crafted.amount, TRANSFER_SUPPRESSION_MILLIS)
            }
        }
        ChatEvents.onVisibleMessage("Elite Leaderboard NPC sales", active) { message ->
            parseNpcSale(message.cleanText)?.let { (displayName, amount) ->
                SkyBlockItemNames.itemId(displayName)?.let { itemId ->
                    removalSuppressions.add(itemId, amount, TRANSFER_SUPPRESSION_MILLIS)
                }
            }
            ChatMessageVisibility.SHOW
        }
        SkyBlockItemChanges.onChange("Elite Leaderboard collection items", active, ::onItemChanges)
        SkyBlockSackChanges.onChange("Elite Leaderboard gemstone sacks", active, ::onGemstoneSackChanges)
        SkysoftClientEvents.onDisconnect("Elite Leaderboard collection reset") { clearTransient() }
        SkyBlockProfileApi.onProfileChange("Elite Leaderboard collection profile reset", { true }) { clearTransient() }
    }

    private fun isCollectionTrackingActive(): Boolean =
        EliteLeaderboardClient.isTrackingLocalProgress() && HypixelLocationState.inSkyBlock

    private fun onItemChanges(batch: SkyBlockItemChangeBatch) {
        val unsuppressed = consumeSuppressions(batch)
        if (batch.source == SkyBlockItemChangeSource.INVENTORY && MinecraftClient.screen() is AbstractContainerScreen<*>) {
            return
        }
        ensureContributionIndex()
        val effectiveGains = if (batch.source == SkyBlockItemChangeSource.SACKS) {
            unsuppressed.filterValues { it > 0 }
        } else {
            reconcileCompaction(unsuppressed)
        }
        if (effectiveGains.isEmpty()) return

        val deltasByCollection = mutableMapOf<String, Double>()
        effectiveGains.forEach { (itemId, amount) ->
            if (amount <= 0) return@forEach
            // Inventory reports raw NBT ids while sacks/item-name lookups report canonical ids;
            // both are folded to the same join key so colon-bearing items actually match.
            itemContributions[eliteCollectionJoinKey(itemId)].orEmpty().forEach { contribution ->
                deltasByCollection[contribution.collectionId] =
                    (deltasByCollection[contribution.collectionId] ?: 0.0) + amount * contribution.multiplier
            }
        }
        deltasByCollection.forEach { (collectionId, delta) ->
            if (delta > 0.0) {
                EliteSignalBus.publish(EliteLocalSignal.CollectionGain(collectionId, delta))
            }
        }
    }

    private fun onGemstoneSackChanges(batch: SkyBlockSackChangeBatch) {
        var totalGemstoneUnits = 0.0
        batch.changes.forEach { change ->
            if (change.amount <= 0) return@forEach
            if (SkyBlockItemNames.itemId(change.displayName) != null) return@forEach
            val multiplier = gemstoneMultiplierFromName(change.displayName) ?: return@forEach
            totalGemstoneUnits += change.amount * multiplier
        }
        if (totalGemstoneUnits > 0.0) {
            EliteSignalBus.publish(EliteLocalSignal.CollectionGain(GEMSTONE_COLLECTION_ID, totalGemstoneUnits))
        }
    }

    private fun consumeSuppressions(batch: SkyBlockItemChangeBatch): Map<String, Int> {
        val grossRemovals = (batch.changes.keys + batch.grossGains.keys).associateWith { itemId ->
            (batch.grossGains.getOrDefault(itemId, 0) - batch.changes.getOrDefault(itemId, 0)).coerceAtLeast(0)
        }
        val withoutRemovals = removalSuppressions.consume(batch.changes, grossRemovals) { amount, _, suppressed ->
            amount + suppressed
        }
        return gainSuppressions.consume(withoutRemovals, batch.grossGains) { amount, grossGain, suppressed ->
            amount - (suppressed - (grossGain - amount).coerceAtLeast(0)).coerceAtLeast(0)
        }
    }

    private fun reconcileCompaction(changes: Map<String, Int>): Map<String, Int> {
        pendingCompactionRemovals.pruneExpired()
        changes.forEach { (itemId, amount) ->
            val key = eliteCollectionJoinKey(itemId)
            if (amount < 0 && key in itemContributions) {
                pendingCompactionRemovals.add(key, -amount)
            }
        }
        return buildMap {
            changes.forEach { (itemId, amount) ->
                val key = eliteCollectionJoinKey(itemId)
                if (amount <= 0 || key !in itemContributions) return@forEach
                val conversion = craftingInputs[key]
                if (conversion == null) {
                    put(itemId, amount)
                    return@forEach
                }
                val requiredInput = conversion.inputCount * amount / conversion.outputCount
                val coveredInput = pendingCompactionRemovals.take(conversion.inputId, requiredInput)
                val remainingInput = (requiredInput - coveredInput).coerceAtLeast(0L)
                if (remainingInput > 0L) {
                    val wholeOutputs = (remainingInput / conversion.inputCount * conversion.outputCount).toInt()
                    val remainderInput = (remainingInput % conversion.inputCount).toInt()
                    if (wholeOutputs > 0) put(itemId, getOrDefault(itemId, 0) + wholeOutputs)
                    if (remainderInput > 0) put(conversion.inputId, getOrDefault(conversion.inputId, 0) + remainderInput)
                }
            }
        }
    }

    private fun ensureContributionIndex() {
        val dataVersion = SkyBlockDataRepository.snapshotVersion
        val catalogVersion = EliteLeaderboardCatalog.version
        if (dataVersion == cachedDataVersion &&
            catalogVersion == cachedCatalogVersion &&
            itemContributions.isNotEmpty()
        ) {
            return
        }
        // Root mappings come from the Elite catalog and need no recipe data. Recipes only add the
        // crafting-compaction conversions, so a failed or still-running item-data load must not
        // disable collections that map directly to an item id (diamond, emerald, ...).
        val recipes = SkyBlockDataRepository.pricingRecipes.takeIf { isDataReady() }
        val built = buildCollectionIndex(recipes)
        cachedDataVersion = dataVersion
        cachedCatalogVersion = catalogVersion
        itemContributions = built.contributions
        craftingInputs = built.conversions
    }

    private fun isDataReady(): Boolean = SkyBlockDataRepository.status.state == SkyBlockDataLoadState.READY

    private fun clearTransient() {
        gainSuppressions.clear()
        removalSuppressions.clear()
        pendingCompactionRemovals.clear()
    }
}

internal data class CollectionContribution(
    val collectionId: String,
    val multiplier: Double,
)

private data class CraftingConversion(
    val inputId: String,
    val inputCount: Long,
    val outputCount: Long,
)

private data class BuiltCollectionIndex(
    val contributions: Map<String, List<CollectionContribution>>,
    val conversions: Map<String, CraftingConversion>,
)

private fun buildCollectionIndex(recipes: SkyBlockRecipeSnapshot?): BuiltCollectionIndex {
    val rootMappings = collectRootMappings()
    val conversions = mutableMapOf<String, CraftingConversion>()
    val byResult = mutableMapOf<String, List<CraftingConversion>>()
    // Without item data there are no recipes, so only the direct catalog mappings are indexed.
    recipes?.recipesByResult?.forEach { (resultKey, recipeList) ->
        if (resultKey.kind != ItemListEntryKind.SKYBLOCK) return@forEach
        val itemId = resultKey.id
        if (isExcludedCraftedResult(itemId)) return@forEach
        val singleInputs = recipeList.asSequence()
            .filterIsInstance<SkyBlockRecipe.Crafting>()
            .mapNotNull { recipe ->
                val inputId = recipe.ingredients.map { it.id }.distinct().singleOrNull() ?: return@mapNotNull null
                val inputCount = recipe.ingredients.sumOf { it.count }
                val outputCount = recipe.result.count
                if (inputCount <= 0L || outputCount <= 0L) return@mapNotNull null
                if (inputCount < outputCount * MIN_COMPACTION_RATIO) return@mapNotNull null
                CraftingConversion(inputId, inputCount, outputCount)
            }
            .toList()
        if (singleInputs.isNotEmpty()) byResult[itemId] = singleInputs
    }

    val contributions = linkedMapOf<String, MutableMap<String, Double>>()
    rootMappings.forEach { (itemId, collectionIds) ->
        val map = contributions.getOrPut(itemId, ::linkedMapOf)
        collectionIds.forEach { collectionId -> map[collectionId] = 1.0 }
    }

    repeat(MAX_COMPACTION_DEPTH) {
        var changed = false
        byResult.forEach { (outputId, candidates) ->
            if (outputId in contributions) return@forEach
            val chosen = candidates.firstOrNull { it.inputId in contributions } ?: return@forEach
            val inputCollections = contributions.getValue(chosen.inputId)
            val ratio = chosen.inputCount.toDouble() / chosen.outputCount.toDouble()
            val outMap = contributions.getOrPut(outputId, ::linkedMapOf)
            inputCollections.forEach { (collectionId, mult) ->
                outMap[collectionId] = mult * ratio
            }
            conversions[outputId] = chosen
            changed = true
        }
        if (!changed) return@repeat
    }

    return BuiltCollectionIndex(
        contributions = contributions.mapValues { (_, collections) ->
            collections.entries.map { (collectionId, mult) -> CollectionContribution(collectionId, mult) }
        },
        conversions = conversions,
    )
}

private fun collectRootMappings(): Map<String, List<String>> {
    val map = linkedMapOf<String, MutableList<String>>()
    EliteLeaderboardCatalog.entries()
        .mapNotNull { it.collectionId }
        .distinct()
        .forEach { collectionId ->
            when (collectionId) {
                MUSHROOM_COLLECTION_ID -> {
                    map.getOrPut(eliteCollectionJoinKey("BROWN_MUSHROOM"), ::mutableListOf).add(collectionId)
                    map.getOrPut(eliteCollectionJoinKey("RED_MUSHROOM"), ::mutableListOf).add(collectionId)
                }
                GEMSTONE_COLLECTION_ID -> {
                    GEMSTONE_TYPES.forEach { gemType ->
                        map.getOrPut(eliteCollectionJoinKey("ROUGH_${gemType}_GEM"), ::mutableListOf).add(collectionId)
                    }
                }
                else -> map.getOrPut(eliteCollectionJoinKey(collectionId), ::mutableListOf).add(collectionId)
            }
        }
    return map
}

private fun isExcludedCraftedResult(itemId: String): Boolean {
    if (EXCLUDED_RESULT_SUFFIXES.any { itemId.endsWith(it) }) return true
    if (EXCLUDED_RESULT_MARKERS.any { it in itemId }) return true
    val info = SkyBlockDataRepository.info(SkyBlockDataRepository.itemKey(itemId))
    val lastLore = info?.lore?.lastOrNull().orEmpty()
    return EXCLUDED_LORE_TAGS.any { it in lastLore }
}

private fun gemstoneMultiplierFromName(displayName: String): Double? {
    val match = GEMSTONE_SACK_NAME_PATTERN.matchEntire(displayName.trim()) ?: return null
    val rarity = match.groups["rarity"]?.value?.uppercase() ?: return null
    return GEMSTONE_MULTIPLIERS[rarity]
}

private fun parseNpcSale(message: String): Pair<String, Int>? {
    val match = NPC_SALE_PATTERN.matchEntire(message) ?: return null
    val displayName = match.groups["item"]?.value?.trim().orEmpty()
    val amount = match.groups["amount"]?.value?.formatIntOrNull() ?: return null
    return (displayName to amount).takeIf { displayName.isNotEmpty() && amount > 0 }
}

private class CollectionItemSuppressions {
    private val suppressions = mutableMapOf<String, Suppression>()

    fun add(itemId: String, amount: Int, lifetimeMillis: Long) {
        if (amount <= 0) return
        val now = System.currentTimeMillis()
        val current = suppressions[itemId]?.takeIf { it.expiresAtMillis > now }
        suppressions[itemId] = Suppression(
            amount = (current?.amount ?: 0) + amount,
            expiresAtMillis = maxOf(current?.expiresAtMillis ?: 0L, now + lifetimeMillis),
        )
    }

    fun consume(
        changes: Map<String, Int>,
        grossAmounts: Map<String, Int>,
        remainingAmount: (amount: Int, grossAmount: Int, suppressed: Int) -> Int,
    ): Map<String, Int> = buildMap {
        val now = System.currentTimeMillis()
        suppressions.entries.removeIf { (_, suppression) -> suppression.expiresAtMillis <= now }
        (changes.keys + grossAmounts.keys).forEach { itemId ->
            val grossAmount = grossAmounts[itemId] ?: 0
            val remaining = remainingAmount(changes[itemId] ?: 0, grossAmount, take(itemId, grossAmount))
            if (remaining != 0) put(itemId, remaining)
        }
    }

    fun clear() = suppressions.clear()

    private fun take(itemId: String, limit: Int): Int {
        val suppression = suppressions[itemId] ?: return 0
        val taken = min(limit, suppression.amount)
        if (taken <= 0) return 0
        val pending = suppression.amount - taken
        if (pending == 0) suppressions.remove(itemId) else suppressions[itemId] = suppression.copy(amount = pending)
        return taken
    }

    private data class Suppression(val amount: Int, val expiresAtMillis: Long)
}

private class CollectionPendingRemovals {
    private val pending = mutableMapOf<String, Entry>()

    fun add(itemId: String, amount: Int) {
        if (amount <= 0) return
        val existing = pending[itemId]
        pending[itemId] = Entry((existing?.amount ?: 0L) + amount, ElapsedTimeMark.now())
    }

    fun take(itemId: String, needed: Long): Long {
        val entry = pending[itemId] ?: return 0L
        val taken = min(needed, entry.amount)
        if (taken <= 0L) return 0L
        val remaining = entry.amount - taken
        if (remaining == 0L) pending.remove(itemId) else pending[itemId] = entry.copy(amount = remaining)
        return taken
    }

    fun pruneExpired() {
        pending.entries.removeIf { (_, entry) -> entry.started.passedSince() > COMPACTION_WINDOW }
    }

    fun clear() = pending.clear()

    private data class Entry(val amount: Long, val started: ElapsedTimeMark)
}

private const val MUSHROOM_COLLECTION_ID = "MUSHROOM_COLLECTION"
private const val GEMSTONE_COLLECTION_ID = "GEMSTONE_COLLECTION"
private const val MIN_COMPACTION_RATIO = 9L
private const val MAX_COMPACTION_DEPTH = 4
private const val DROPPED_SUPPRESSION_MILLIS = 10_000L
private const val HUNTING_BOX_SUPPRESSION_MILLIS = 5_000L
private const val TRANSFER_SUPPRESSION_MILLIS = 60_000L
private val COMPACTION_WINDOW = 15.seconds
private val NPC_SALE_PATTERN = Regex("^You sold (?<item>.+) x(?<amount>[\\d,]+) for [\\d,]+ Coins!$")
private val GEMSTONE_SACK_NAME_PATTERN = Regex(
    "^(?<rarity>Rough|Flawed|Fine|Flawless|Perfect) [A-Za-z]+ Gemstone$",
    RegexOption.IGNORE_CASE,
)
private val GEMSTONE_TYPES = listOf(
    "RUBY", "AMETHYST", "JADE", "SAPPHIRE", "AMBER", "TOPAZ",
    "JASPER", "OPAL", "ONYX", "AQUAMARINE", "CITRINE", "PERIDOT",
)
private val GEMSTONE_MULTIPLIERS = mapOf(
    "ROUGH" to 1.0,
    "FLAWED" to 80.0,
    "FINE" to 6_400.0,
    "FLAWLESS" to 512_000.0,
    "PERFECT" to 2_560_000.0,
)
private val EXCLUDED_RESULT_SUFFIXES = setOf(
    "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS",
    "_SWORD", "_BOW", "_WAND", "_AXE", "_HOE", "_PICKAXE", "_SHOVEL", "_ROD",
    "_TALISMAN", "_RING", "_ARTIFACT", "_RELIC", "_BELT", "_CLOAK", "_GLOVES", "_NECKLACE",
)
private val EXCLUDED_RESULT_MARKERS = setOf("MINION", "SIL_EX")
private val EXCLUDED_LORE_TAGS = setOf(
    "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "SWORD", "BOW", "ACCESSORY",
    "TOOL", "PICKAXE", "AXE", "HOE", "SHOVEL", "ROD", "WAND", "DRILL",
    "GAUNTLET", "VACUUM", "DEPLOYABLE", "MINION", "PET", "POWER STONE", "FISHING NET",
)
