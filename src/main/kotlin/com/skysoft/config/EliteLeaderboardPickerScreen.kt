package com.skysoft.config

import com.mojang.blaze3d.platform.InputConstants
import com.skysoft.data.skyblock.EliteBoardSignal
import com.skysoft.data.skyblock.EliteCatalogStatus
import com.skysoft.data.skyblock.EliteIntervalType
import com.skysoft.data.skyblock.EliteLeaderboardCatalog
import com.skysoft.data.skyblock.EliteLeaderboardInfo
import com.skysoft.data.skyblock.eliteBoardSignal
import com.skysoft.data.skyblock.normalizeEliteBoardId
import com.skysoft.gui.SkysoftEditorScreen
import com.skysoft.utils.MinecraftClient
import com.skysoft.utils.SoundUtilities
import com.skysoft.utils.gui.OverlayPanelStyle
import com.skysoft.utils.gui.OverlayTextStyle
import com.skysoft.utils.gui.PixelButtonRenderer
import com.skysoft.utils.gui.PixelButtonTone
import com.skysoft.utils.gui.PixelControlColors
import com.skysoft.utils.gui.Rect
import com.skysoft.utils.gui.TextFieldState
import com.skysoft.utils.gui.elide
import com.skysoft.utils.gui.enableScissor
import com.skysoft.utils.input.InputHandlingResult
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

