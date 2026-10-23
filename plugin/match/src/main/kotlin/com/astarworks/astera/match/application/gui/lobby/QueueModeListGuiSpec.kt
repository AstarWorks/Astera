package com.astarworks.astera.match.application.gui.lobby

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * ADR-0248 — the mode picker, opened by the Play gate NPC, the main-menu Play tile, the compass `Q` key and
 * the crafting-grid Play button (all emit `lobby.queue.show`).
 *
 * One column per queue lane (up to nine), built fresh on every open so the queued counts are current:
 *
 *  - top row: the lane tile. Name, one-line description, team size, minimum players, how many are queued now.
 *    Click = **Queue** with real players (`lobby.queue.join`).
 *  - middle row: the **Play vs bots** tile of the same lane (`lobby.queue.bots`).
 *  - bottom row: "Leave queue" in the centre while the viewer is queued.
 */
public object QueueModeListGuiSpec {

    public val ID: GuiId = GuiId("queue-mode-list")

    public const val MAX_LANES: Int = 9
    public const val PAYLOAD_LANE: String = "lane"

    private val ICON_LANE = ItemId("gui-tile-queue")
    private val ICON_BOTS = ItemId("gui-tile-play-bots")
    private val ICON_LEAVE = ItemId("gui-tile-leave-queue")
    private val ICON_PANE = ItemId("gui-pane-dark")

    /** Everything the picker shows about one lane. */
    public data class LaneView(
        val laneKey: String,
        /** Lane title (game mode name). */
        val name: GuiText,
        /** One-line description. */
        val description: GuiText,
        val laneLabel: String,
        val teams: Int,
        val playersPerTeam: Int,
        val minPlayers: Int,
        val queued: Int,
        /** Bots a click on "Play vs bots" would ask for. */
        val botCount: Int,
        /** The viewer is queued in this very lane. */
        val mine: Boolean,
    )

    public fun build(lanes: List<LaneView>, queuedLaneLabel: String?): GuiSpec =
        GuiSpec.build(id = ID, title = MessageKey("astera.play.picker.title"), size = GuiSize.CHEST_27) {
            for (column in 0 until 9) slot(SlotIndex(18 + column), GuiSlot.Border(ICON_PANE))
            lanes.take(MAX_LANES).forEachIndexed { column, lane ->
                val payload = mapOf(PAYLOAD_LANE to lane.laneKey)
                val sizeArgs = mapOf(
                    "teams" to lane.teams.toString(),
                    "size" to lane.playersPerTeam.toString(),
                    "min" to lane.minPlayers.toString(),
                )
                slot(
                    SlotIndex(column),
                    GuiSlot.Static(
                        itemId = ICON_LANE,
                        onLeftClick = GuiIntent("lobby.queue.join", payload),
                        displayName = lane.name,
                        count = lane.queued.coerceIn(1, GuiSlot.Static.MAX_STACK),
                        lore = listOf(
                            lane.description,
                            GuiText(MessageKey("astera.play.picker.lane.size"), sizeArgs),
                            GuiText(MessageKey("astera.play.picker.lane.queued"), mapOf("queued" to lane.queued.toString())),
                            GuiText(MessageKey(if (lane.mine) "astera.play.picker.lane.mine" else "astera.play.picker.lane.hint")),
                        ),
                    ),
                )
                slot(
                    SlotIndex(9 + column),
                    GuiSlot.Static(
                        itemId = ICON_BOTS,
                        onLeftClick = GuiIntent("lobby.queue.bots", payload),
                        displayName = GuiText(MessageKey("astera.play.picker.bots.name")),
                        lore = listOf(
                            GuiText(MessageKey("astera.play.picker.bots.lane"), mapOf("lane" to lane.laneLabel)),
                            GuiText(MessageKey("astera.play.picker.bots.line"), mapOf("count" to lane.botCount.toString())),
                            GuiText(MessageKey("astera.play.picker.bots.hint")),
                        ),
                    ),
                )
            }
            if (queuedLaneLabel != null) {
                slot(
                    SlotIndex(22),
                    GuiSlot.Static(
                        itemId = ICON_LEAVE,
                        onLeftClick = GuiIntent("lobby.queue.leave"),
                        displayName = GuiText(MessageKey("astera.play.picker.leave.name")),
                        lore = listOf(GuiText(MessageKey("astera.play.picker.leave.lane"), mapOf("lane" to queuedLaneLabel))),
                    ),
                )
            }
        }
}
