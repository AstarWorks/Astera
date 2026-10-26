package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `IPveXpDistributor` after a mob kill has been converted to
 * player XP (Wave 5pve, ADR-0085).
 *
 * Pairs with [PveLootDistributed] — together they form the post-mob-kill
 * reward pipeline. Subscribers:
 *
 *  - Stats Norma (Wave 5e): increment "PvE kill" norma category.
 *  - Battle pass XP (Phase 4): credit XP toward the active season ladder.
 *  - Analytics: per-mob XP yield audit.
 *
 * Skipped for `null` killer kills (environment / suicide) — XP is a player
 * reward, not an event-of-record.
 *
 * @property playerId Player who received the XP.
 * @property mobTypeId Mob that yielded the XP.
 * @property xpAmount Non-negative integer XP delta granted. Zero is valid
 *   (a balance flag may zero out the grant without skipping the event, so
 *   downstream audit still records the kill).
 * @property atTick Server tick at grant time.
 */
public data class PveXpGranted(
    public val playerId: PlayerId,
    public val mobTypeId: MobTypeId,
    public val xpAmount: Int,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    init {
        require(xpAmount >= 0) {
            "PveXpGranted.xpAmount must be non-negative: $xpAmount"
        }
    }
}
