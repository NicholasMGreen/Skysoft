package com.skysoft.features.inventory.shopping

import com.skysoft.config.SkysoftConfigGui
import com.skysoft.data.ProfileStorageApi
import com.skysoft.data.hypixel.HypixelLocationState
import com.skysoft.data.skyblock.SkyBlockDataRepository
import com.skysoft.data.skyblock.price.BazaarProductAvailability
import com.skysoft.data.skyblock.price.SkyBlockPriceData
import com.skysoft.features.inventory.InventoryOverlayInput
import com.skysoft.features.inventory.itemlist.ItemListViewerScreen
import com.skysoft.gui.GuiOverlay
import com.skysoft.gui.GuiOverlayContextType
import com.skysoft.gui.GuiOverlayLayer
import com.skysoft.gui.GuiOverlayRegistry
import com.skysoft.gui.HudEditorElement
import com.skysoft.gui.HudEditorRegistry
import com.skysoft.gui.OverlayControlArea
import com.skysoft.gui.OverlayControlMouse
import com.skysoft.gui.tooltip.SkysoftNativeTooltip
import com.skysoft.utils.MinecraftClient
import com.skysoft.utils.NumberUtilities.addSeparators
import com.skysoft.utils.SkysoftErrorBoundary
import com.skysoft.utils.SoundUtilities
import com.skysoft.utils.TextUtilities.truncateLegacyText
import com.skysoft.utils.gui.OverlayPanelStyle
import com.skysoft.utils.gui.Rect
import com.skysoft.utils.input.InputHandlingResult
import com.skysoft.utils.render.LegacyTextRenderer
import com.skysoft.utils.renderables.GuiRenderable
import com.skysoft.utils.renderables.primitives.ItemIconRenderable
import com.skysoft.utils.renderables.renderAt
import kotlin.math.floor
import kotlin.math.roundToInt
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW

object ShoppingList {
    fun register() {
        ProfileStorageApi.registerConsumer("Shopping List") { config.enabled }
        SkyBlockDataRepository.Demand.register("Shopping List") { config.enabled }
        registerInput()
        GuiOverlayRegistry.register(
            GuiOverlay(
                id = "shopping_list",
                layer = GuiOverlayLayer.BELOW_SCREEN,
                contexts = GuiOverlayContextType.entries.toSet(),
                screenForegroundContexts = GuiOverlayContextType.INVENTORIES,
                render = { context, _ -> renderHud(context) },
            ),
        )
        HudEditorRegistry.register(object : HudEditorElement {
            override val id: String = "shopping_list"
            override val label: String = "Shopping List"
            override val position get() = config.position
            override val hasEditorBackground: Boolean get() = !config.details.showBackground
            override fun width(): Int = buildRenderable().width
            override fun height(): Int = buildRenderable().height
            override fun isVisible(): Boolean = config.enabled
            override fun absoluteX(width: Int): Int = position.getAbsX0AllowingOverflow(0)
            override fun absoluteY(height: Int): Int = position.getAbsY0AllowingOverflow(0)
            override fun renderEditor(context: GuiGraphicsExtractor) = buildRenderable().render(context)
            override fun applyEditorDrag(deltaX: Int, deltaY: Int): InputHandlingResult {
                val targetX = position.getAbsX0AllowingOverflow(0) + deltaX
                val targetY = position.getAbsY0AllowingOverflow(0) + deltaY
                position.moveToAbsoluteAllowingOverflow(targetX, targetY, 0, 0)
                return InputHandlingResult.CONSUMED
            }
            override fun applyEditorScroll(scrollY: Double): InputHandlingResult {
                position.scale += if (scrollY > 0.0) EDITOR_SCALE_STEP else -EDITOR_SCALE_STEP
                return InputHandlingResult.CONSUMED
            }
            override fun openConfig() = SkysoftConfigGui.open("Shopping List")
        })
    }
}

private val config get() = SkysoftConfigGui.config().inventory.shoppingList
private var scrollOffset = 0
private var hoveredControl: OverlayControlArea<ShoppingListControl>? = null
private var isHudHovered = false

private fun registerInput() {
    ScreenEvents.BEFORE_INIT.register { _, screen, _, _ ->
        if (screen !is AbstractContainerScreen<*>) return@register
        ScreenMouseEvents.allowMouseClick(screen).register { _, click ->
            SkysoftErrorBoundary.value("Shopping List mouse click", true) {
                shouldAllowClick(screen, click)
            }
        }
        ScreenMouseEvents.allowMouseScroll(screen).register { _, mouseX, mouseY, _, verticalAmount ->
            SkysoftErrorBoundary.value("Shopping List mouse scroll", true) {
                InventoryOverlayInput.isPointCovered(screen, mouseX, mouseY) || !wasScrollHandled(verticalAmount)
            }
        }
    }
}

