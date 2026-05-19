package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.reward.RewardBundle
import com.astarworks.astera.core.domain.reward.RewardBundleId
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * JSONB payload for a [RewardBundle] row in `reward_outbox` (Wave _persist-1
 * / ADR-0068).
 *
 * The outer aggregate carries [RewardBundleId], [PlayerId], and `createdAt`
 * as columnar fields on [com.astarworks.astera.persistence.table.RewardOutboxTable]
 * — those drive the `player_id + delivered_at` index and the `created_at ASC`
 * drain order. Only the variable-shape contents (item list, currency map, xp)
 * land here as JSONB, keeping the domain [RewardBundle] free of
 * `@Serializable` (ADR-0019 / Konsist: domain types must not import
 * kotlinx.serialization).
 *
 * Currency keys are stringified [Currency] enum names — same pattern as
 * `PostgresAuditLogRepository`'s `CurrencyTransferredJson` so additive enum
 * variants don't require an `ALTER TYPE`.
 */
@Serializable
public data class RewardBundleJson(
    val items: List<RewardItemJson>,
    val currency: Map<String, Long>,
    val xp: Long,
) {

    public fun toDomain(
        id: RewardBundleId,
        playerId: PlayerId,
        createdAt: Instant,
    ): RewardBundle = RewardBundle(
        id = id,
        playerId = playerId,
        items = items.map { it.toDomain() },
        currency = currency.mapKeys { (k, _) -> Currency.valueOf(k) },
        xp = xp,
        createdAt = createdAt,
    )

    public companion object {
        public fun fromDomain(bundle: RewardBundle): RewardBundleJson =
            RewardBundleJson(
                items = bundle.items.map { RewardItemJson.fromDomain(it) },
                currency = bundle.currency.mapKeys { (k, _) -> k.name },
                xp = bundle.xp,
            )
    }
}

@Serializable
public data class RewardItemJson(
    val itemId: String,
    val count: Int,
) {
    public fun toDomain(): RewardBundle.RewardItem =
        RewardBundle.RewardItem(itemId = itemId, count = count)

    public companion object {
        public fun fromDomain(item: RewardBundle.RewardItem): RewardItemJson =
            RewardItemJson(itemId = item.itemId, count = item.count)
    }
}
