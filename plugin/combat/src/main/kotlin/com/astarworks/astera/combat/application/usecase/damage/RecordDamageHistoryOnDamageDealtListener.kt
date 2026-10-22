package com.astarworks.astera.combat.application.usecase.damage

import com.astarworks.astera.combat.application.port.outbound.IDamageHistoryService
import com.astarworks.astera.combat.domain.damage.DamageEntry
import com.astarworks.astera.combat.domain.event.DamageDealt

/**
 * Records every PvP [DamageDealt] into [IDamageHistoryService] so
 * [AssistAttributionUseCase] can resolve recent attackers at kill time.
 * Environment damage (`attacker == null`) is skipped. ADR-0063.
 */
public class RecordDamageHistoryOnDamageDealtListener(
    private val history: IDamageHistoryService,
) {
    public fun handle(event: DamageDealt) {
        if (!event.context.countsForProgression) return // ADR-0235: no assist trail from training / lobby hits
        val attacker = event.attacker ?: return
        history.record(
            event.target,
            DamageEntry(
                attackerId = attacker,
                amount = event.resolved.finalAmount,
                at = event.atTick,
            ),
        )
    }
}
