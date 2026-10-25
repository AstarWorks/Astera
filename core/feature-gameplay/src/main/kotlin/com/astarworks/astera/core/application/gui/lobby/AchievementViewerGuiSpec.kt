package com.astarworks.astera.core.application.gui.lobby

import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Wave M 5polish-quickwin (ADR-0130 §B) — achievement viewer.
 *
 * `CHEST_54` layout (6 rows × 9 cols). Slots 0..44 (5 rows) host reactive
 * achievement tiles bound to `player.achievement.list.<n>` — the
 * `PlayerAchievementListGuiBindingResolver` in `plugin/stats` (this wave)
 * fills tiles with the player's catalog projection:
 *
 * - unlocked → `indicator-achievement-unlocked` (gold/green visual via
 *   texture pack)
 * - locked → `indicator-achievement-locked` (grayed-out)
 *
 * Bottom row:
 *  - Slot 49 (centre): close button (`gui.close`).
 *
 * # Cross-plugin classloader rule
 *
 * The spec lives in `plugin/core` so other plugins (Stats binding resolver,
 * navigation from `ProfileCardGuiSpec`) can reference [ID] / [SPEC] without
 * core depending on stats. The actual tile data flows through the
 * IGuiBindingResolver composite stack (Wave M §A).
 *
 * # Why a fixed 45-tile budget for Phase 1
 *
 * RTM total achievement target is < 45 for Phase 1 PoC. Pagination
 * (slots → page-index payload) lands once the catalog grows past one
 * screen — out of scope here.
 */
public object AchievementViewerGuiSpec {

    public val ID: GuiId = GuiId("achievement-viewer")

    public const val MAX_VISIBLE_TILES: Int = 45

    public val SPEC: GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.achievement-viewer.title"),
        size = GuiSize.CHEST_54,
    ) {
        for (i in 0 until MAX_VISIBLE_TILES) {
            slot(
                SlotIndex(i),
                GuiSlot.Reactive(
                    binding = BindingKey("player.achievement.list.$i"),
                    tooltip = listOf(MessageKey("astera.gui.achievement-viewer.tile.tooltip")),
                ),
            )
        }
        slot(
            SlotIndex(49),
            GuiSlot.Static(
                itemId = ItemId("gui-pane-red"),
                onLeftClick = GuiIntent("gui.close"),
                tooltip = listOf(MessageKey("astera.gui.button.close")),
            ),
        )
    }
}
