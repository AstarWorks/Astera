package com.astarworks.astera.core.application.analytics

import com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter

/**
 * Sink-to-nowhere [IAnalyticsExporter] (Wave 5u / Wave 5engage default).
 *
 * Registered by `AsteraCorePlugin` when the Wave _ops metrics endpoint is
 * disabled (`ASTERA_METRICS_ENABLED != "true"`). Keeps the
 * [com.astarworks.astera.core.application.analytics.AnalyticsEventListener]
 * + [com.astarworks.astera.core.application.service.engagement.EngagementDashboardService]
 * wiring uniform: every plugin can resolve `IAnalyticsExporter` from the
 * registry without a null check, and metric calls are guaranteed-cheap
 * (single method dispatch into a no-op) when nobody scrapes.
 */
public object NoopAnalyticsExporter : IAnalyticsExporter {
    override fun recordMatchEnded(matchId: String, durationSeconds: Double, playerCount: Int, winningTeam: String?) = Unit
    override fun recordKill(matchId: String, attackerId: String, victimId: String, itemId: String) = Unit
    override fun recordDeath(playerId: String, matchId: String) = Unit
    override fun recordPlayerSessionEnded(playerId: String, durationSeconds: Double) = Unit
    override fun recordSkillCast(skillId: String, casterId: String) = Unit
    override fun recordShopRevenue(currency: String, amount: Long) = Unit
    override fun setMarketplaceListingCount(count: Long) = Unit
    override fun setMatchPlayerCount(matchId: String, count: Int) = Unit

    // Wave 5engage (ADR-0104)
    override fun setPlayerEngagementScore(playerId: String, score: Double) = Unit
    override fun setDauWauMau(dau: Long, wau: Long, mau: Long) = Unit
    override fun setRetentionCohort(cohort: String, day: String, rate: Double) = Unit
}
