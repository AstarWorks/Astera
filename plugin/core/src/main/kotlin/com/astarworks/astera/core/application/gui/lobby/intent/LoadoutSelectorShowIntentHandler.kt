package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.gui.lobby.LoadoutSelectorGuiSpec
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave 5game-complete (ADR-0121 §B) — opens the 9-slot
 * [LoadoutSelectorGuiSpec] in response to the
 * `loadout.selector.show` intent.
 *
 * Mirrors [LobbyCustomizeShowIntentHandler] — purely a navigation
 * helper. The slot-click intents (`loadout.selector.activate` /
 * `.rename` / `.reset` / `.save`) are handled by the items plugin
 * once it lands the matching handlers; Phase 1 PoC leaves them as
 * no-ops because the loadout taxonomy already round-trips through
 * `ILoadoutRepository`.
 */
public class LoadoutSelectorShowIntentHandler(
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != LoadoutSelectorGuiSpec.INTENT_SHOW) {
            log.warn(
                "Unknown loadout.selector intent '{}' from player {} — payload={}",
                intent.key, player.id, intent.payload,
            )
            return
        }
        val mcPlayer = mcServer.findPlayer(player.id) ?: run {
            log.debug("Player {} disappeared before loadout selector open", player.id)
            return
        }
        if (!guiAdapter.open(mcPlayer, LoadoutSelectorGuiSpec.SPEC)) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_OPEN_REJECTED, emptyMap()),
            )
        }
    }

    public companion object {
        public const val INTENT_PREFIX: String = LoadoutSelectorGuiSpec.INTENT_PREFIX
        public const val INTENT_SHOW: String = LoadoutSelectorGuiSpec.INTENT_SHOW

        private val MSG_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
