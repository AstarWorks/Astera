package com.astarworks.astera.match.application.gui.lobby

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Wave 5lobby-content (ADR-0119) — queue mode list sub-menu.
 *
 * Opened by `lobby.queue.show` (NPC right-click via
 * `content/lobby-npcs/queue-npc.yaml` or compass tile via
 * [com.astarworks.astera.core.application.gui.lobby.MainMenuGuiSpec]).
 *
 * Phase 1 PoC: only the **Norma** queue is listed. `custom:*` and
 * `tournament:*` queues are scheduled to land alongside admin tooling that
 * configures them — the GUI is built declaratively so adding a new tile is
 * one more `slot(...)` call.
 *
 * Tile click emits `lobby.queue.join` with payload `{"modeKey": "norma"}`;
 * [com.astarworks.astera.match.application.intent.LobbyQueueIntentHandler]
 * resolves the payload and dispatches into
 * [com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker.enqueue].
 *
 * # Layout
 *
 * Single 9-slot chest row, Norma tile at slot 4 (centre). Slots 0..3 + 5..8
 * are intentionally left empty so the tile reads as the focal point — future
 * tiles take the symmetric pair-around-centre positions before reaching for
 * a 27-slot variant.
 */
public object QueueModeListGuiSpec {

    public val ID: GuiId = GuiId("queue-mode-list")

    public val SPEC: GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.queue-mode-list.title"),
        size = GuiSize.CHEST_9,
    ) {
        slot(
            SlotIndex(4),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-queue"),
                onLeftClick = GuiIntent(
                    key = "lobby.queue.join",
                    payload = mapOf("modeKey" to "norma"),
                ),
                tooltip = listOf(MessageKey("astera.gui.queue-mode-list.norma.tooltip")),
            ),
        )
    }
}
