package com.skysoft.data.skyblock

import com.skysoft.data.SkyBlockIsland
import java.util.Locale

/**
 * Resolves a local [EliteLocalSignal] to the boards it should advance.
 *
 * Reverse indexes are derived entirely from Elite's catalog metadata, so a board Elite adds later
 * inherits local tracking with no change here. Indexes rebuild only when the catalog version moves.
 *
 * Interval variants are separate index entries rather than a suffix expansion, which keeps
 * `pests-monthly` and `farming-monthly` correct without special-casing either naming scheme.
 */
internal object EliteBoardBindingRouter {
    @Volatile
    private var indexes: BindingIndexes? = null

    @Volatile
    private var indexedVersion: Long = Long.MIN_VALUE

    /** Boards advanced by [signal], with per-board amounts. */
    fun resolve(signal: EliteLocalSignal): List<EliteBoardGain> = when (signal) {
        is EliteLocalSignal.MobKill -> resolveMobKill(signal)
        is EliteLocalSignal.PestKill -> resolvePestKill(signal)
        is EliteLocalSignal.CollectionGain -> resolveCollection(signal)
        is EliteLocalSignal.SkillXp -> resolveSkill(signal)
        is EliteLocalSignal.SlayerXp -> resolveSlayer(signal)
    }

    private fun resolveMobKill(signal: EliteLocalSignal.MobKill): List<EliteBoardGain> {
        val key = eliteMobFamilyKey(signal.mobName)
        if (key.isEmpty()) return emptyList()
        val candidates = indexesFor().mobsByFamily[key].orEmpty()
            // Garden pest boards are owned by the chat-verified pest hook, which names the pest
            // exactly; crediting them here too would double-count each pest kill.
            .filterNot(MobBinding::isGardenPest)
        if (candidates.isEmpty()) return emptyList()
        return candidatesForIsland(candidates, signal.island).map { EliteBoardGain(it.boardId, signal.amount) }
    }

    /**
     * Narrows ambiguous families to the current island.
     *
     * Only genuine duplicates (`bat`, `enderman`) are scoped this way. Applying island scope to
     * every board would break boards whose Elite bucket is broader than where the mob actually
     * spawns, e.g. `mining_3:glacite_walker` maps to Dwarven Mines but Glacite Walkers also spawn in
     * the Glacite Tunnels and Mineshafts.
     */
    private fun candidatesForIsland(candidates: List<MobBinding>, island: SkyBlockIsland?): List<MobBinding> {
        if (candidates.size <= 1 || island == null) return candidates
        val scoped = candidates.filter { binding -> binding.island == island }
        return scoped.ifEmpty { candidates }
    }

    private fun resolvePestKill(signal: EliteLocalSignal.PestKill): List<EliteBoardGain> {
        val key = eliteMobFamilyKey(signal.pestName)
        val specific = indexesFor().mobsByFamily[key].orEmpty()
            .filter(MobBinding::isGardenPest)
            .map(MobBinding::boardId)
        val boards = indexesFor().pestBoards + specific
        return boards.distinct().map { EliteBoardGain(it, signal.amount) }
    }

    private fun resolveCollection(signal: EliteLocalSignal.CollectionGain): List<EliteBoardGain> {
        val key = eliteCollectionJoinKey(signal.itemId)
        return indexesFor().boardsByCollection[key].orEmpty().map { EliteBoardGain(it, signal.amount) }
    }

    private fun resolveSkill(signal: EliteLocalSignal.SkillXp): List<EliteBoardGain> {
        val slug = signal.skillSlug.lowercase(Locale.ROOT)
        return indexesFor().boardsBySkill[slug].orEmpty().map { EliteBoardGain(it, signal.amount) }
    }

    private fun resolveSlayer(signal: EliteLocalSignal.SlayerXp): List<EliteBoardGain> {
        val slug = signal.slayerSlug.lowercase(Locale.ROOT)
        val boards = buildList {
            addAll(indexesFor().boardsBySlayer[slug].orEmpty())
            addAll(indexesFor().totalSlayerBoards)
        }
        return boards.distinct().map { EliteBoardGain(it, signal.amount) }
    }

    private fun indexesFor(): BindingIndexes {
        val catalogVersion = EliteLeaderboardCatalog.version
        indexes?.let { cached -> if (indexedVersion == catalogVersion) return cached }
        return synchronized(this) {
            indexes?.takeIf { indexedVersion == catalogVersion } ?: buildIndexes().also {
                indexes = it
                indexedVersion = catalogVersion
            }
        }
    }

