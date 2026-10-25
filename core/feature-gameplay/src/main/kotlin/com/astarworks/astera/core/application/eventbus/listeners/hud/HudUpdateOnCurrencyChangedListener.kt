package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * On [CurrencyChanged], flash a short toast on the action bar (WP14, ADR-0177).
 *
 * The action bar's persistent owner is the cooldown readout (priority 0); this
 * toast is priority [TOAST_PRIORITY] so the gateway keeps it on screen for its
 * whole TTL instead of letting the next readout refresh erase it. The wallet
 * itself lives on the sidebar (lobby / match), so a zero delta draws nothing.
 *
 * Message `astera.hud.currency.summary` takes `{currency}`, `{delta}` (signed)
 * and `{balance}`.
 */
public class HudUpdateOnCurrencyChangedListener(
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: CurrencyChanged) {
        if (event.delta == 0L) return
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(
                messageKey = MessageKey("astera.hud.currency.summary"),
                color = if (event.delta > 0) HudColor.GOLD else HudColor.RED,
                placeholders = mapOf(
                    "currency" to event.currency.name,
                    "delta" to (if (event.delta > 0) "+" else "") + event.delta,
                    "balance" to event.newBalance.toString(),
                ),
            ),
            ttlTicks = TOAST_TTL_TICKS,
            priority = TOAST_PRIORITY,
        )
        when (val result = hudGateway.showFor(event.playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "HUD currency-changed show failed for {} ({}): {}",
                event.playerId,
                event.currency,
                result.error,
            )
        }
    }

    public companion object {
        public const val TOAST_TTL_TICKS: Int = 50
        public const val TOAST_PRIORITY: Int = 1
    }
}
