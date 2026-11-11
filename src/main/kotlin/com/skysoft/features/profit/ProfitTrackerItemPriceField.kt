package com.skysoft.features.profit

import com.mojang.blaze3d.platform.InputConstants
import com.skysoft.gui.OverlayControlArea
import com.skysoft.gui.tracker.TrackedItemPanelStyle.MUTED_COLOR
import com.skysoft.gui.tracker.TrackedItemPanelStyle.ROW_HEIGHT
import com.skysoft.gui.tracker.TrackedItemPanelStyle.TEXT_HEIGHT
import com.skysoft.gui.tracker.TrackedItemPanelStyle.TITLE_COLOR
import com.skysoft.gui.tracker.TrackedItemPanelStyle.drawHover
import com.skysoft.gui.tracker.TrackedItemPanelStyle.drawText
import com.skysoft.gui.tracker.TrackedItemPanelStyle.styledText
import com.skysoft.utils.NumberUtilities.coinFormat
import com.skysoft.utils.NumberUtilities.parseCompactNumberOrNull
import com.skysoft.utils.gui.OverlayPanelStyle
import com.skysoft.utils.gui.Rect
import com.skysoft.utils.gui.TextFieldState
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

internal class ProfitTrackerItemPriceField {
    private val field = TextFieldState(maxLength = PRICE_MAXIMUM_LENGTH)
    private var editingItemId: String? = null
    private var fieldBounds: Rect? = null
    private var hoveredMouseX = 0
    private var fieldHovered = false

    fun begin(target: ProfitTrackerTarget, itemId: String) {
        editingItemId = itemId
        field.text = ProfitTrackerItemCustomizations.customPrice(target, itemId)?.let(::customItemPriceInput).orEmpty()
        field.focused = true
    }

    fun cancel() {
        editingItemId = null
        field.focused = false
        fieldBounds = null
        fieldHovered = false
    }

    fun isEditingItem(itemId: String): Boolean = editingItemId == itemId

    fun rowHeight(): Int = if (editingItemId == null) ROW_HEIGHT else PRICE_FIELD_HEIGHT

    fun width(target: ProfitTrackerTarget, itemId: String): Int {
        val textWidth = Minecraft.getInstance().font.width(label(target, itemId))
        return if (editingItemId == itemId) textWidth + PRICE_FIELD_GAP + PRICE_FIELD_WIDTH else textWidth
    }

    fun wasKeyPressHandled(event: KeyEvent, save: (String, Double) -> Unit): Boolean {
        val itemId = editingItemId ?: return false
        if (!field.focused) return false
        when (event.key()) {
            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> parseCustomItemPrice(field.text)?.let { price ->
                save(itemId, price)
                cancel()
            }
            InputConstants.KEY_ESCAPE -> cancel()
            else -> {
                field.keyPressed(event)
                field.text = sanitizeCustomItemPriceInput(field.text)
            }
        }
        return true
    }

    fun wasCharTypedHandled(event: CharacterEvent): Boolean {
        if (editingItemId == null || !field.focused) return false
        val character = event.codepointAsString().singleOrNull()?.lowercaseChar()
        if (character != null && (character.isDigit() || character == '.' || character in PRICE_SUFFIXES)) {
            field.charTyped(event)
            field.text = sanitizeCustomItemPriceInput(field.text)
        }
        return true
    }

    fun focusHoveredField() {
        field.focused = true
        val bounds = fieldBounds ?: return
        if (fieldHovered) field.placeCursorAt(hoveredMouseX, bounds.x, bounds.width)
    }

