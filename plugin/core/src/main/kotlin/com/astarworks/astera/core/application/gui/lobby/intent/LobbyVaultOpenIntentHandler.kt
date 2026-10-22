package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.command.vaultErrorKey
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.vault.AccessPersonalVaultUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import org.slf4j.LoggerFactory

/**
 * `lobby.vault.open` (customize menu) — opens the player's personal vault the
 * same way `/astera vault` and the ender chest block do, and reports a refusal
 * (e.g. during a match) in chat.
 */
public class LobbyVaultOpenIntentHandler(
    private val access: () -> AccessPersonalVaultUseCase,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_OPEN) {
            log.warn("Unknown lobby.vault intent '{}' from player {}", intent.key, player.id)
            return
        }
        val result = access().open(player.id)
        if (result is Result.Failure) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, vaultErrorKey(result.error), emptyMap()),
            )
        }
    }

    public companion object {
        public const val INTENT_OPEN: String = "lobby.vault.open"
    }
}
