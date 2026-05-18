package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.damage.DamageEntry
import com.astarworks.astera.combat.domain.damage.DamageHistoryWindow
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: sliding-window damage attribution. ADR-0063.
 *
 * Records [DamageEntry]s keyed by target, and answers "who damaged this
 * target in the last N ticks" so `AssistAttributionUseCase` can credit
 * assists when [com.astarworks.astera.combat.domain.event.PlayerKilled] fires.
 *
 * Default in-memory impl: [com.astarworks.astera.combat.application.service.InMemoryDamageHistoryService].
 */
public interface IDamageHistoryService {

    /** Append [entry] for [targetId]. */
    public fun record(targetId: PlayerId, entry: DamageEntry)

    /**
     * Distinct [PlayerId]s of attackers whose recorded entry satisfies
     * `at >= asOf - window.durationTicks`. Order is unspecified.
     */
    public fun recentAttackers(
        targetId: PlayerId,
        window: DamageHistoryWindow,
        asOf: Long,
    ): List<PlayerId>

    /** Forget every entry for [targetId]. Idempotent on missing keys. */
    public fun clear(targetId: PlayerId)

    /** All recorded entries for [targetId], oldest-first. Debug / test only. */
    public fun entries(targetId: PlayerId): List<DamageEntry>
}
