package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * On [CurrencyChanged], push a stub HUD update to the player's action bar
 * (Wave 5wireup, ADR-0116 §G6).
 *
 * # Phase 1 stub
 *
 * The `InMemoryHudGateway` default records the call and publishes a
 * `HudShown` event — no actual rendering happens until Wave 6b's
 * `PaperHudGateway` ships. This listener is the wiring seam that closes
 * the "silent drop" gap (a deposit fires `CurrencyChanged` but no HUD
 * subscriber existed). Once Wave 6b lands, the same listener carries the
 * update through to the player's screen unchanged.
 *
 * # MessageKey
 *
 * `astera.hud.currency.summary` is the placeholder key the renderer
 * resolves with `{newBalance}` / `{delta}` substitutions when the i18n
 * tier grows placeholder support for HUD strings.
 */
public class HudUpdateOnCurrencyChangedListener(
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: CurrencyChanged) {
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(
                messageKey = MessageKey("astera.hud.currency.summary"),
                color = HudColor.GOLD,
            ),
            ttlTicks = 60,
        )
        hudGateway.showFor(event.playerId, layout).also { result ->
            if (result is com.astarworks.astera.core.domain.Result.Failure) {
                log.debug(
                    "HUD currency-changed show failed for {} ({}): {}",
                    event.playerId,
                    event.currency,
                    result.error,
                )
            }
        }
    }
}
