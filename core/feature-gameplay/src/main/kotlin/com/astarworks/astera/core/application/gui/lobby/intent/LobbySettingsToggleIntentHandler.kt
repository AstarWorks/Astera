package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.ParticleVisibility
import com.astarworks.astera.core.domain.player.PlayerSettings
import org.slf4j.LoggerFactory

/**
 * UI-03 (docs/game-design/access-map.md) - the settings screen's cycle/toggle tiles for the
 * [PlayerSettings] fields that had no GUI: particle visibility, double jump and the public profile.
 *
 * Intent `settings.toggle`, payload `key` = `particles` | `double-jump` | `public-profile`. The new value is
 * persisted through [IPlayerSettingsRepository.upsert] (the same store `/astera lang` and the other
 * settings tiles write), echoed in chat, and the settings screen is reopened so the click is visible.
 */
public class LobbySettingsToggleIntentHandler(
    private val settings: IPlayerSettingsRepository,
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val settingsSpec: () -> GuiSpec?,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
    /** Called after a write so hot-path readers (HUD toggles) drop their cached copy. */
    private val onChanged: (com.astarworks.astera.core.domain.player.PlayerId) -> Unit = {},
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_TOGGLE) {
            log.warn("Unexpected intent '{}' routed to LobbySettingsToggleIntentHandler for {}", intent.key, player.id)
            return
        }
        val current = settings.findOrDefault(player.id)
        val (updated, label, value) = when (val key = intent.payload[PAYLOAD_KEY]) {
            KEY_PARTICLES -> {
                val next = nextParticles(current.particleVisibility)
                Triple(current.copy(particleVisibility = next), "particles", "particles.${particleToken(next)}")
            }
            KEY_DOUBLE_JUMP -> {
                val next = !current.doubleJumpEnabled
                Triple(current.copy(doubleJumpEnabled = next), "double-jump", if (next) "on" else "off")
            }
            KEY_PUBLIC_PROFILE -> {
                val next = !current.publicProfileEnabled
                Triple(current.copy(publicProfileEnabled = next), "public-profile", if (next) "on" else "off")
            }
            KEY_HP_LINE -> {
                val next = !current.hpLineVisible
                Triple(current.copy(hpLineVisible = next), "hp-line", if (next) "on" else "off")
            }
            KEY_DAMAGE_NUMBERS -> {
                val next = !current.damageNumbersVisible
                Triple(current.copy(damageNumbersVisible = next), "damage-numbers", if (next) "on" else "off")
            }
            KEY_TUTORIAL_HINTS -> {
                val next = !current.tutorialHintsEnabled
                Triple(current.copy(tutorialHintsEnabled = next), "tutorial-hints", if (next) "on" else "off")
            }
            KEY_VFX_INTENSITY -> {
                val next = current.vfxIntensity.next()
                Triple(current.copy(vfxIntensity = next), "vfx-intensity", "vfx-intensity.${next.yamlName}")
            }
            else -> {
                log.warn("settings.toggle: unknown key '{}' from {}", key, player.id)
                return
            }
        }
        settings.upsert(updated)
        onChanged(player.id)
        playerGateway.sendMessage(
            player.id,
            messages.render(
                player.id,
                MessageKey("astera.settings.toggle.$label"),
                mapOf("value" to messages.render(player.id, MessageKey("astera.settings.value.$value"), emptyMap())),
            ),
        )
        val mcPlayer = mcServer.findPlayer(player.id) ?: return
        settingsSpec()?.let { guiAdapter.open(mcPlayer, it) }
    }

    private fun nextParticles(current: ParticleVisibility): ParticleVisibility = when (current) {
        ParticleVisibility.All -> ParticleVisibility.OwnTeamOnly
        ParticleVisibility.OwnTeamOnly -> ParticleVisibility.OwnOnly
        ParticleVisibility.OwnOnly -> ParticleVisibility.All
    }

    private fun particleToken(v: ParticleVisibility): String = when (v) {
        ParticleVisibility.All -> "all"
        ParticleVisibility.OwnTeamOnly -> "team"
        ParticleVisibility.OwnOnly -> "own"
    }

    public companion object {
        public const val INTENT_TOGGLE: String = "settings.toggle"
        public const val PAYLOAD_KEY: String = "key"
        public const val KEY_PARTICLES: String = "particles"
        public const val KEY_DOUBLE_JUMP: String = "double-jump"
        public const val KEY_PUBLIC_PROFILE: String = "public-profile"
        public const val KEY_HP_LINE: String = "hp-line"
        public const val KEY_DAMAGE_NUMBERS: String = "damage-numbers"
        public const val KEY_TUTORIAL_HINTS: String = "tutorial-hints"
        public const val KEY_VFX_INTENSITY: String = "vfx-intensity"
    }
}
