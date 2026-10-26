package com.astarworks.astera.core.application.gui

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IGuiRegistry
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * `gui.back` - a context-aware Back button. Payload `gui` is where Back goes normally (e.g. `main-menu`);
 * the optional payload `in_match` is where it goes while the player is in a match (e.g. `match-menu`).
 * With no `in_match` target, Back closes the screen during a match (the lobby main menu is not reachable there).
 */
public class GuiBackIntentHandler(
    private val registry: IGuiRegistry,
    private val guiAdapter: IGuiAdapter,
    private val inMatch: (PlayerId) -> Boolean,
) {
    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        val target = if (inMatch(player.id)) intent.payload[PAYLOAD_IN_MATCH] else intent.payload[PAYLOAD_GUI]
        val spec = target?.let { runCatching { GuiId(it) }.getOrNull() }?.let { registry.find(it) }
        if (spec == null || !guiAdapter.open(player, spec)) guiAdapter.close(player)
    }

    public companion object {
        public const val INTENT: String = "gui.back"
        public const val PAYLOAD_GUI: String = "gui"
        public const val PAYLOAD_IN_MATCH: String = "in_match"
    }
}
