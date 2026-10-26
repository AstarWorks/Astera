package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.usecase.settings.SetLocaleUseCase
import com.astarworks.astera.core.application.usecase.settings.SettingsError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — cycle the player's locale.
 *
 * Triggered by the `settings.locale.cycle` intent emitted from the Settings
 * GUI's locale tile. A player without an explicit choice cycles from the
 * server default (ADR-0283). The cycle order today is `en ↔ ja` (the two locales in
 * [com.astarworks.astera.core.domain.player.PlayerSettings.SUPPORTED_LOCALES]).
 * Adding a third locale means extending the constant set — the cycle picks
 * the next element after the current locale by iterating the supported set
 * in declaration order.
 *
 * After applying via [SetLocaleUseCase] the new locale is echoed back as a
 * chat message so the player gets immediate feedback. The GUI itself does
 * not auto-refresh — the next reopen renders with the new locale, which is
 * the Wave G PoC budget (modal-refresh is Wave 10c+ scope).
 */
public class LobbySettingsLocaleIntentHandler(
    private val setLocale: SetLocaleUseCase,
    private val settings: IPlayerSettingsRepository,
    private val guiAdapter: IGuiAdapter,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
    /** Server default (ADR-0283): the effective locale of a player who never chose one, where the cycle starts. */
    private val defaultLocale: LocaleCode,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_CYCLE) {
            log.warn(
                "Unexpected intent '{}' routed to LobbySettingsLocaleIntentHandler for player {}",
                intent.key, player.id,
            )
            return
        }
        val current = settings.findOrDefault(player.id).locale ?: defaultLocale
        val next = nextLocale(current)
        when (val outcome = setLocale.set(player.id, next)) {
            is Result.Success -> {
                playerGateway.sendMessage(
                    player.id,
                    messages.render(
                        player.id,
                        MSG_CHANGED,
                        mapOf("locale" to next.code),
                    ),
                )
                // Close the GUI so the next reopen renders with the new
                // locale; in-place refresh isn't part of the Phase 1 GUI
                // primitive set.
                guiAdapter.close(player)
            }
            is Result.Failure -> {
                val err = outcome.error
                val key = when (err) {
                    is SettingsError.UnsupportedLocale -> MSG_UNSUPPORTED
                    else -> MSG_GENERIC
                }
                playerGateway.sendMessage(
                    player.id,
                    messages.render(player.id, key, mapOf("locale" to next.code)),
                )
            }
        }
    }

    private fun nextLocale(current: LocaleCode): LocaleCode {
        val ordered = LOCALE_CYCLE
        val idx = ordered.indexOf(current).takeIf { it >= 0 } ?: 0
        return ordered[(idx + 1) % ordered.size]
    }

    public companion object {
        public const val INTENT_CYCLE: String = "settings.locale.cycle"

        // Cycle order matches PlayerSettings.SUPPORTED_LOCALES declaration
        // order so cycling is deterministic across restarts. Adding a locale
        // = extend SUPPORTED_LOCALES + extend this list (kept in sync via
        // a domain test in PlayerSettingsTest).
        private val LOCALE_CYCLE: List<LocaleCode> = listOf(LocaleCode.EN, LocaleCode.JA)

        private val MSG_CHANGED = MessageKey("astera.settings.locale.changed")
        private val MSG_UNSUPPORTED = MessageKey("astera.settings.locale.unsupported")
        private val MSG_GENERIC = MessageKey("astera.settings.locale.error")
    }
}
