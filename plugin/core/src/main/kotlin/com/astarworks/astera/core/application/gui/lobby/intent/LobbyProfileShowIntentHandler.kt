package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.gui.lobby.ProfileCardGuiSpec
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave 5lobby-content (ADR-0119) — opens the player profile card GUI in
 * response to the `lobby.profile.show` intent.
 *
 * Phase 1 PoC: the profile card is a declarative read-only GUI built from
 * Wave 5flex `PowerIndex` + Wave 5e `IPlayerProfileService` data. Both data
 * sources live in plugins this handler does NOT directly depend on (the
 * resolution flows through Wave 5flex bindings registered in
 * `DefaultGuiBindingResolver`); the spec itself ([ProfileCardGuiSpec.SPEC])
 * is intentionally static so this handler doesn't need to thread any service
 * references through — the renderer pulls live values at GUI-build time.
 *
 * Future waves can swap in a dynamic spec composer (per-mode stats, equipped
 * loadout preview, recent matches) without changing this handler — the GUI
 * spec is a value, the handler just opens it.
 */
public class LobbyProfileShowIntentHandler(
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_SHOW) {
            log.warn(
                "Unknown lobby.profile intent '{}' from player {} — payload={}",
                intent.key, player.id, intent.payload,
            )
            return
        }
        val mcPlayer = mcServer.findPlayer(player.id) ?: run {
            log.debug("Player {} disappeared before profile card open", player.id)
            return
        }
        if (!guiAdapter.open(mcPlayer, ProfileCardGuiSpec.SPEC)) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_OPEN_REJECTED, emptyMap()),
            )
        }
    }

    public companion object {
        public const val INTENT_PREFIX: String = "lobby.profile."
        public const val INTENT_SHOW: String = "lobby.profile.show"

        private val MSG_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
