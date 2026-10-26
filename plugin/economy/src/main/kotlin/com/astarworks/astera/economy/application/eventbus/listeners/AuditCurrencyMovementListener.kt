package com.astarworks.astera.economy.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.audit.AuditEventId
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import org.slf4j.LoggerFactory

/**
 * Appends one [AuditEvent.CurrencyMovement] per [CurrencyChanged] (ADR-0202):
 * who, which currency, signed delta, balance after, and why. This is the
 * transaction log that answers "where did my GOLD go" and lets support replay
 * a dispute. A missing or failing audit store never blocks the money movement:
 * the failure is logged and swallowed.
 *
 * The repository is a provider because the Postgres overlay may register after
 * this plugin enables (ADR-0195); it is resolved per event.
 */
public class AuditCurrencyMovementListener(
    private val audit: () -> IAuditLogRepository?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: CurrencyChanged) {
        if (event.delta == 0L) return
        val repo = audit() ?: return
        runCatching {
            repo.write(
                AuditEvent.CurrencyMovement(
                    id = AuditEventId.random(),
                    player = event.playerId,
                    currency = event.currency,
                    delta = event.delta,
                    newBalance = event.newBalance,
                    reason = describe(event.reason),
                    occurredAt = event.occurredAt,
                ),
            )
        }.onFailure { log.warn("Could not audit currency movement for {}: {}", event.playerId, it.toString()) }
    }

    public companion object {
        /** Stable, greppable reason string. */
        public fun describe(reason: CurrencyChangeReason): String = when (reason) {
            CurrencyChangeReason.Deposit -> "deposit"
            CurrencyChangeReason.Withdraw -> "withdraw"
            is CurrencyChangeReason.Transfer -> "transfer:${reason.counterpartyId.uuid}"
            CurrencyChangeReason.MatchReward -> "match_reward"
            CurrencyChangeReason.MissionReward -> "mission_reward"
            is CurrencyChangeReason.LevelReward -> "level_reward:${reason.level}"
            CurrencyChangeReason.LoginBonus -> "login_bonus"
            CurrencyChangeReason.AdminGrant -> "admin_grant"
            CurrencyChangeReason.TutorialReward -> "tutorial_reward"
            CurrencyChangeReason.AchievementReward -> "achievement_reward"
            is CurrencyChangeReason.ShopPurchase -> "shop:${reason.shopId.value}/${reason.offerId.value}"
            is CurrencyChangeReason.Betting -> "betting:${reason.kind}:${reason.bettingMatchUuid}"
        }
    }
}
