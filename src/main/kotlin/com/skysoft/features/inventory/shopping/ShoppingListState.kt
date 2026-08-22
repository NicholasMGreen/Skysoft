package com.skysoft.features.inventory.shopping

import com.skysoft.config.SkysoftConfigGui
import com.skysoft.data.ProfileStorage
import com.skysoft.data.ProfileStorageApi
import com.skysoft.data.skyblock.ItemListEntryKey
import com.skysoft.data.skyblock.ItemListEntryKind

object ShoppingListState {
    private val config get() = SkysoftConfigGui.config().inventory.shoppingList

    fun items(): List<ProfileStorage.ShoppingListItemData> =
        ProfileStorageApi.storage.shoppingList.items.toList()

    fun contains(itemId: String): Boolean =
        ProfileStorageApi.storage.shoppingList.items.any { it.itemId == itemId }

    fun contains(key: ItemListEntryKey): Boolean =
        key.kind == ItemListEntryKind.SKYBLOCK && contains(key.id)

    fun isFull(): Boolean =
        ProfileStorageApi.storage.shoppingList.items.size >= MAXIMUM_TRACKED_ITEMS

    fun toggle(key: ItemListEntryKey, targetAmount: Long = 1L) {
        if (key.kind != ItemListEntryKind.SKYBLOCK) return
        if (contains(key.id)) remove(key.id) else add(key.id, targetAmount)
    }

    fun add(itemId: String, targetAmount: Long = 1L) {
        val trimmed = itemId.trim()
        if (trimmed.isEmpty()) return
        val list = ProfileStorageApi.storage.shoppingList.items
        val existing = list.firstOrNull { it.itemId == trimmed }
        if (existing != null) {
            existing.targetAmount = targetAmount.coerceAtLeast(1L)
            ProfileStorageApi.markDirty()
            return
        }
        if (list.size >= MAXIMUM_TRACKED_ITEMS) return
        list += ProfileStorage.ShoppingListItemData(
            itemId = trimmed,
            targetAmount = targetAmount.coerceAtLeast(1L),
        )
        ProfileStorageApi.markDirty()
    }

    fun remove(itemId: String) {
        val removed = ProfileStorageApi.storage.shoppingList.items.removeAll { it.itemId == itemId }
        if (removed) ProfileStorageApi.markDirty()
    }

    fun clear() {
        val list = ProfileStorageApi.storage.shoppingList.items
        if (list.isEmpty()) return
        list.clear()
        ProfileStorageApi.markDirty()
    }

    fun isFeatureEnabled(): Boolean = config.enabled
}

internal const val MAXIMUM_TRACKED_ITEMS = 50
