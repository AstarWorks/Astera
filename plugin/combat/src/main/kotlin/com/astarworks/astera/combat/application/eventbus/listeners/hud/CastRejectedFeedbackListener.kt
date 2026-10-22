package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.domain.event.CastRejectionReason
import com.astarworks.astera.combat.domain.event.SkillCastRejected
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Tells the player why a cast did nothing (WP2): a short centre-screen
 * subtitle ("Out of ammo - press Q to reload", "Not enough mana", ...).
 * Subtitle rather than action bar because the cooldown readout rewrites the
 * action bar every few ticks. Repeats within [MIN_GAP_TICKS] are dropped so a
 * held right click on an empty gun does not spam the title.
 */
public class CastRejectedFeedbackListener(
    private val hudGateway: IHudGateway,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastShown: MutableMap<PlayerId, Long> = HashMap()

    public fun handle(event: SkillCastRejected) {
        val last = lastShown[event.caster]
        if (last != null && event.atTick - last < MIN_GAP_TICKS) return
        lastShown[event.caster] = event.atTick
        val layout = HudLayout.Subtitle(
            component = HudComponent.Text(
                messageKey = keyFor(event.reason),
                color = HudColor.RED,
            ),
            fadeInTicks = 0,
            stayTicks = 15,
            fadeOutTicks = 5,
        )
        when (val outcome = hudGateway.showFor(event.caster, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug("Cast feedback failed for {}: {}", event.caster, outcome.error)
        }
    }

    public companion object {
        public const val MIN_GAP_TICKS: Long = 10L

        public fun keyFor(reason: CastRejectionReason): MessageKey =
            MessageKey("astera.combat.cast.${reason.keySuffix}")
    }
}
