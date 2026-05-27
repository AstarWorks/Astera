package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.usecase.settings.SetControlSchemeUseCase
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.ControlScheme
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — cycle the player's [ControlScheme].
 *
 * Triggered by the `settings.controls.cycle` intent. Two-element cycle today
 * ([ControlScheme.RightClick] ↔ [ControlScheme.Button]); adding a third
 * variant means extending the `when` branch + the cycle list.
 *
 * Wave 5e already shipped [SetControlSchemeUseCase]; this handler is the
 * GUI-side adapter that translates the cycle gesture into a concrete
 * "set to next value" call.
 */
public class LobbySettingsControlsIntentHandler(
    private val setControlScheme: SetControlSchemeUseCase,
    private val settings: IPlayerSettingsRepository,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_CYCLE) {
            log.warn(
                "Unexpected intent '{}' routed to LobbySettingsControlsIntentHandler for player {}",
                intent.key, player.id,
            )
            return
        }
        val current = settings.findOrDefault(player.id).controlScheme
        val next: ControlScheme = when (current) {
            is ControlScheme.RightClick -> ControlScheme.Button
            is ControlScheme.Button -> ControlScheme.RightClick
        }
        setControlScheme.set(player.id, next)
        playerGateway.sendMessage(
            player.id,
            messages.render(
                player.id,
                MSG_CHANGED,
                mapOf("scheme" to schemeToken(next)),
            ),
        )
    }

    private fun schemeToken(scheme: ControlScheme): String = when (scheme) {
        is ControlScheme.RightClick -> "right-click"
        is ControlScheme.Button -> "button"
    }

    public companion object {
        public const val INTENT_CYCLE: String = "settings.controls.cycle"
        private val MSG_CHANGED = MessageKey("astera.settings.controls.changed")
    }
}
