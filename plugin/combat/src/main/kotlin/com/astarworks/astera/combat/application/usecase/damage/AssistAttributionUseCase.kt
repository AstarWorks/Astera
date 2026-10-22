package com.astarworks.astera.combat.application.usecase.damage

import com.astarworks.astera.combat.application.port.outbound.IDamageHistoryService
import com.astarworks.astera.combat.domain.damage.DamageHistoryWindow
import com.astarworks.astera.combat.domain.event.AssistCredited
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus

/**
 * On [PlayerKilled], publish one [AssistCredited] per distinct attacker who
 * damaged the victim within [window], excluding the killer. ADR-0063.
 *
 * `matchId` is left `null` for now — Wave 5c match-mode listeners may
 * enrich the event downstream once `IMatchTeamLookup` exposes a `matchOf`
 * lookup. Consumers (e.g. `RecordAssistOnAssistCreditedListener`) already
 * null-check.
 */
public class AssistAttributionUseCase(
    private val history: IDamageHistoryService,
    private val eventBus: IDomainEventBus,
    private val window: DamageHistoryWindow = DamageHistoryWindow.DEFAULT,
) {
    public fun handle(event: PlayerKilled) {
        if (!event.context.countsForProgression) return // ADR-0235
        val attackers = history.recentAttackers(event.victim, window, asOf = event.atTick)
        if (attackers.isEmpty()) return
        attackers.asSequence()
            .filter { it != event.killer }
            .forEach { assister ->
                eventBus.publish(
                    AssistCredited(
                        player = assister,
                        victim = event.victim,
                        matchId = null,
                        atTick = event.atTick,
                    ),
                )
            }
    }
}
