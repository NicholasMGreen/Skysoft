package com.skysoft.config

import com.google.gson.annotations.Expose
import com.skysoft.config.core.HudPosition
import com.skysoft.data.skyblock.SkyBlockSlayerType
import com.skysoft.utils.BrowserUtilities
import io.github.notenoughupdates.moulconfig.ChromaColour
import io.github.notenoughupdates.moulconfig.annotations.Accordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import io.github.notenoughupdates.moulconfig.observer.Property

class SlayerLeaderboardConfig {
    @JvmField
    @field:Expose
    @field:ConfigOption(
        name = "Enabled",
        desc = "Show your slayer XP rank and the player ahead from Elite SkyBlock (https://eliteskyblock.com/).",
    )
    @field:MainFeatureToggle
    @field:ConfigEditorBoolean
    var enabled = false

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Settings", desc = "Choose which slayer leaderboard is shown.")
    @field:Accordion
    val settings = SlayerLeaderboardSettingsConfig()

    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Details", desc = "Customize the leaderboard overlay appearance.")
    @field:Accordion
    val details = SlayerLeaderboardDetailsConfig()

    @JvmField
    @field:Expose
    val position = HudPosition(DEFAULT_X, DEFAULT_Y, centerY = false).rememberDefault()

    private companion object {
        const val DEFAULT_X = 8
        const val DEFAULT_Y = 130
    }
}

class SlayerLeaderboardSettingsConfig {
    @JvmField
    @field:Expose
    @field:ConfigOption(name = "Board", desc = "Choose which Elite SkyBlock slayer leaderboard to display.")
    @field:ConfigEditorDropdown
    var board = EliteSlayerBoard.BLAZE

    @JvmField
    @field:Expose
    @field:ConfigOption(
        name = "Follow Active Quest",
        desc = "Switch to the leaderboard matching your active slayer quest on the scoreboard.",
    )
    @field:ConfigEditorBoolean
    var followActiveQuest = false

    @JvmField
    @field:Expose
    @field:ConfigOption(
        name = "Track Local Slayer XP",
        desc = "Add Slayer XP locally on each quest completion between 10-minute Elite SkyBlock fetches. " +
            "Caches 20 upcoming players locally, refreshes 20 ahead at your local XP when Elite lags behind, " +
            "and updates your rank and target as you pass them.",
    )
    @field:ConfigEditorBoolean
    var trackLocalXp = true

    @JvmField
    @field:ConfigOption(
        name = "Elite Leaderboard",
        desc = "Powered by Elite SkyBlock. Open the selected leaderboard at https://eliteskyblock.com/.",
    )
    @field:ConfigEditorButton(buttonText = "Open")
    val openLeaderboard = Runnable { BrowserUtilities.tryOpen(board.url()) }
}

class SlayerLeaderboardDetailsConfig {
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
    @field:ConfigOption(name = "Number Color", desc = "Color used for XP and gap numbers.")
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

enum class EliteSlayerBoard(
    val id: String,
    val shortLabel: String,
    val slayerType: SkyBlockSlayerType?,
) {
    ZOMBIE("zombie-slayer", "Zombie Slayer XP", SkyBlockSlayerType.ZOMBIE),
    SPIDER("spider-slayer", "Spider Slayer XP", SkyBlockSlayerType.SPIDER),
    WOLF("wolf-slayer", "Wolf Slayer XP", SkyBlockSlayerType.WOLF),
    ENDERMAN("enderman-slayer", "Enderman Slayer XP", SkyBlockSlayerType.ENDERMAN),
    BLAZE("blaze-slayer", "Blaze Slayer XP", SkyBlockSlayerType.BLAZE),
    VAMPIRE("vampire-slayer", "Vampire Slayer XP", SkyBlockSlayerType.VAMPIRE),
    TOTAL("slayer-xp", "Slayer XP", null),
    ;

    fun url(): String = "$ELITE_LEADERBOARD_BASE_URL/$id"

    override fun toString(): String = shortLabel

    companion object {
        private const val ELITE_LEADERBOARD_BASE_URL = "https://eliteskyblock.com/leaderboard"

        fun fromSlayerType(slayerType: SkyBlockSlayerType): EliteSlayerBoard =
            entries.firstOrNull { it.slayerType == slayerType } ?: BLAZE
    }
}
