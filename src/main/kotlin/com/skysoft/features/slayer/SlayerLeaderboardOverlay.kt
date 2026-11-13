package com.skysoft.features.slayer

import com.skysoft.config.EliteSlayerBoard
import com.skysoft.config.SkysoftConfigGui
import com.skysoft.data.hypixel.HypixelLocationState
import com.skysoft.data.skyblock.EliteLeaderboardAhead
import com.skysoft.data.skyblock.EliteLeaderboardClient
import com.skysoft.data.skyblock.EliteLeaderboardSnapshot
import com.skysoft.data.skyblock.SlayerQuestState
import com.skysoft.gui.SkysoftHudEditor
import com.skysoft.gui.TabDataOverlays
import com.skysoft.gui.registerRenderableHud
import com.skysoft.utils.ColorUtilities.toColor
import com.skysoft.utils.MinecraftClient
import com.skysoft.utils.NumberUtilities.addSeparators
import com.skysoft.utils.renderables.GuiRenderable
import com.skysoft.utils.renderables.container.horizontalLayout
import com.skysoft.utils.renderables.container.verticalLayout
import com.skysoft.utils.renderables.decorators.withOverlayPanel
import com.skysoft.utils.renderables.primitives.StringRenderable
import kotlin.math.roundToLong
import net.minecraft.client.Minecraft

object SlayerLeaderboardOverlay {
    private val config get() = SkysoftConfigGui.config().slayer.leaderboard
    private const val ROW_SPACING = 2
    private const val PREVIEW_AMOUNT = 4_038_909.0
    private const val PREVIEW_MIN_AMOUNT = 1_500.0
    private const val PREVIEW_RANK = 636
    private const val PREVIEW_AHEAD_AMOUNT = 4_057_109.0
    private const val PREVIEW_AHEAD_RANK = 635

    fun register() {
        registerRenderableHud(
            id = "slayer_leaderboard",
            label = "Slayer Leaderboard",
            position = { config.position },
            visible = TabDataOverlays::canRender,
            hasEditorBackground = { !config.details.background },
            renderable = ::currentRenderable,
        )
    }

    private fun currentRenderable(): GuiRenderable? {
        val minecraft = Minecraft.getInstance()
        if (!config.enabled || MinecraftClient.isGuiHidden(minecraft)) return null
        val board = activeBoard()
        val inEditor = MinecraftClient.screen(minecraft) is SkysoftHudEditor.EditorScreen
        if (!inEditor && !HypixelLocationState.inSkyBlock) return null
        val snapshot = EliteLeaderboardClient.snapshotFor(board)
            ?: if (inEditor) previewSnapshot(board) else return null
        return buildRenderable(board, snapshot)
    }

    private fun activeBoard(): EliteSlayerBoard {
        if (config.settings.followActiveQuest) {
            SlayerQuestState.scoreboardSlayerType?.let { return EliteSlayerBoard.fromSlayerType(it) }
        }
        return config.settings.board
    }

    private fun buildRenderable(board: EliteSlayerBoard, snapshot: EliteLeaderboardSnapshot): GuiRenderable {
        val details = config.details
        val labelColor = details.labelColor.get().toColor().rgb
        val numberColor = details.numberColor.get().toColor().rgb
        val behindColor = details.behindColor.get().toColor().rgb
        val nameColor = details.nameColor.get().toColor().rgb

        val rows = buildList {
            add(firstLine(board, snapshot, labelColor, numberColor, behindColor, nameColor))
            if (snapshot.isRanked) {
                snapshot.ahead?.let { ahead ->
                    add(secondLine(snapshot.amount, ahead, numberColor, behindColor, nameColor))
                }
            }
        }
        return verticalLayout(rows, spacing = ROW_SPACING).withOverlayPanel(details.background)
    }

    private fun firstLine(
        board: EliteSlayerBoard,
        snapshot: EliteLeaderboardSnapshot,
        labelColor: Int,
        numberColor: Int,
        behindColor: Int,
        nameColor: Int,
    ): GuiRenderable {
        val labelSegment = StringRenderable("${board.shortLabel}: ", color = labelColor)
        if (snapshot.amount < snapshot.minAmount) {
            return horizontalLayout(listOf(labelSegment, StringRenderable("Not ranked", color = behindColor)))
        }
        val formattedAmount = snapshot.amount.roundToLong().addSeparators()
        val segments = buildList {
            add(labelSegment)
            add(StringRenderable(formattedAmount, color = numberColor))
            if (snapshot.rank > 0) {
                add(StringRenderable(" [", color = behindColor))
                add(StringRenderable("#${snapshot.rank.addSeparators()}", color = nameColor))
                add(StringRenderable("]", color = behindColor))
            }
        }
        return horizontalLayout(segments)
    }

    private fun secondLine(
        amount: Double,
        ahead: EliteLeaderboardAhead,
        numberColor: Int,
        behindColor: Int,
        nameColor: Int,
    ): GuiRenderable {
        val gap = (ahead.amount.roundToLong() - amount.roundToLong()).coerceAtLeast(0L)
        return horizontalLayout(
            listOf(
                StringRenderable(gap.addSeparators(), color = numberColor),
                StringRenderable(" behind ", color = behindColor),
                StringRenderable(ahead.ign, color = nameColor),
            ),
        )
    }

    private fun previewSnapshot(board: EliteSlayerBoard): EliteLeaderboardSnapshot = EliteLeaderboardSnapshot(
        boardId = board.id,
        rank = PREVIEW_RANK,
        amount = PREVIEW_AMOUNT,
        minAmount = PREVIEW_MIN_AMOUNT,
        ahead = EliteLeaderboardAhead(
            ign = "CatgirlArch",
            amount = PREVIEW_AHEAD_AMOUNT,
            rank = PREVIEW_AHEAD_RANK,
        ),
    )
}
