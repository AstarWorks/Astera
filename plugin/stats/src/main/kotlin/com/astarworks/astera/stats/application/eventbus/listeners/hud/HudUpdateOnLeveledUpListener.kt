package com.astarworks.astera.stats.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.stats.domain.event.LeveledUp
import org.slf4j.LoggerFactory

/**
 * On [LeveledUp], push a celebratory HUD title to the player (Wave 5wireup,
 * ADR-0116 §G7).
 *
 * # Why in `:plugin:stats`, not `:plugin:core`
 *
 * [LeveledUp] lives in `stats.domain.event` because the event is scoped to
 * the XP / level subsystem owned by Stats. Per ADR-0022 sibling-isolation,
 * Core cannot import from Stats — so the HUD subscriber for this event
 * lives in Stats (which already depends on Core for [IHudGateway]).
 *
 * # Phase 1 stub
 *
 * Same caveat as [com.astarworks.astera.core.application.eventbus.listeners.hud.HudUpdateOnCurrencyChangedListener]:
 * the in-memory HUD gateway records the call only. Wave 6b's Paper adapter
 * actually renders the title.
 */
public class HudUpdateOnLeveledUpListener(
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: LeveledUp) {
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(
                messageKey = MessageKey("astera.hud.leveled-up.announce"),
                color = HudColor.YELLOW,
                placeholders = mapOf("level" to event.newLevel.value.toString()),
            ),
            ttlTicks = 80,
            priority = 1,
        )
        hudGateway.showFor(event.playerId, layout).also { result ->
            if (result is com.astarworks.astera.core.domain.Result.Failure) {
                log.debug(
                    "HUD leveled-up show failed for {} ({}→{}): {}",
                    event.playerId,
                    event.oldLevel,
                    event.newLevel,
                    result.error,
                )
            }
        }
    }
}
