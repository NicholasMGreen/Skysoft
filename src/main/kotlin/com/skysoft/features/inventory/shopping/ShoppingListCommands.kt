package com.skysoft.features.inventory.shopping

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.skysoft.data.skyblock.ItemListEntryKind
import com.skysoft.data.skyblock.SkyBlockDataLoadState
import com.skysoft.data.skyblock.SkyBlockDataRepository
import com.skysoft.features.inventory.itemlist.itemCommandSuggestions
import com.skysoft.features.inventory.itemlist.resolveItemListCommandQuery
import com.skysoft.utils.SkysoftChat
import com.skysoft.utils.commands.SkysoftCommandRegistry.Companion.literal
import java.util.Locale
import java.util.concurrent.CompletableFuture
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

object ShoppingListCommands {
    fun command(): LiteralArgumentBuilder<FabricClientCommandSource> =
        literal("shopping")
            .executes { context ->
                SkysoftChat.error(context.source, "Usage: /ss shopping <add|remove|clear|list>")
                0
            }
            .then(
                literal("add")
                    .executes { context ->
                        SkysoftChat.error(context.source, "Usage: /ss shopping add <item> [amount]")
                        0
                    }
                    .then(
                        greedyItemArgument("item")
                            .suggests { _, builder -> suggestItems(builder) }
                            .executes { context ->
                                addItem(context.source, StringArgumentType.getString(context, "item"))
                            },
                    ),
            )
            .then(
                literal("remove")
                    .executes { context ->
                        SkysoftChat.error(context.source, "Usage: /ss shopping remove <item>")
                        0
                    }
                    .then(
                        greedyItemArgument("item")
                            .suggests { _, builder -> suggestTrackedItems(builder) }
                            .executes { context ->
                                removeItem(context.source, StringArgumentType.getString(context, "item"))
                            },
                    ),
            )
            .then(literal("clear").executes { clearItems(it.source) })
            .then(literal("list").executes { listItems(it.source) })
}

private fun greedyItemArgument(name: String): RequiredArgumentBuilder<FabricClientCommandSource, String> =
    RequiredArgumentBuilder.argument(name, StringArgumentType.greedyString())

private fun addItem(source: FabricClientCommandSource, raw: String): Int {
    ensureReady(source) ?: return 0
    val (query, amount) = parseAddArguments(raw)
    val entry = resolveSkyBlockItem(query)
    if (entry == null) {
        SkysoftChat.error(source, "No SkyBlock item found for '$query'.")
        return 0
    }
    val alreadyTracked = ShoppingListState.contains(entry.key.id)
    if (!alreadyTracked && ShoppingListState.isFull()) {
        SkysoftChat.error(source, "Shopping list is full (max $MAXIMUM_TRACKED_ITEMS items).")
        return 0
    }
    ShoppingListState.add(entry.key.id, amount)
    val action = if (alreadyTracked) "Updated" else "Added"
    SkysoftChat.feedback(source, "$action §e${entry.displayName}§r x$amount on the Shopping List.")
    return Command.SINGLE_SUCCESS
}

private fun removeItem(source: FabricClientCommandSource, raw: String): Int {
    ensureReady(source) ?: return 0
    val query = raw.trim()
    val tracked = ShoppingListState.items()
    val byId = tracked.firstOrNull { it.itemId.equals(query, ignoreCase = true) }
    val entry = byId?.let { SkyBlockDataRepository.entry(SkyBlockDataRepository.itemKey(it.itemId)) }
        ?: resolveSkyBlockItem(query)
    val itemId = byId?.itemId ?: entry?.key?.id
    if (itemId == null || !ShoppingListState.contains(itemId)) {
        SkysoftChat.error(source, "No shopping list entry found for '$query'.")
        return 0
    }
    ShoppingListState.remove(itemId)
    val name = entry?.displayName
        ?: SkyBlockDataRepository.entry(SkyBlockDataRepository.itemKey(itemId))?.displayName
        ?: itemId
    SkysoftChat.feedback(source, "Removed §e$name§r from the Shopping List.")
    return Command.SINGLE_SUCCESS
}

private fun clearItems(source: FabricClientCommandSource): Int {
    if (ShoppingListState.items().isEmpty()) {
        SkysoftChat.feedback(source, "Shopping List is already empty.")
        return Command.SINGLE_SUCCESS
    }
    ShoppingListState.clear()
    SkysoftChat.feedback(source, "Cleared the Shopping List.")
    return Command.SINGLE_SUCCESS
}

private fun listItems(source: FabricClientCommandSource): Int {
    val items = ShoppingListState.items()
    if (items.isEmpty()) {
        SkysoftChat.feedback(source, "Shopping List is empty.")
        return Command.SINGLE_SUCCESS
    }
    SkysoftChat.feedback(source, "Shopping List (${items.size}):")
    items.forEach { item ->
        val name = SkyBlockDataRepository.entry(SkyBlockDataRepository.itemKey(item.itemId))?.displayName
            ?: item.itemId
        SkysoftChat.feedback(source, " §7- §f$name §7x§e${item.targetAmount}")
    }
    return Command.SINGLE_SUCCESS
}

private fun ensureReady(source: FabricClientCommandSource): Unit? {
    SkyBlockDataRepository.ensureLoaded()
    if (SkyBlockDataRepository.status.state != SkyBlockDataLoadState.READY) {
        SkysoftChat.error(source, "Item List data is not ready yet.")
        return null
    }
    return Unit
}

private fun resolveSkyBlockItem(query: String) =
    resolveItemListCommandQuery(query, SkyBlockDataRepository.ItemListData.search(query))
        ?.takeIf { it.key.kind == ItemListEntryKind.SKYBLOCK }

private fun parseAddArguments(raw: String): Pair<String, Long> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "" to 1L
    val parts = trimmed.split(Regex("\\s+"))
    val last = parts.last()
    if (parts.size >= 2 && last.all(Char::isDigit)) {
        val amount = last.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
        return parts.dropLast(1).joinToString(" ") to amount
    }
    return trimmed to 1L
}

private fun suggestItems(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
    SkyBlockDataRepository.ensureLoaded()
    if (SkyBlockDataRepository.status.state != SkyBlockDataLoadState.READY) return builder.buildFuture()
    val remaining = builder.remaining.lowercase(Locale.US)
    val results = SkyBlockDataRepository.ItemListData.search(builder.remaining)
        .filter { it.key.kind == ItemListEntryKind.SKYBLOCK }
    itemCommandSuggestions(results, remaining).take(MAX_SUGGESTIONS).forEach(builder::suggest)
    return builder.buildFuture()
}

private fun suggestTrackedItems(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
    SkyBlockDataRepository.ensureLoaded()
    val remaining = builder.remaining.lowercase(Locale.US)
    ShoppingListState.items().asSequence()
        .flatMap { item ->
            val entry = SkyBlockDataRepository.entry(SkyBlockDataRepository.itemKey(item.itemId))
            sequenceOf(entry?.displayName, item.itemId)
        }
        .filterNotNull()
        .filter { remaining.isEmpty() || it.lowercase(Locale.US).startsWith(remaining) }
        .distinct()
        .take(MAX_SUGGESTIONS)
        .forEach(builder::suggest)
    return builder.buildFuture()
}

private const val MAX_SUGGESTIONS = 80