internal class EliteLeaderboardPickerScreen private constructor(
    parent: Screen?,
    private val selectedBoardId: String,
    private val onSelect: (String) -> Unit,
) : SkysoftEditorScreen(Component.literal("Choose Elite Leaderboard"), parent) {
    private val search = TextFieldState(maxLength = MAX_SEARCH_LENGTH).also { it.focused = true }
    private var selectedCategory: String = ALL_CATEGORIES
    private var selectedInterval: EliteIntervalType? = null
    private var scroll = 0
    private var lastFilterKey = ""
    private var panel = Rect(0, 0, 0, 0)
    private var listViewport = Rect(0, 0, 0, 0)
    private var searchBounds = Rect(0, 0, 0, 0)
    private var totalContentHeight = 0
    private val clickTargets = mutableListOf<PickerTarget>()

    init {
        EliteLeaderboardCatalog.ensureLoaded()
    }

    override fun extractBackground(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, SCRIM_COLOR)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        EliteLeaderboardCatalog.ensureLoaded()
        clickTargets.clear()

        val panelWidth = minOf(PREFERRED_WIDTH, (width - SCREEN_MARGIN * 2).coerceAtLeast(MIN_PANEL_WIDTH))
        val panelHeight = minOf(PREFERRED_HEIGHT, (height - SCREEN_MARGIN * 2).coerceAtLeast(MIN_PANEL_HEIGHT))
        panel = Rect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight)
        OverlayPanelStyle.draw(context, panel)

        val innerX = panel.x + INSET
        val innerWidth = panel.width - INSET * 2
        val font = Minecraft.getInstance().font

        val headerText = OverlayTextStyle.title("Choose Elite SkyBlock Leaderboard")
        context.text(font, headerText, innerX, panel.y + INSET, PixelControlColors.TEXT, false)
        val statusLabel = statusLabel()
        if (statusLabel.isNotEmpty()) {
            val statusWidth = font.width(statusLabel)
            context.text(font, statusLabel, innerX + innerWidth - statusWidth, panel.y + INSET + 1, PixelControlColors.MUTED_TEXT, false)
        }

        val searchY = panel.y + HEADER_HEIGHT
        searchBounds = Rect(innerX, searchY, innerWidth, SEARCH_HEIGHT)
        search.render(
            context,
            searchBounds.x,
            searchBounds.y,
            searchBounds.width,
            searchBounds.height,
            "Search leaderboards (e.g. pest kills, farming, blaze)...",
        )

        val filterY = searchY + SEARCH_HEIGHT + GAP
        drawFilterRow(context, font, innerX, filterY, innerWidth, mouseX, mouseY)

        val listTop = filterY + FILTER_HEIGHT + GAP
        val footerTop = panel.y + panel.height - INSET - BUTTON_HEIGHT
        val listHeight = (footerTop - GAP - listTop).coerceAtLeast(ROW_HEIGHT)
        listViewport = Rect(innerX, listTop, innerWidth, listHeight)

        val filterKey = "${search.text}|$selectedCategory|${selectedInterval?.name}"
        if (lastFilterKey != filterKey) {
            lastFilterKey = filterKey
            scroll = 0
        }

        val entries = filteredEntries()
        val customSlugCandidate = customSlugOrNull(entries)
        val rowCount = entries.size + if (customSlugCandidate != null) 1 else 0
        totalContentHeight = rowCount * (ROW_HEIGHT + ROW_GAP)
        scroll = scroll.coerceIn(0, (totalContentHeight - listViewport.height).coerceAtLeast(0))

        context.enableScissor(listViewport)
        try {
            drawEntries(context, font, entries, customSlugCandidate, mouseX, mouseY)
        } finally {
            context.disableScissor()
        }

        val backWidth = BACK_BUTTON_WIDTH
        val backRect = Rect(innerX, footerTop, backWidth, BUTTON_HEIGHT)
        drawButton(context, font, backRect, "Back", selected = false, hovered = backRect.contains(mouseX, mouseY)) {
            onClose()
        }
        val countText = "${entries.size} boards"
        val countX = innerX + innerWidth - font.width(countText)
        context.text(font, countText, countX, footerTop + TEXT_Y_OFFSET, PixelControlColors.MUTED_TEXT, false)
    }

    private fun drawFilterRow(
        context: GuiGraphicsExtractor,
        font: net.minecraft.client.gui.Font,
        x: Int,
        y: Int,
        width: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        val intervalWidth = INTERVAL_BUTTON_WIDTH
        val categoryWidth = (width - intervalWidth - GAP).coerceAtLeast(MIN_CATEGORY_WIDTH)
        val categoryRect = Rect(x, y, categoryWidth, FILTER_HEIGHT)
        val intervalRect = Rect(x + categoryWidth + GAP, y, intervalWidth, FILTER_HEIGHT)

        val categoryLabel = "Category: $selectedCategory"
        drawButton(
            context,
            font,
            categoryRect,
            categoryLabel,
            selected = selectedCategory != ALL_CATEGORIES,
            hovered = categoryRect.contains(mouseX, mouseY),
        ) {
            cycleCategory()
        }
        val intervalLabel = "Interval: ${selectedInterval?.label ?: "All"}"
        drawButton(
            context,
            font,
            intervalRect,
            intervalLabel,
            selected = selectedInterval != null,
            hovered = intervalRect.contains(mouseX, mouseY),
        ) {
            cycleInterval()
        }
    }

    private fun drawEntries(
        context: GuiGraphicsExtractor,
        font: net.minecraft.client.gui.Font,
        entries: List<EliteLeaderboardInfo>,
        customSlugCandidate: String?,
        mouseX: Int,
        mouseY: Int,
    ) {
        var rowY = listViewport.y - scroll
        entries.forEach { info ->
            if (rowY + ROW_HEIGHT > listViewport.y && rowY < listViewport.y + listViewport.height) {
                val rect = Rect(listViewport.x, rowY, listViewport.width, ROW_HEIGHT)
                val hovered = listViewport.contains(mouseX, mouseY) && rect.contains(mouseX, mouseY)
                val isSelected = info.id == selectedBoardId
                val label = entryLabel(info, font, rect.width - ROW_TEXT_INSET * 2)
                drawRow(context, font, rect, label, info.category, isSelected, hovered) {
                    onSelect(info.id)
                    onClose()
                }
            }
            rowY += ROW_HEIGHT + ROW_GAP
        }
        if (customSlugCandidate != null && rowY + ROW_HEIGHT > listViewport.y && rowY < listViewport.y + listViewport.height) {
            val rect = Rect(listViewport.x, rowY, listViewport.width, ROW_HEIGHT)
            val hovered = listViewport.contains(mouseX, mouseY) && rect.contains(mouseX, mouseY)
            drawRow(context, font, rect, "Use custom id: $customSlugCandidate", "Custom", selected = false, hovered = hovered) {
                onSelect(customSlugCandidate)
                onClose()
            }
        }
        if (entries.isEmpty() && customSlugCandidate == null) {
            context.text(
                font,
                "No leaderboards match that filter.",
                listViewport.x + ROW_TEXT_INSET,
                listViewport.y + TEXT_Y_OFFSET,
                PixelControlColors.MUTED_TEXT,
                false,
            )
        }
    }

    private fun drawRow(
        context: GuiGraphicsExtractor,
        font: net.minecraft.client.gui.Font,
        bounds: Rect,
        primary: String,
        tag: String,
        selected: Boolean,
        hovered: Boolean,
        action: () -> Unit,
    ) {
        PixelButtonRenderer.draw(context, font, bounds, "", selected = selected, hovered = hovered, enabled = true)
        val tagWidth = font.width(tag)
        val maxPrimaryWidth = (bounds.width - ROW_TEXT_INSET * ROW_GAP_COLUMNS - tagWidth).coerceAtLeast(MIN_PRIMARY_TEXT_WIDTH)
        val elidedPrimary = font.elide(primary, maxPrimaryWidth)
        val textY = bounds.y + (bounds.height - font.lineHeight + 2) / 2
        context.text(font, elidedPrimary, bounds.x + ROW_TEXT_INSET, textY, PixelControlColors.TEXT, false)
        context.text(font, tag, bounds.x + bounds.width - ROW_TEXT_INSET - tagWidth, textY, PixelControlColors.MUTED_TEXT, false)
        clickTargets += PickerTarget(bounds, action)
    }

    private fun drawButton(
        context: GuiGraphicsExtractor,
        font: net.minecraft.client.gui.Font,
        bounds: Rect,
        label: String,
        selected: Boolean,
        hovered: Boolean,
        tone: PixelButtonTone = PixelButtonTone.NORMAL,
        action: () -> Unit,
    ) {
        PixelButtonRenderer.draw(context, font, bounds, label, selected = selected, hovered = hovered, enabled = true, tone = tone)
        clickTargets += PickerTarget(bounds, action)
    }

    private fun entryLabel(info: EliteLeaderboardInfo, font: net.minecraft.client.gui.Font, maxWidth: Int): String {
        val base = info.displayTitle()
        val tags = buildList {
            if (info.isProfileBoard) add(PROFILE_TAG)
            if (eliteBoardSignal(info) == EliteBoardSignal.NONE) add(SNAPSHOT_ONLY_TAG)
        }
        val full = if (tags.isEmpty()) base else "$base ${tags.joinToString(" ")}"
        return font.elide(full, maxWidth)
    }

    private fun filteredEntries(): List<EliteLeaderboardInfo> =
        EliteLeaderboardCatalog.entries().filter { info ->
            (selectedCategory == ALL_CATEGORIES || info.category == selectedCategory) &&
                (selectedInterval == null || info.intervalType == selectedInterval) &&
                info.matchesQuery(search.text)
        }

    private fun customSlugOrNull(entries: List<EliteLeaderboardInfo>): String? {
        val raw = search.text.trim()
        if (raw.isEmpty() || ' ' in raw) return null
        val normalized = normalizeEliteBoardId(raw)
        if (entries.any { it.id == normalized }) return null
        return normalized
    }

    private fun statusLabel(): String = when (EliteLeaderboardCatalog.status) {
        EliteCatalogStatus.LOADING -> "Loading catalog..."
        EliteCatalogStatus.FAILED -> "Catalog offline (using presets)"
        EliteCatalogStatus.IDLE, EliteCatalogStatus.LOADED -> ""
    }

    private fun cycleCategory() {
        val all = listOf(ALL_CATEGORIES) + EliteLeaderboardCatalog.categories()
        val nextIndex = (all.indexOf(selectedCategory) + 1) % all.size
        selectedCategory = all[nextIndex]
    }

    private fun cycleInterval() {
        val all = listOf<EliteIntervalType?>(null) + EliteIntervalType.entries
        val nextIndex = (all.indexOf(selectedInterval) + 1) % all.size
        selectedInterval = all[nextIndex]
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mouseX = click.x().toInt()
        val mouseY = click.y().toInt()
        if (!panel.contains(mouseX, mouseY)) {
            onClose()
            return true
        }
        if (click.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(click, doubled)
        if (searchBounds.contains(mouseX, mouseY)) {
            search.focused = true
            search.placeCursorAt(mouseX, searchBounds.x, searchBounds.width)
            return true
        }
        search.focused = false
        val target = clickTargets.lastOrNull { target ->
            target.bounds.contains(mouseX, mouseY) &&
                (!listViewport.contains(target.bounds.x, target.bounds.y) || listViewport.contains(mouseX, mouseY))
        } ?: return true
        SoundUtilities.playClickSound()
        target.action()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (!listViewport.contains(mouseX.toInt(), mouseY.toInt()) || scrollY == 0.0) return false
        scroll = (scroll - (scrollY * SCROLL_STEP).toInt()).coerceIn(0, (totalContentHeight - listViewport.height).coerceAtLeast(0))
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose()
            return true
        }
        if (search.focused && search.keyPressed(event) == InputHandlingResult.CONSUMED) return true
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (search.focused && event.isAllowedChatCharacter) {
            search.charTyped(event)
            return true
        }
        return super.charTyped(event)
    }

    override fun beforeEditorClose() = Unit

    private data class PickerTarget(
        val bounds: Rect,
        val action: () -> Unit,
    )

    companion object {
        private const val ALL_CATEGORIES = "All"
        private const val PROFILE_TAG = "[Profile]"
        private const val SNAPSHOT_ONLY_TAG = "[Snapshot only]"
        private const val MAX_SEARCH_LENGTH = 80
        private const val PREFERRED_WIDTH = 360
        private const val PREFERRED_HEIGHT = 270
        private const val MIN_PANEL_WIDTH = 220
        private const val MIN_PANEL_HEIGHT = 180
        private const val SCREEN_MARGIN = 16
        private const val INSET = 8
        private const val HEADER_HEIGHT = 22
        private const val SEARCH_HEIGHT = 18
        private const val FILTER_HEIGHT = 18
        private const val ROW_HEIGHT = 18
        private const val ROW_GAP = 2
        private const val GAP = 4
        private const val BUTTON_HEIGHT = 18
        private const val BACK_BUTTON_WIDTH = 64
        private const val INTERVAL_BUTTON_WIDTH = 110
        private const val MIN_CATEGORY_WIDTH = 100
        private const val ROW_TEXT_INSET = 6
        private const val ROW_GAP_COLUMNS = 3
        private const val MIN_PRIMARY_TEXT_WIDTH = 60
        private const val TEXT_Y_OFFSET = 5
        private const val SCROLL_STEP = 20
        private const val SCRIM_COLOR = 0x70000000

        fun open(parent: Screen?, currentBoardId: String, onSelect: (String) -> Unit) {
            MinecraftClient.setScreen(EliteLeaderboardPickerScreen(parent, currentBoardId, onSelect))
        }
    }
}
