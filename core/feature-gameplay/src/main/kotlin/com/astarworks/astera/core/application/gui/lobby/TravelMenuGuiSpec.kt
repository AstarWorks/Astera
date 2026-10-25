package com.astarworks.astera.core.application.gui.lobby

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Wave 5lobby-content (ADR-0119) — travel sub-menu.
 *
 * Opened by `lobby.travel.show` from the main-menu compass tile or the
 * `travel-npc` NPC right-click. Four tiles in a single 9-slot row (UI-01: the Travel hub also owns Training):
 *
 *  - Slot 1: PvE → `lobby.travel.pve` → [com.astarworks.astera.core.application.usecase.travel.TravelToPveWorldUseCase]
 *  - Slot 3: Survival → `lobby.travel.survival` → [com.astarworks.astera.core.application.usecase.travel.TravelToSurvivalIslandUseCase]
 *  - Slot 5: Training -> `lobby.training.travel`
 *  - Slot 7: Lobby → `lobby.travel.lobby` → [com.astarworks.astera.core.application.usecase.travel.ReturnToLobbyUseCase]
 *
 * The tile items use placeholder ids (`gui-tile-travel-pve`, etc.); the
 * actual visuals come from the InMemoryItemRegistry default + a future
 * texture pack overlay.
 */
public object TravelMenuGuiSpec {

    public val ID: GuiId = GuiId("travel-menu")

    public val SPEC: GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.travel-menu.title"),
        size = GuiSize.CHEST_9,
    ) {
        slot(
            SlotIndex(1),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-travel-pve"),
                onLeftClick = GuiIntent("lobby.travel.pve"),
                tooltip = listOf(MessageKey("astera.gui.travel-menu.pve.tooltip")),
            ),
        )
        slot(
            SlotIndex(3),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-travel-survival"),
                onLeftClick = GuiIntent("lobby.travel.survival"),
                tooltip = listOf(MessageKey("astera.gui.travel-menu.survival.tooltip")),
            ),
        )
        slot(
            SlotIndex(5),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-training"),
                onLeftClick = GuiIntent("lobby.training.travel"),
                tooltip = listOf(MessageKey("astera.gui.main-menu.training.tooltip")),
            ),
        )
        slot(
            SlotIndex(7),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-travel-lobby"),
                onLeftClick = GuiIntent("lobby.travel.lobby"),
                tooltip = listOf(MessageKey("astera.gui.travel-menu.lobby.tooltip")),
            ),
        )
    }
}