    fun render(
        context: GuiGraphicsExtractor,
        target: ProfitTrackerTarget,
        itemId: String,
        panelX: Int,
        panelWidth: Int,
        y: Int,
        mouseX: Int,
        mouseY: Int,
        opacity: Double,
        interactive: Boolean,
    ): OverlayControlArea<ProfitTrackerControl>? {
        val text = label(target, itemId)
        val textX = panelX + OverlayPanelStyle.PADDING
        val height = rowHeight()
        fieldBounds = if (editingItemId == itemId) {
            Rect(textX + Minecraft.getInstance().font.width(text) + PRICE_FIELD_GAP, y, PRICE_FIELD_WIDTH, height)
        } else {
            null
        }
        val rowBounds = Rect(panelX, y, panelWidth, height)
        fieldHovered = fieldBounds?.contains(mouseX, mouseY) == true
        if (fieldHovered) hoveredMouseX = mouseX
        val hovered = OverlayControlArea<ProfitTrackerControl>(
            ProfitTrackerControl.ItemCustomPrice(itemId),
            if (fieldHovered) requireNotNull(fieldBounds) else rowBounds,
            CUSTOM_PRICE_TOOLTIP,
        ).takeIf { interactive && rowBounds.contains(mouseX, mouseY) }
        hovered?.bounds?.let { bounds -> drawHover(context, bounds, opacity) }
        drawText(context, text, textX, y + (height - TEXT_HEIGHT) / 2, opacity)
        fieldBounds?.let { bounds ->
            field.render(
                context,
                bounds.x,
                bounds.y,
                bounds.width,
                bounds.height,
                "Price...",
                alpha = opacity,
                textColor = TITLE_COLOR,
            )
        }
        return hovered
    }

    private fun label(target: ProfitTrackerTarget, itemId: String): MutableComponent {
        val prefix = styledText("Custom Price ", MUTED_COLOR)
        if (editingItemId == itemId) return prefix
        val value = ProfitTrackerItemCustomizations.customPrice(target, itemId)?.coinFormat() ?: "Live"
        return prefix.append(styledText("[$value]", TITLE_COLOR, bold = true))
    }
}

internal fun customPricePanelRow(itemId: String, priceField: ProfitTrackerItemPriceField): PanelRow =
    PanelRow(Component.empty(), priceItemId = itemId, priceField = priceField)

internal fun wasCustomItemPriceClicked(
    panel: ProfitTrackerItemPanel,
    target: ProfitTrackerTarget,
    itemId: String,
    button: Int,
): Boolean {
    val field = panel.priceField
    if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
        if (ProfitTrackerItemCustomizations.customPrice(target, itemId) == null && !field.isEditingItem(itemId)) {
            return false
        }
        ProfitTrackerItemCustomizations.setCustomPrice(target, itemId, null)
        field.cancel()
        return true
    }
    if (button != InputConstants.MOUSE_BUTTON_LEFT) return false
    if (field.isEditingItem(itemId)) {
        field.focusHoveredField()
    } else {
        panel.itemModifier.cancel()
        field.begin(target, itemId)
    }
    return true
}

private fun parseCustomItemPrice(text: String): Double? =
    text.parseCompactNumberOrNull()?.value?.takeIf { it.isFinite() && it > 0.0 }

private fun sanitizeCustomItemPriceInput(raw: String): String {
    val suffix = raw.lastOrNull()?.lowercaseChar()?.takeIf { it in PRICE_SUFFIXES }
    val body = if (suffix == null) raw else raw.dropLast(1)
    return buildString {
        var dotSeen = false
        body.forEach { char ->
            when {
                char.isDigit() -> append(char)
                char == '.' && !dotSeen -> {
                    dotSeen = true
                    append(char)
                }
            }
        }
        if (suffix != null) append(suffix)
    }
}

private fun customItemPriceInput(price: Double): String {
    val compact = COMPACT_PRICE_UNITS.firstNotNullOfOrNull { (divisor, suffix) ->
        if (price < divisor) return@firstNotNullOfOrNull null
        val text = formatPriceInput(price / divisor)
        text.takeIf { text.toDoubleOrNull()?.times(divisor) == price }?.plus(suffix)
    }
    if (compact != null) return compact
    val plain = formatPriceInput(price)
    return plain.takeIf { it.toDoubleOrNull() == price } ?: price.toString()
}

private fun formatPriceInput(value: Double): String =
    DecimalFormat("0.########", DecimalFormatSymbols(Locale.US)).apply { isGroupingUsed = false }.format(value)

private const val PRICE_MAXIMUM_LENGTH = 24
private const val PRICE_FIELD_WIDTH = 72
private const val PRICE_FIELD_HEIGHT = 18
private const val PRICE_FIELD_GAP = 3
private const val PRICE_SUFFIXES = "kmb"
private val COMPACT_PRICE_UNITS = listOf(1_000_000_000.0 to "b", 1_000_000.0 to "m", 1_000.0 to "k")
private val CUSTOM_PRICE_TOOLTIP = listOf(
    "§7Left click to set a coin price. Right click to use the live price.",
    "§7Accepts a number or a k, m, or b suffix, such as 12.5m.",
)
