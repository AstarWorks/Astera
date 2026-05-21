package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.AdvancementReward
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * One [AdvancementReward] from an earned advancement is ready to be applied
 * (Wave 5p, ADR-0088).
 *
 * # Why fan-out via event, not direct calls
 *
 * Core's `GrantAdvancementRewardUseCase` cannot import:
 *
 *  - `items.application.port.outbound.IItemDelivery` (items plugin; ADR-0022
 *    forbids core → feature dependency)
 *  - `stats.application.port.outbound.IAchievementRepository` (stats plugin)
 *  - any title/cosmetic adapter (Wave 6a, not yet on classpath)
 *
 * So the use case publishes one [AdvancementRewardGranted] per reward and lets
 * the owning feature plugin subscribe + apply:
 *
 *  - `economy` → `GrantCurrency` (could also use `ICurrencyLedger.credit` direct
 *    since it's in core; we publish the event anyway for analytics symmetry)
 *  - `items` → `GrantItem` (calls `IItemDelivery.give`)
 *  - `stats` → `GrantTitle` / `GrantAchievement` (delegates to repositories)
 *
 * # Idempotence
 *
 * One event per (player, advancement, reward index) is published. Subscribers
 * that need persistence-level idempotence (e.g. don't double-credit currency
 * on hot-reload) should key off (`playerId`, `advancementId`, `rewardIndex`).
 *
 * # Ordering
 *
 * Multiple rewards from the same advancement are published in declaration
 * order. The publisher waits for synchronous subscribers between publishes,
 * matching the existing `IDomainEventBus` contract.
 */
public data class AdvancementRewardGranted(
    public val playerId: PlayerId,
    public val advancementId: AdvancementId,
    public val rewardIndex: Int,
    public val reward: AdvancementReward,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
