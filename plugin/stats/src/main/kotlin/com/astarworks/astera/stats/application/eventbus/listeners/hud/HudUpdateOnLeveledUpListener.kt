package com.astarworks.astera.stats.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.stats.domain.event.LeveledUp
import org.slf4j.LoggerFactory

/**
 * On [LeveledUp], show a "Level N!" title (subtitle slot) and play the vanilla
 * level-up chime to the player (Wave 5wireup, ADR-0116 §G7; F3-7).
 *
 * # Why in `:plugin:stats`, not `:plugin:core`
 *
 * [LeveledUp] lives in `stats.domain.event` because the event is scoped to
 * the XP / level subsystem owned by Stats. Per ADR-0022 sibling-isolation,
 * Core cannot import from Stats — so the HUD subscriber for this event
 * lives in Stats (which already depends on Core for [IHudGateway]).
 *
 * The title goes through the same [IHudGateway] Subtitle path as the other
 * one-shot toasts (achievement, countdown, killfeed). The chime is viewer-only
 * via [IPlayerSoundGateway] so it does not leak to other players.
 */
public class HudUpdateOnLeveledUpListener(
    private val hudGateway: IHudGateway,
    private val playerSounds: IPlayerSoundGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: LeveledUp) {
        val layout = HudLayout.Subtitle(
            component = HudComponent.Text(
                messageKey = MessageKey("astera.hud.leveled-up.title"),
                color = HudColor.YELLOW,
                placeholders = mapOf("level" to event.newLevel.value.toString()),
            ),
            fadeInTicks = 5,
            stayTicks = 50,
            fadeOutTicks = 10,
        )
        hudGateway.showFor(event.playerId, layout).also { result ->
            if (result is Result.Failure) {
                log.debug(
                    "HUD leveled-up title failed for {} ({}→{}): {}",
                    event.playerId,
                    event.oldLevel,
                    event.newLevel,
                    result.error,
                )
            }
        }
        playerSounds.playForViewer(event.playerId, LEVEL_UP_SOUND)
    }

    public companion object {
        /** Vanilla `entity.player.levelup` chime. */
        public val LEVEL_UP_SOUND: SoundSpec = SoundSpec(key = "minecraft:entity.player.levelup")
    }
}
