package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.domain.event.ComboStepRecorded
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * On [ComboStepRecorded], push a combo streak readout to the player's
 * action bar (Wave 5wireup, ADR-0116 §G8).
 *
 * # Why in `:plugin:combat`, not `:plugin:core`
 *
 * [ComboStepRecorded] lives in `combat.domain.event`. ADR-0022 forbids
 * Core from importing combat; the listener for this event therefore lives
 * in combat (which already depends on Core for [IHudGateway]).
 *
 * # Phase 1 stub
 *
 * Same caveat as the other two Wave 5wireup HUD listeners — the
 * in-memory HUD gateway records only. Wave 6b adapter renders. The
 * streak count is carried into the HUD layout's placeholder slot for
 * eventual `astera.hud.combo.streak` substitution.
 */
public class HudUpdateOnComboStepRecordedListener(
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: ComboStepRecorded) {
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(
                messageKey = MessageKey("astera.hud.combo.streak"),
                color = HudColor.WHITE,
            ),
            ttlTicks = 30,
            priority = 1,
        )
        hudGateway.showFor(event.playerId, layout).also { result ->
            if (result is com.astarworks.astera.core.domain.Result.Failure) {
                log.debug(
                    "HUD combo-step show failed for {} (streak={}): {}",
                    event.playerId,
                    event.streakCount,
                    result.error,
                )
            }
        }
    }
}
