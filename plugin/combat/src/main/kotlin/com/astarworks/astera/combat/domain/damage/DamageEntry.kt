package com.astarworks.astera.combat.domain.damage

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * One recorded hit observed by `IDamageHistoryService`. ADR-0063.
 *
 * `at` is the absolute server tick at which the damage landed
 * (`IScheduler.currentTick()`). Environmental damage (no `attackerId`) is
 * never recorded — the listener skips entries where `DamageDealt.attacker`
 * is null.
 */
public data class DamageEntry(
    val attackerId: PlayerId,
    val amount: Double,
    val at: Long,
)
