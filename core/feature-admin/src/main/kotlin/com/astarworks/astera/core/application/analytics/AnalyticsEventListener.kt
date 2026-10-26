package com.astarworks.astera.core.application.analytics

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.SkillCastRequested
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Subscribes to core-level DomainEvents and republishes them as analytics
 * KPI metrics (Wave 5u, ADR-0087).
 *
 * Lives in `core.application.*` because:
 *  - It mediates between two abstractions (IDomainEventBus + IAnalyticsExporter),
 *    not between a port and Paper. So `adapter/paper/` would be wrong.
 *  - The Konsist application-layer rules forbid Bukkit imports; this file
 *    has none — perfect fit.
 *
 * Feature-plugin events that are not visible in Core (PlayerKilled in
 * Combat, CurrencyChanged in Economy) cannot be subscribed to from Core
 * without breaking ADR-0022 sibling-isolation. They are recorded directly
 * by their owning plugins via [IAnalyticsExporter] lookup in the
 * ServiceRegistry — see ADR-0087 §"Cross-plugin metric publishing".
 *
 * # Match-time bookkeeping
 *
 * Match duration histograms need a (started → ended) pairing. Match domain
 * carries [MatchStarted.atTick] / [MatchEnded.atTick]; deriving wall-clock
 * seconds from tick count would assume a stable 20 TPS which is not
 * guaranteed. So we capture an [Instant] at MatchStarted and diff against
 * the MatchEnded occurredAt timestamp — accurate even under TPS drift.
 */
public class AnalyticsEventListener(
    private val eventBus: IDomainEventBus,
    private val exporter: IAnalyticsExporter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** matchId → wall-clock Instant of MatchStarted. */
    private val matchStartTimes: MutableMap<String, Instant> = ConcurrentHashMap()

    /** playerId → wall-clock Instant of PlayerJoinedServer. */
    private val playerJoinTimes: MutableMap<String, Instant> = ConcurrentHashMap()

    private val subscriptions: MutableList<IDomainEventBus.Subscription> = mutableListOf()

    public fun start() {
        check(subscriptions.isEmpty()) {
            "AnalyticsEventListener.start() called twice without intervening stop()"
        }

        subscriptions += eventBus.subscribe(MatchStarted::class, EventDelivery.Global) { event ->
            matchStartTimes[event.matchId.value] = event.occurredAt
        }

        subscriptions += eventBus.subscribe(MatchEnded::class, EventDelivery.Global) { event ->
            val matchKey = event.matchId.value
            val startedAt = matchStartTimes.remove(matchKey)
            val durationSeconds = if (startedAt != null) {
                Duration.between(startedAt, event.occurredAt).toMillis() / 1000.0
            } else {
                // MatchEnded without a paired MatchStarted (e.g., crash recovery
                // before Wave 5u listener attached). Record zero duration so the
                // histogram still gets a sample for "match ended" cardinality.
                0.0
            }
            exporter.recordMatchEnded(
                matchId = matchKey,
                durationSeconds = durationSeconds,
                playerCount = 0, // Per-match player count is tracked elsewhere (Wave 5c).
                winningTeam = event.winningTeam?.value,
            )
        }

        subscriptions += eventBus.subscribe(PlayerJoinedServer::class, EventDelivery.Global) { event ->
            playerJoinTimes[event.playerId.uuid.toString()] = event.occurredAt
        }

        subscriptions += eventBus.subscribe(SkillCastRequested::class, EventDelivery.Global) { event ->
            exporter.recordSkillCast(
                skillId = event.skillId.value,
                casterId = event.actorId.uuid.toString(),
            )
        }

        log.info("AnalyticsEventListener wired — {} subscriptions", subscriptions.size)
    }

    /**
     * Publishes a player-session ended metric. Called by the platform layer
     * when the cross-server session domain (Wave 5e `PlayerSessionRepository`)
     * confirms a player has truly left — distinct from PlayerQuit (a Bukkit-side
     * event that can fire on Paper-Folia thread shifts even mid-session).
     */
    public fun reportPlayerSessionEnded(playerId: String, endedAt: Instant = Instant.now()) {
        val joinedAt = playerJoinTimes.remove(playerId) ?: return
        val durationSeconds = Duration.between(joinedAt, endedAt).toMillis() / 1000.0
        exporter.recordPlayerSessionEnded(playerId, durationSeconds)
    }

    public fun stop() {
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        matchStartTimes.clear()
        playerJoinTimes.clear()
    }
}
