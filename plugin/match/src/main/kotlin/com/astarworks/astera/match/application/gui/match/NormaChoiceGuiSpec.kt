package com.astarworks.astera.match.application.gui.match

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
 * The Norma goal picker shown at match start and at every level-up: left tile = STAR goal, right tile = KILL goal.
 * Clicks emit `match.norma.choose` with `payload[goal] = star|kill`. Not choosing keeps the default (STAR).
 */
public object NormaChoiceGuiSpec {
    public val ID: GuiId = GuiId("norma-choice")

    public const val INTENT_CHOOSE: String = "match.norma.choose"
    public const val PAYLOAD_GOAL: String = "goal"

    private val ICON_STAR = ItemId("gui-tile-norma-star")
    private val ICON_KILL = ItemId("gui-tile-norma-kill")
    private val ICON_PANE = ItemId("gui-pane-dark")

    public fun build(level: Int, maxLevel: Int, starsGoal: Int, killsGoal: Int, starsCarried: Int, kills: Int): GuiSpec =
        GuiSpec.build(id = ID, title = MessageKey("astera.match.norma-choice.gui.title"), size = GuiSize.CHEST_9) {
            for (i in 0 until 9) slot(SlotIndex(i), GuiSlot.Border(ICON_PANE))
            val levelArgs = mapOf("level" to level.toString(), "max" to maxLevel.toString())
            slot(
                SlotIndex(2),
                GuiSlot.Static(
                    itemId = ICON_STAR,
                    onLeftClick = GuiIntent(INTENT_CHOOSE, mapOf(PAYLOAD_GOAL to "star")),
                    displayName = GuiText(MessageKey("astera.match.norma-choice.gui.star.name")),
                    lore = listOf(
                        GuiText(MessageKey("astera.match.norma-choice.gui.star.lore"), mapOf("goal" to starsGoal.toString())),
                        GuiText(
                            MessageKey("astera.match.norma-choice.gui.progress"),
                            mapOf("now" to starsCarried.coerceAtMost(starsGoal).toString(), "goal" to starsGoal.toString()),
                        ),
                        GuiText(MessageKey("astera.match.norma-choice.gui.level"), levelArgs),
                        GuiText(MessageKey("astera.match.norma-choice.gui.default")),
                    ),
                ),
            )
            slot(
                SlotIndex(6),
                GuiSlot.Static(
                    itemId = ICON_KILL,
                    onLeftClick = GuiIntent(INTENT_CHOOSE, mapOf(PAYLOAD_GOAL to "kill")),
                    displayName = GuiText(MessageKey("astera.match.norma-choice.gui.kill.name")),
                    lore = listOf(
                        GuiText(MessageKey("astera.match.norma-choice.gui.kill.lore"), mapOf("goal" to killsGoal.toString())),
                        GuiText(
                            MessageKey("astera.match.norma-choice.gui.progress"),
                            mapOf("now" to kills.coerceAtMost(killsGoal).toString(), "goal" to killsGoal.toString()),
                        ),
                        GuiText(MessageKey("astera.match.norma-choice.gui.level"), levelArgs),
                    ),
                ),
            )
        }
}