private fun shouldAllowClick(screen: AbstractContainerScreen<*>, click: MouseButtonEvent): Boolean {
    if (!isVisible()) return true
    if (InventoryOverlayInput.isPointCovered(screen, click.x(), click.y())) return true
    val control = hoveredControl?.action ?: return true
    val handled = when (control) {
        is ShoppingListControl.Item -> wasItemClickHandled(screen, control.itemId, click.button())
    }
    if (handled) SoundUtilities.playClickSound()
    return !handled
}

private fun wasItemClickHandled(screen: AbstractContainerScreen<*>, itemId: String, button: Int): Boolean =
    when (button) {
        GLFW.GLFW_MOUSE_BUTTON_LEFT -> wasLeftClickHandled(screen, itemId)
        GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
            ShoppingListState.remove(itemId)
            true
        }
        else -> false
    }

private fun wasLeftClickHandled(screen: AbstractContainerScreen<*>, itemId: String): Boolean {
    val key = SkyBlockDataRepository.itemKey(itemId)
    if (!isOnBazaar(itemId)) {
        if (!SkysoftConfigGui.config().inventory.itemList.enabled) return false
        MinecraftClient.setScreen(ItemListViewerScreen(screen, key))
        return true
    }
    val connection = Minecraft.getInstance().connection ?: return false
    val itemName = SkyBlockDataRepository.entry(key)?.displayName ?: return false
    connection.sendCommand("bz $itemName")
    MinecraftClient.setScreen(null)
    return true
}

private fun isOnBazaar(itemId: String): Boolean =
    SkyBlockPriceData.bazaarAvailability(itemId) == BazaarProductAvailability.AVAILABLE

private fun leftClickActionLabel(itemId: String): String =
    if (isOnBazaar(itemId)) {
        "§eLeft-click §7to open Bazaar"
    } else {
        "§eLeft-click §7to open Item List"
    }

private fun wasScrollHandled(verticalAmount: Double): Boolean {
    if (!isVisible() || !isHudHovered || verticalAmount == 0.0) return false
    val maximumOffset = maximumScrollOffset(ShoppingListState.items().size)
    if (maximumOffset == 0) return false
    scrollOffset = (scrollOffset + if (verticalAmount < 0.0) 1 else -1).coerceIn(0, maximumOffset)
    return true
}

private fun renderHud(context: GuiGraphicsExtractor) {
    if (!isVisible()) {
        clearInteraction()
        return
    }
    val minecraft = Minecraft.getInstance()
    val inventoryScreen = MinecraftClient.screen(minecraft) as? AbstractContainerScreen<*>
    val renderable = buildRenderable()
    if (renderable.width <= 0 || renderable.height <= 0) {
        clearInteraction()
        return
    }
    val window = minecraft.window
    val mouseX = minecraft.mouseHandler.getScaledXPos(window).toInt()
    val mouseY = minecraft.mouseHandler.getScaledYPos(window).toInt()
    val (normalMouseX, normalMouseY) = OverlayControlMouse.normalPoint(mouseX, mouseY)
    val (screenMouseX, screenMouseY) = OverlayControlMouse.screenPoint(mouseX, mouseY)
    val interactive = inventoryScreen != null &&
        !InventoryOverlayInput.isPointCovered(inventoryScreen, screenMouseX.toDouble(), screenMouseY.toDouble())
    val scale = config.position.effectiveScale
    val scaledHeight = (renderable.height * scale).roundToInt()
    val x = config.position.getAbsX0AllowingOverflow(0)
    val y = config.position.getAbsY0AllowingOverflow(scaledHeight)
    val localMouseX = floor((normalMouseX - x) / scale).toInt()
    val localMouseY = floor((normalMouseY - y) / scale).toInt()

    context.nextStratum()
    context.pose().pushMatrix()
    context.pose().translate(x.toFloat(), y.toFloat())
    context.pose().scale(scale, scale)
    val localControl = renderable.renderInteractive(
        context,
        localMouseX.takeIf { interactive },
        localMouseY.takeIf { interactive },
    )
    context.pose().popMatrix()

    isHudHovered = interactive &&
        localMouseX in 0 until renderable.width &&
        localMouseY in 0 until renderable.height
    hoveredControl = localControl?.let { control ->
        OverlayControlArea(
            action = control.action,
            bounds = Rect(
                x = x + (control.bounds.x * scale).roundToInt(),
                y = y + (control.bounds.y * scale).roundToInt(),
                width = (control.bounds.width * scale).roundToInt().coerceAtLeast(1),
                height = (control.bounds.height * scale).roundToInt().coerceAtLeast(1),
            ),
            tooltipLines = control.tooltipLines,
        )
    }
    if (interactive) hoveredControl?.let { control ->
        context.nextStratum()
        val itemId = (control.action as? ShoppingListControl.Item)?.itemId
        if (itemId != null) {
            val entry = shoppingListEntry(itemId) ?: return@let
            SkysoftNativeTooltip.setItemActionForNextFrame(
                context,
                entry.stack ?: ItemStack.EMPTY,
                null,
                entry.name,
                screenMouseX,
                screenMouseY,
                actionLines = listOf(
                    leftClickActionLabel(itemId),
                    "§eRight-click §7to remove from Shopping List",
                ),
            )
        }
    }
}

