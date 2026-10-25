package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.usecase.settings.SetVolumeUseCase
import com.astarworks.astera.core.application.usecase.settings.SettingsError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.VolumeChannel
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — cycle one channel's volume.
 *
 * Triggered by the `settings.volume.adjust` intent. The intent's
 * `payload[channel]` selects which bus (default `music` if omitted) and the
 * value cycles in the fixed sequence `100 → 75 → 50 → 25 → 0 → 100 …` so a
 * single tile click decrements by one step and wraps. The fixed step pattern
 * keeps the Phase 1 UX legible (no scroll wheel, no slider GUI primitive
 * yet).
 *
 * Per-channel separation lives in [VolumeChannel] — the GUI Spec exposes
 * three tiles in Wave H (one per channel), but Phase 1's Settings GUI only
 * shows a single "volume" tile that targets [VolumeChannel.Music]. The
 * Brigadier command `/astera volume <channel> <0-100>` is the way to set
 * the other two channels until the multi-tile spec lands.
 */
public class LobbySettingsVolumeIntentHandler(
    private val setVolume: SetVolumeUseCase,
    private val settings: IPlayerSettingsRepository,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_ADJUST) {
            log.warn(
                "Unexpected intent '{}' routed to LobbySettingsVolumeIntentHandler for player {}",
                intent.key, player.id,
            )
            return
        }
        val channel = parseChannel(intent.payload["channel"])
        val current = settings.findOrDefault(player.id).volume(channel)
        val next = nextStep(current)
        when (val outcome = setVolume.set(player.id, channel, next)) {
            is Result.Success -> {
                playerGateway.sendMessage(
                    player.id,
                    messages.render(
                        player.id,
                        MSG_CHANGED,
                        mapOf(
                            "channel" to channel.name.lowercase(),
                            "value" to next.toString(),
                        ),
                    ),
                )
            }
            is Result.Failure -> {
                // SetVolumeUseCase only rejects out-of-range; we generate
                // values inside VOLUME_STEPS so this branch is unreachable
                // by construction — log defensively in case the constant
                // is ever edited to include an invalid step.
                val err = outcome.error
                val key = if (err is SettingsError.InvalidVolume) MSG_INVALID else MSG_GENERIC
                log.warn(
                    "Volume adjust unexpectedly failed for player {} channel {} value {}: {}",
                    player.id, channel, next, err,
                )
                playerGateway.sendMessage(
                    player.id,
                    messages.render(player.id, key, mapOf("value" to next.toString())),
                )
            }
        }
    }

    private fun parseChannel(raw: String?): VolumeChannel {
        if (raw.isNullOrBlank()) return VolumeChannel.Music
        return when (raw.lowercase()) {
            "music" -> VolumeChannel.Music
            "sound" -> VolumeChannel.Sound
            "voice" -> VolumeChannel.Voice
            else -> {
                log.warn("Unknown volume channel '{}' in intent payload; defaulting to Music", raw)
                VolumeChannel.Music
            }
        }
    }

    private fun nextStep(current: Int): Int {
        // 100 → 75 → 50 → 25 → 0 → 100. Find the smallest step strictly
        // less than current; wrap to 100 if current is already 0.
        val below = VOLUME_STEPS.firstOrNull { it < current } ?: return WRAP_TO
        return below
    }

    public companion object {
        public const val INTENT_ADJUST: String = "settings.volume.adjust"

        // Decremented step sequence — first step strictly below `current`
        // is the next value. Order matters; do not sort.
        private val VOLUME_STEPS: List<Int> = listOf(75, 50, 25, 0)
        private const val WRAP_TO: Int = 100

        private val MSG_CHANGED = MessageKey("astera.settings.volume.changed")
        private val MSG_INVALID = MessageKey("astera.settings.volume.invalid")
        private val MSG_GENERIC = MessageKey("astera.settings.volume.error")
    }
}
