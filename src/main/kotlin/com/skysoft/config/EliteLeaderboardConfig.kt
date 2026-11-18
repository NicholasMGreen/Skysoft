package com.skysoft.config

import com.google.gson.annotations.Expose
import com.skysoft.config.core.HudPosition
import com.skysoft.data.skyblock.DEFAULT_ELITE_BOARD_ID
import com.skysoft.data.skyblock.EliteLeaderboardCatalog
import com.skysoft.utils.BrowserUtilities
import com.skysoft.utils.MinecraftClient
import io.github.notenoughupdates.moulconfig.ChromaColour
import io.github.notenoughupdates.moulconfig.annotations.Accordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import io.github.notenoughupdates.moulconfig.observer.Property
import io.github.notenoughupdates.moulconfig.processor.ProcessedOption

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigEditorEliteBoard

class EliteLeaderboardConfig {
    @JvmField
    @field:Expose
    @field:ConfigOption(
        name = "Enabled",
        desc = "Show your rank and the player ahead on any Elite SkyBlock leaderboard (https://eliteskyblock.com/).",
    )
    @field:MainFeatureToggle
    @field:ConfigEditorBoolean
    var enabled = false

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Settings", desc = "Choose which Elite SkyBlock leaderboard is shown.")
    @field:Accordion
    val settings = EliteLeaderboardSettingsConfig()

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Details", desc = "Customize the leaderboard overlay appearance.")
    @field:Accordion
    val details = EliteLeaderboardDetailsConfig()

    @JvmField
    @field:Expose
    val position = HudPosition(DEFAULT_X, DEFAULT_Y, centerY = false).rememberDefault()

    private companion object {
        const val DEFAULT_X = 8
        const val DEFAULT_Y = 130
    }
}

class EliteLeaderboardSettingsConfig {
    @JvmField
    @field:Expose
    @field:ConfigOption(
        name = "Leaderboard",
        desc = "Choose which Elite SkyBlock leaderboard to display (e.g. Pest Kills, Blaze Slayer XP, Farming Weight).",
    )
    @field:ConfigEditorEliteBoard
    var boardId = DEFAULT_ELITE_BOARD_ID

    @JvmField
    @field:Expose
    @field:ConfigOption(
        name = "Follow Active Slayer Quest",
        desc = "Temporarily switch to the matching slayer leaderboard while a slayer quest is on your scoreboard.",
    )
    @field:ConfigEditorBoolean
    var followActiveSlayerQuest = false

    @JvmField
    @field:Expose
    @field:ConfigOption(
        name = "Track Local Progress",
        desc = "Advance the board locally between 10-minute Elite SkyBlock fetches using in-client signals " +
            "(mob kills, Garden pests, collections, skill XP, slayer XP). Boards with no in-client signal, " +
            "such as networth or dungeon XP, show the Elite snapshot only.",
    )
    @field:ConfigEditorBoolean
    var trackLocalProgress = true

    @JvmField
    @field:ConfigOption(
        name = "Elite Leaderboard",
        desc = "Powered by Elite SkyBlock. Open the selected leaderboard at https://eliteskyblock.com/.",
    )
    @field:ConfigEditorButton(buttonText = "Open")
    val openLeaderboard = Runnable { BrowserUtilities.tryOpen(EliteLeaderboardCatalog.info(boardId).url()) }
}

class EliteLeaderboardDetailsConfig {
    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Background", desc = "Draw a background behind the overlay.")
    @field:ConfigEditorBoolean
    var background = false

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Label Color", desc = "Color used for the leaderboard label.")
    @field:ConfigEditorColour
    val labelColor: Property<ChromaColour> = Property.of(ChromaColour.fromRGB(255, 170, 0, 0, 255))

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Number Color", desc = "Color used for score and gap numbers.")
    @field:ConfigEditorColour
    val numberColor: Property<ChromaColour> = Property.of(ChromaColour.fromRGB(255, 255, 85, 0, 255))

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Secondary Color", desc = "Color used for brackets, 'behind', and unranked text.")
    @field:ConfigEditorColour
    val behindColor: Property<ChromaColour> = Property.of(ChromaColour.fromRGB(170, 170, 170, 0, 255))

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Highlight Color", desc = "Color used for your rank and the player ahead.")
    @field:ConfigEditorColour
    val nameColor: Property<ChromaColour> = Property.of(ChromaColour.fromRGB(85, 255, 255, 0, 255))
}

internal class EliteLeaderboardBoardEditor(option: ProcessedOption) :
    SkysoftStatusButtonEditor(option, "leaderboard elite board pest slayer farming skill collection bestiary") {
    init {
        EliteLeaderboardCatalog.ensureLoaded()
    }

    private fun currentBoardId(): String =
        (option.get() as? String).orEmpty().ifBlank { DEFAULT_ELITE_BOARD_ID }

    override fun statusText(): String =
        EliteLeaderboardCatalog.info(currentBoardId()).displayTitle()

    override fun statusColor(): Int = STATUS_COLOR

    override fun buttonText(): String = "Browse"

    override fun click() {
        val current = currentBoardId()
        EliteLeaderboardPickerScreen.open(MinecraftClient.screen(), current) { selectedBoardId ->
            option.set(selectedBoardId)
            option.explicitNotifyChange()
        }
    }

    private companion object {
        val STATUS_COLOR = 0xFFFFAA00.toInt()
    }
}