    private fun buildIndexes(): BindingIndexes {
        val boards = EliteLeaderboardCatalog.entries()
        val mobs = mutableMapOf<String, MutableList<MobBinding>>()
        val collections = mutableMapOf<String, MutableList<String>>()
        val skills = mutableMapOf<String, MutableList<String>>()
        val slayers = mutableMapOf<String, MutableList<String>>()
        val totalSlayer = mutableListOf<String>()
        val pestBoards = mutableListOf<String>()
        for (info in boards) {
            indexBoard(info, mobs, collections, skills, slayers, totalSlayer, pestBoards)
        }
        return BindingIndexes(
            mobsByFamily = mobs.mapValues { (_, value) -> value.toList() },
            boardsByCollection = collections.mapValues { (_, value) -> value.distinct() },
            boardsBySkill = skills.mapValues { (_, value) -> value.distinct() },
            boardsBySlayer = slayers.mapValues { (_, value) -> value.distinct() },
            totalSlayerBoards = totalSlayer.toList(),
            pestBoards = pestBoards.toList(),
        )
    }

    private fun indexBoard(
        info: EliteLeaderboardInfo,
        mobs: MutableMap<String, MutableList<MobBinding>>,
        collections: MutableMap<String, MutableList<String>>,
        skills: MutableMap<String, MutableList<String>>,
        slayers: MutableMap<String, MutableList<String>>,
        totalSlayer: MutableList<String>,
        pestBoards: MutableList<String>,
    ) {
        val signal = eliteBoardSignal(info)
        when (signal) {
            EliteBoardSignal.MOB_KILL -> indexMobBoard(info, mobs)
            EliteBoardSignal.COLLECTION -> info.collectionId?.let { collectionId ->
                collections.getOrPut(eliteCollectionJoinKey(collectionId), ::mutableListOf).add(info.id)
            }
            EliteBoardSignal.SKILL_XP -> eliteSkillSlug(info)?.let { slug ->
                skills.getOrPut(slug, ::mutableListOf).add(info.id)
            }
            EliteBoardSignal.SLAYER_XP -> {
                val slug = eliteSlayerSlug(info)
                if (slug.isNullOrEmpty()) totalSlayer.add(info.id) else slayers.getOrPut(slug, ::mutableListOf).add(info.id)
            }
            EliteBoardSignal.PEST_KILL -> pestBoards.add(info.id)
            EliteBoardSignal.NONE -> Unit
        }
    }

    private fun indexMobBoard(info: EliteLeaderboardInfo, mobs: MutableMap<String, MutableList<MobBinding>>) {
        val bestiaryId = info.bestiaryId ?: return
        // A Garden bucket alone is not enough: `garden:timestalk_clone` and `garden:zombuddy` are
        // ordinary Bestiary mobs, while the 15 garden pests live in the Pests category.
        val isGardenPest = info.category == PESTS_CATEGORY &&
            bestiaryId.substringBefore(':', "").equals(GARDEN_BESTIARY_BUCKET, ignoreCase = true)
        val binding = MobBinding(
            boardId = info.id,
            island = eliteBestiaryIsland(bestiaryId),
            isGardenPest = isGardenPest,
        )
        val familyKey = eliteBestiaryFamilyKey(bestiaryId)
        mobs.getOrPut(familyKey, ::mutableListOf).add(binding)
        // Alternate nameplates the bundled Bestiary data knows about, e.g. "Scatha" -> stoneworm.
        SkyBlockBestiaryFamilies.mobNames(listOf(familyKey)).forEach { alias ->
            val aliasKey = eliteMobFamilyKey(alias)
            if (aliasKey.isNotEmpty() && aliasKey != familyKey) {
                mobs.getOrPut(aliasKey, ::mutableListOf).add(binding)
            }
        }
    }

    private data class MobBinding(
        val boardId: String,
        val island: SkyBlockIsland?,
        val isGardenPest: Boolean,
    )

    private data class BindingIndexes(
        val mobsByFamily: Map<String, List<MobBinding>>,
        val boardsByCollection: Map<String, List<String>>,
        val boardsBySkill: Map<String, List<String>>,
        val boardsBySlayer: Map<String, List<String>>,
        val totalSlayerBoards: List<String>,
        val pestBoards: List<String>,
    )
}
