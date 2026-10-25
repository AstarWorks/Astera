package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.domain.presentation.PresentationMoments
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
    private val sounds: IPresentation,
) {

    public fun onChanged(event: EgoGaugeChanged) {
        if (!event.becameFull) return
        sounds.play(PresentationMoments.EGO_READY, event.playerId)
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
    }
}
