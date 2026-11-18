package com.skysoft.features.misc

import com.skysoft.config.SkysoftConfigGui
import com.skysoft.data.hypixel.HypixelLocationState
import com.skysoft.data.skyblock.EliteLeaderboardAhead
import com.skysoft.data.skyblock.EliteLeaderboardCatalog
import com.skysoft.data.skyblock.EliteLeaderboardClient
import com.skysoft.data.skyblock.EliteLeaderboardInfo
import com.skysoft.data.skyblock.EliteLeaderboardSnapshot
import com.skysoft.data.skyblock.EliteScoreDataType
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
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToLong
import net.minecraft.client.Minecraft

object EliteLeaderboardOverlay {
    private val config get() = SkysoftConfigGui.config().gui.information.eliteLeaderboard
    private val decimalFormat = ThreadLocal.withInitial {
        DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US))
    }

    private const val ROW_SPACING = 2
    private const val PREVIEW_LONG_AMOUNT = 4_038_909.0
    private const val PREVIEW_LONG_AHEAD_AMOUNT = 4_057_109.0
    private const val PREVIEW_DECIMAL_AMOUNT = 37_772.37
    private const val PREVIEW_DECIMAL_AHEAD_AMOUNT = 37_854.83
    private const val PREVIEW_MIN_AMOUNT = 100.0
    private const val PREVIEW_RANK = 636
    private const val PREVIEW_AHEAD_RANK = 635

    fun register() {
        registerRenderableHud(
            id = "elite_leaderboard",
            label = "Elite Leaderboard",
            position = { config.position },
            visible = TabDataOverlays::canRender,
            hasEditorBackground = { !config.details.background },
            renderable = ::currentRenderable,
        )
    }

    private fun currentRenderable(): GuiRenderable? {
        val minecraft = Minecraft.getInstance()
        if (!config.enabled || MinecraftClient.isGuiHidden(minecraft)) return null
        val boardInfo = EliteLeaderboardCatalog.info(EliteLeaderboardClient.activeBoardId())
        val inEditor = MinecraftClient.screen(minecraft) is SkysoftHudEditor.EditorScreen
        if (!inEditor && !HypixelLocationState.inSkyBlock) return null
        val snapshot = EliteLeaderboardClient.snapshotFor(boardInfo.id)
            ?: if (inEditor) previewSnapshot(boardInfo) else return null
        return buildRenderable(boardInfo, snapshot)
    }

    private fun buildRenderable(
        boardInfo: EliteLeaderboardInfo,
        snapshot: EliteLeaderboardSnapshot,
    ): GuiRenderable {
        val details = config.details
        val labelColor = details.labelColor.get().toColor().rgb
        val numberColor = details.numberColor.get().toColor().rgb
        val behindColor = details.behindColor.get().toColor().rgb
        val nameColor = details.nameColor.get().toColor().rgb

        val rows = buildList {
            add(firstLine(boardInfo, snapshot, labelColor, numberColor, behindColor, nameColor))
            // The "ahead" row only makes sense once ranked. For an unranked player Elite returns the
            // board's last entry rather than the neighbour above, so showing it would report a gap
            // against an unrelated player.
            if (snapshot.isRanked) {
                snapshot.ahead?.let { ahead ->
                    add(secondLine(boardInfo.scoreDataType, snapshot.amount, ahead, numberColor, behindColor, nameColor))
                }
            }
        }
        return verticalLayout(rows, spacing = ROW_SPACING).withOverlayPanel(details.background)
    }

    private fun firstLine(
        boardInfo: EliteLeaderboardInfo,
        snapshot: EliteLeaderboardSnapshot,
        labelColor: Int,
        numberColor: Int,
        behindColor: Int,
        nameColor: Int,
    ): GuiRenderable {
        val labelSegment = StringRenderable("${boardInfo.shortTitle}: ", color = labelColor)
        if (snapshot.isRanked) return rankedLine(boardInfo, snapshot, labelSegment, numberColor, behindColor, nameColor)
        return unrankedLine(boardInfo, snapshot, labelSegment, numberColor, behindColor)
    }

    private fun rankedLine(
        boardInfo: EliteLeaderboardInfo,
        snapshot: EliteLeaderboardSnapshot,
        labelSegment: GuiRenderable,
        numberColor: Int,
        behindColor: Int,
        nameColor: Int,
    ): GuiRenderable {
        val formattedAmount = formatScore(snapshot.amount, boardInfo.scoreDataType)
        return horizontalLayout(
            listOf(
                labelSegment,
                StringRenderable(formattedAmount, color = numberColor),
                StringRenderable(" [", color = behindColor),
                StringRenderable("#${snapshot.rank.addSeparators()}", color = nameColor),
                StringRenderable("]", color = behindColor),
            ),
        )
    }

    /**
     * Elite reports `amount: 0` and `rank: -1` for a player below a board's minimum score, so the
     * board's real total is genuinely unknown. Show only what is actually known: that the player is
     * unranked, the score needed to qualify, and any progress made locally this session.
     */
    private fun unrankedLine(
        boardInfo: EliteLeaderboardInfo,
        snapshot: EliteLeaderboardSnapshot,
        labelSegment: GuiRenderable,
        numberColor: Int,
        behindColor: Int,
    ): GuiRenderable {
        val segments = mutableListOf(labelSegment)
        if (snapshot.amount > 0.0) {
            val localGain = formatScore(snapshot.amount, boardInfo.scoreDataType)
            segments += StringRenderable("+$localGain", color = numberColor)
            segments += StringRenderable(" local", color = behindColor)
        } else {
            segments += StringRenderable("unranked", color = behindColor)
        }
        if (snapshot.minAmount > 0.0) {
            val minimum = formatScore(snapshot.minAmount, boardInfo.scoreDataType)
            segments += StringRenderable(" (min $minimum)", color = behindColor)
        }
        return horizontalLayout(segments)
    }

    private fun secondLine(
        scoreDataType: EliteScoreDataType,
        amount: Double,
        ahead: EliteLeaderboardAhead,
        numberColor: Int,
        behindColor: Int,
        nameColor: Int,
    ): GuiRenderable {
        val gap = (ahead.amount - amount).coerceAtLeast(0.0)
        return horizontalLayout(
            listOf(
                StringRenderable(formatScore(gap, scoreDataType), color = numberColor),
                StringRenderable(" behind ", color = behindColor),
                StringRenderable(ahead.ign, color = nameColor),
            ),
        )
    }

    private fun formatScore(value: Double, scoreDataType: EliteScoreDataType): String = when (scoreDataType) {
        EliteScoreDataType.LONG -> value.roundToLong().addSeparators()
        EliteScoreDataType.DECIMAL -> decimalFormat.get().format(value)
    }

    private fun previewSnapshot(boardInfo: EliteLeaderboardInfo): EliteLeaderboardSnapshot {
        val useDecimal = boardInfo.scoreDataType == EliteScoreDataType.DECIMAL &&
            boardInfo.category != "Slayers"
        val previewAmount = if (useDecimal) PREVIEW_DECIMAL_AMOUNT else PREVIEW_LONG_AMOUNT
        val previewAheadAmount = if (useDecimal) PREVIEW_DECIMAL_AHEAD_AMOUNT else PREVIEW_LONG_AHEAD_AMOUNT
        return EliteLeaderboardSnapshot(
            boardId = boardInfo.id,
            rank = PREVIEW_RANK,
            amount = previewAmount,
            minAmount = PREVIEW_MIN_AMOUNT,
            upcomingRank = PREVIEW_AHEAD_RANK,
            upcomingPlayers = listOf(
                EliteLeaderboardAhead(
                    ign = "CatgirlArch",
                    amount = previewAheadAmount,
                    rank = PREVIEW_AHEAD_RANK,
                ),
            ),
        )
    }
}
