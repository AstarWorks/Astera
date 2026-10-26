package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A player's currency balance changed (Wave 5a-fix relocated to core in
 * Wave 5p for cross-plugin reuse, ADR-0088).
 *
 * # Why in core (Wave 5p relocation)
 *
 * Wave 5p's `TrackAdvancementProgressListener` lives in `plugin:stats`, which
 * cannot import `plugin:economy` (ADR-0022 sibling-isolation matrix —
 * Economy → Stats, not the other way). But the listener needs to consume
 * `CurrencyChanged` to drive
 * [com.astarworks.astera.core.domain.advancement.AdvancementCriteria.CurrencyEarned].
 *
 * Moving the event to core (where every plugin can see it) is cleaner than
 * inverting the dep direction or duplicating the event in core. Same pattern
 * as `CurrencyChangeReason` (already in core), `MatchEnded` (core), and
 * `XpSource` (core) — cross-plugin events all live in
 * `core.domain.event`.
 *
 * Per Phase 1 PoC posture (CLAUDE.md §"Pre-release posture"): forward-only
 * relocation, no deprecated alias in the old `economy.domain.event` package.
 *
 * # Field semantics
 *
 *  - [delta] is signed: positive for deposits, negative for withdrawals.
 *  - [newBalance] is the balance after the change (≥ 0 by `Wallet` contract).
 *  - [reason] discriminates the change cause (deposit / withdraw / shop /
 *    match reward / mission reward / admin grant / transfer).
 */
public data class CurrencyChanged(
    public val playerId: PlayerId,
    public val currency: Currency,
    public val delta: Long,
    public val newBalance: Long,
    public val reason: CurrencyChangeReason,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
