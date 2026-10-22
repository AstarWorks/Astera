package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.event.EgoGaugeChanged
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * "E.G.O READY" cue (ADR-0231): when a gain fills the gauge, a chime and a small
 * subtitle flash. The persistent gauge readout is the action bar
 * ([HudStatusEffectReadoutListener]); this is only the one-shot edge cue, on the
 * subtitle channel next to the cast-rejection feedback.
 */
public class EgoReadyCueListener(
    private val hud: IHudGateway,
    private val sounds: IPlayerSoundGateway,
) {

    public fun onChanged(event: EgoGaugeChanged) {
        if (!event.becameFull) return
        sounds.playForViewer(event.playerId, READY_SOUND)
        hud.showFor(
            event.playerId,
            HudLayout.Subtitle(
                component = HudComponent.Text(messageKey = FLASH_KEY, color = HudColor.GOLD),
                fadeInTicks = 2,
                stayTicks = 20,
                fadeOutTicks = 8,
            ),
        )
    }

    public companion object {
        public val FLASH_KEY: MessageKey = MessageKey("astera.hud.ego.flash")

        public val READY_SOUND: SoundSpec =
            SoundSpec(key = "minecraft:block.beacon.activate", volume = 0.5, pitch = 1.8)
    }
}