private fun clearInteraction() {
    hoveredControl = null
    isHudHovered = false
}

private fun isVisible(): Boolean {
    if (!config.enabled || !HypixelLocationState.inSkyBlock) return false
    val minecraft = Minecraft.getInstance()
    if (MinecraftClient.isGuiHidden(minecraft)) return false
    val items = ShoppingListState.items()
    if (config.settings.hideWhenEmpty && items.isEmpty()) return false
    if (config.settings.isOnlyInMenus && MinecraftClient.screen(minecraft) !is AbstractContainerScreen<*>) {
        return false
    }
    return true
}

private fun buildRenderable(): ShoppingListRenderable {
    val items = ShoppingListState.items()
    val maximumItems = config.settings.maximumItems.coerceIn(1, MAXIMUM_DISPLAY_ITEMS)
    val maximumOffset = (items.size - maximumItems).coerceAtLeast(0)
    scrollOffset = scrollOffset.coerceIn(0, maximumOffset)
    val displayed = items
        .asSequence()
        .drop(scrollOffset)
        .take(maximumItems)
        .mapNotNull { shoppingListEntry(it.itemId)?.copy(targetAmount = it.targetAmount) }
        .toList()
    return ShoppingListRenderable(
        items = displayed,
        hiddenAbove = scrollOffset,
        hiddenBelow = (items.size - scrollOffset - displayed.size).coerceAtLeast(0),
        showTitle = config.details.showTitle,
        showIcons = config.details.showItemIcons,
        showQuantities = config.details.showQuantities,
        background = config.details.showBackground,
    )
}

private fun shoppingListEntry(itemId: String): ShoppingListEntry? {
    val key = SkyBlockDataRepository.itemKey(itemId)
    val entry = SkyBlockDataRepository.entry(key)
    return ShoppingListEntry(
        itemId = itemId,
        name = entry?.formattedDisplayName ?: itemId,
        targetAmount = 1L,
        stack = SkyBlockDataRepository.displayStack(key),
    )
}

private fun maximumScrollOffset(itemCount: Int): Int =
    (itemCount - config.settings.maximumItems.coerceIn(1, MAXIMUM_DISPLAY_ITEMS)).coerceAtLeast(0)

