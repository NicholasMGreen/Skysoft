package com.skysoft.config

import com.google.gson.annotations.Expose
import com.skysoft.config.core.ConfigRepairable
import com.skysoft.config.core.HudPosition
import io.github.notenoughupdates.moulconfig.annotations.Accordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorInfoText
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import io.github.notenoughupdates.moulconfig.annotations.ConfigVisibleIf

class ShoppingListConfig : ConfigRepairable {
    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Enabled", desc = "Show a movable shopping list of items you want to buy.")
    @field:MainFeatureToggle
    @field:ConfigEditorBoolean
    var enabled = false

    @JvmField
    @field:ConfigOption(
        name = "Chat Commands",
        desc = "§b/ss shopping add <item> [amount]§7 — add or update an item. " +
            "§b/ss shopping remove <item>§7 — remove an item. " +
            "§b/ss shopping clear§7 — clear the list. " +
            "§b/ss shopping list§7 — show tracked items.",
    )
    @field:ConfigEditorInfoText
    @field:ConfigVisibleIf("enabled")
    val chatCommandsInfo: Unit = Unit

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Settings", desc = "Shopping List settings.")
    @field:Accordion
    @field:ConfigVisibleIf("enabled")
    val settings = ShoppingListSettingsConfig()

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Details", desc = "Shopping List appearance.")
    @field:Accordion
    @field:ConfigVisibleIf("enabled")
    val details = ShoppingListDetailsConfig()

    @JvmField
    @field:Expose
    val position = HudPosition(8, 150, centerX = false, centerY = false).rememberDefault()

    override fun repairLoadedValues() {
        settings.maximumItems = settings.maximumItems.coerceIn(MINIMUM_ITEMS, MAXIMUM_ITEMS)
    }
}

class ShoppingListSettingsConfig {
    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Maximum Items", desc = "Maximum shopping list rows shown at once.")
    @field:ConfigEditorSlider(minValue = 1f, maxValue = 30f, minStep = 1f)
    var maximumItems = 12

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Only in Menus", desc = "Only show the Shopping List while a container menu is open.")
    @field:ConfigEditorBoolean
    var isOnlyInMenus = true

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Hide When Empty", desc = "Hide the Shopping List when no items are tracked.")
    @field:ConfigEditorBoolean
    var hideWhenEmpty = true
}

class ShoppingListDetailsConfig {
    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Show Title", desc = "Show the §eShopping List§7 title above tracked items.")
    @field:ConfigEditorBoolean
    var showTitle = true

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Show Item Icons", desc = "Show item icons beside shopping list rows.")
    @field:ConfigEditorBoolean
    var showItemIcons = true

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Show Quantities", desc = "Show the §7x§e1§7 amount beside each shopping list item.")
    @field:ConfigEditorBoolean
    var showQuantities = true

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Show Background", desc = "Draw a dark background behind the Shopping List.")
    @field:ConfigEditorBoolean
    var showBackground = true
}

private const val MINIMUM_ITEMS = 1
private const val MAXIMUM_ITEMS = 30
