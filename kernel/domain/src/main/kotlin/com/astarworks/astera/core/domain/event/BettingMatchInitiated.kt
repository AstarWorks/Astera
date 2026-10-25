package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant
import java.util.UUID

/**
 * Cross-plugin event (Wave 5bet / ADR-0103): a 1v1 betting challenge has been
 * created and is awaiting opponent acceptance.
 *
 * # When fired
 *
 * `InitiateBettingChallengeUseCase` (economy plugin) after a successful
 * `/astera-bet challenge <opponent> <amount> <currency>` invocation.
 *
 * # Subscribers
 *
 * - Wave 5e audit log: persist a `betting.initiated` audit record.
 * - Wave 8f moderation (post-launch): notify the opponent via in-game UI.
 * - Wave 12g analytics: daily challenge volume per currency / player.
 *
 * # Why polymorphic-untyped fields
 *
 * Per ADR-0022 Core never depends on the Economy plugin types
 * (`BettingMatchId`, `BettingPolicyId`, `Currency` is owned by Core so it
 * IS shared). The [bettingMatchUuid] / [policyIdString] are raw value-class
 * unwrapping mirroring the [com.astarworks.astera.core.domain.event.ItemInstanceRefined]
 * pattern. Subscribers can wrap them back into their domain ids cheaply.
 */
public data class BettingMatchInitiated(
    public val bettingMatchUuid: UUID,
    public val policyIdString: String,
    public val challengerId: PlayerId,
    public val opponentId: PlayerId,
    public val currency: Currency,
    public val stakeAmount: Long,
    public val acceptanceDeadline: Instant,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(stakeAmount > 0L) { "BettingMatchInitiated.stakeAmount must be > 0" }
        require(policyIdString.isNotBlank()) { "BettingMatchInitiated.policyIdString must not be blank" }
        require(challengerId != opponentId) { "challenger and opponent must differ" }
    }
}