private class ShoppingListRenderable(
    items: List<ShoppingListEntry>,
    private val hiddenAbove: Int,
    private val hiddenBelow: Int,
    private val showTitle: Boolean,
    private val showIcons: Boolean,
    private val showQuantities: Boolean,
    private val background: Boolean,
) : GuiRenderable {
    private val padding = if (background) OverlayPanelStyle.PADDING else 0
    private val rows = items.map { item ->
        ShoppingListRow(
            item = item,
            name = item.name.truncateLegacyText(MAXIMUM_ITEM_NAME_LENGTH),
            value = "§7x§e${item.targetAmount.addSeparators()}".takeIf { showQuantities },
            stack = item.stack,
            reserveIcon = showIcons,
        )
    }
    private val emptyText = "§7No shopping list items."
    private val indicatorText = when {
        hiddenAbove <= 0 && hiddenBelow <= 0 -> ""
        else -> buildList {
            if (hiddenAbove > 0) add("$hiddenAbove above")
            if (hiddenBelow > 0) add("$hiddenBelow more")
        }.joinToString(" §8• §7", prefix = "§7", postfix = "...")
    }
    private val titleText = "§e§lShopping List"
    private val contentWidth = maxOf(
        MINIMUM_WIDTH,
        if (showTitle) LegacyTextRenderer.width(titleText) else 0,
        rows.maxOfOrNull(ShoppingListRow::width) ?: LegacyTextRenderer.width(emptyText),
        LegacyTextRenderer.width(indicatorText),
    )

    override val width: Int = contentWidth + padding * 2
    override val height: Int = padding * 2 +
        (if (showTitle) TITLE_HEIGHT else 0) +
        (if (rows.isEmpty()) TEXT_ROW_HEIGHT else rows.size * ITEM_ROW_HEIGHT) +
        (if (indicatorText.isEmpty()) 0 else TEXT_ROW_HEIGHT)

    override fun render(context: GuiGraphicsExtractor) {
        renderInteractive(context, null, null)
    }

    fun renderInteractive(context: GuiGraphicsExtractor, mouseX: Int?, mouseY: Int?): LocalShoppingControl? {
        if (background) OverlayPanelStyle.draw(context, 0, 0, width, height)
        var y = padding
        if (showTitle) {
            LegacyTextRenderer.draw(context, titleText, padding, y)
            y += TITLE_HEIGHT
        }
        var hovered: LocalShoppingControl? = null
        if (rows.isEmpty()) {
            LegacyTextRenderer.draw(context, emptyText, padding, y)
        } else {
            rows.forEach { row ->
                hovered = row.renderInteractive(context, padding, width - padding, y, mouseX, mouseY) ?: hovered
                y += ITEM_ROW_HEIGHT
            }
        }
        if (indicatorText.isNotEmpty()) {
            if (rows.isEmpty()) y += TEXT_ROW_HEIGHT
            LegacyTextRenderer.draw(context, indicatorText, padding, y)
        }
        return hovered
    }
}

private data class ShoppingListRow(
    val item: ShoppingListEntry,
    val name: String,
    val value: String?,
    val stack: ItemStack?,
    val reserveIcon: Boolean,
) {
    private val iconWidth = if (reserveIcon) ITEM_TEXT_OFFSET else 0
    private val nameWidth = LegacyTextRenderer.width(name)
    private val valueWidth = value?.let(LegacyTextRenderer::width) ?: 0
    private val valueXOffset = iconWidth + nameWidth + if (value != null) COLUMN_GAP else 0
    val width: Int = valueXOffset + valueWidth

    fun renderInteractive(
        context: GuiGraphicsExtractor,
        left: Int,
        right: Int,
        y: Int,
        mouseX: Int?,
        mouseY: Int?,
    ): LocalShoppingControl? {
        val bounds = Rect(left, y, (right - left).coerceAtLeast(1), ITEM_ROW_HEIGHT)
        val hovered = mouseX != null && mouseY != null && bounds.contains(mouseX, mouseY)
        if (hovered) {
            context.fill(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height, CONTROL_HOVER_COLOR)
        }
        if (reserveIcon) stack?.let { ItemIconRenderable(it, ICON_SCALE).renderAt(context, left, y) }
        LegacyTextRenderer.draw(context, name, left + iconWidth, y + ITEM_TEXT_Y_OFFSET)
        value?.let { LegacyTextRenderer.draw(context, it, left + valueXOffset, y + ITEM_TEXT_Y_OFFSET) }
        return LocalShoppingControl(ShoppingListControl.Item(item.itemId), bounds, emptyList()).takeIf { hovered }
    }
}

private data class ShoppingListEntry(
    val itemId: String,
    val name: String,
    val targetAmount: Long,
    val stack: ItemStack?,
)

private data class LocalShoppingControl(
    val action: ShoppingListControl,
    val bounds: Rect,
    val tooltipLines: List<String>,
)

private sealed interface ShoppingListControl {
    data class Item(val itemId: String) : ShoppingListControl
}

private const val MAXIMUM_DISPLAY_ITEMS = 30
private const val MAXIMUM_ITEM_NAME_LENGTH = 28
private const val MINIMUM_WIDTH = 140
private const val TITLE_HEIGHT = 13
private const val TEXT_ROW_HEIGHT = 11
private const val ITEM_ROW_HEIGHT = 14
private const val ITEM_TEXT_Y_OFFSET = 2
private const val ICON_SCALE = 0.75
private const val ITEM_TEXT_OFFSET = 14
private const val COLUMN_GAP = 8
private const val CONTROL_HOVER_COLOR = 0x20FFFFFF
private const val EDITOR_SCALE_STEP = 0.1f
