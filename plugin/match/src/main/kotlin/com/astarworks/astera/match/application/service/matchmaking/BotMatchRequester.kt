package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.bots.BotRelease
import com.astarworks.astera.core.application.bots.BotRequest
import com.astarworks.astera.core.application.bots.BotState
import com.astarworks.astera.core.application.bots.BotStatus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.bots.IBotRequestGateway
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchQueueLeft
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueNotifier
import com.astarworks.astera.match.domain.matchmaking.QueueLane
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * "Play vs bots" (ADR-0248): after the player is queued, asks the bot runner (ADR-0247) over Redis to bring the
 * missing opponents into the same lane, and tells the player how that goes.
 *
 * ```
 * player clicks  -> publish BotRequest on astera:bots:request
 * runner         -> BotStatus spawning -> queued   (or failed)   on astera:bots:status
 * no status for [statusTimeoutSeconds] -> "bots are unavailable" (the player stays queued for real players)
 * player leaves the queue / disconnects before the match formed -> BotRelease on astera:bots:release
 * ```
 *
 * The bots exist for one player. Without the release they would stay queued (up to the runner's queue timeout)
 * after that player cancelled, so the next click asks for yet more bots and the stale ones can even form a
 * bots-only match.
 */
public class BotMatchRequester(
    private val gateway: IBotRequestGateway,
    private val scheduler: IScheduler,
    private val notifier: IQueueNotifier,
    private val nameOf: (PlayerId) -> String?,
    private val statusTimeoutSeconds: Int = DEFAULT_STATUS_TIMEOUT_SECONDS,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private class Pending(val player: PlayerId, val lane: QueueLane, var timeout: IScheduler.TaskHandle?)

    private val pending: MutableMap<String, Pending> = mutableMapOf()
    private var subscription: IBotRequestGateway.Subscription? = null

    /** The request whose bots currently wait for each player; dropped once the match formed or the bots were released. */
    private val live: MutableMap<PlayerId, String> = mutableMapOf()

    /** Starts listening for the runner's answers. */
    public fun start() {
        subscription = gateway.subscribe { status -> onStatus(status) }
    }

    public fun stop() {
        subscription?.cancel()
        subscription = null
        pending.values.forEach { it.timeout?.cancel() }
        pending.clear()
        live.clear()
    }

    /**
     * [MatchQueueLeft] of a player: a match formed -> their bots go with it; anything else (leave, disconnect, lane
     * switch) -> the bots that came for them are released.
     */
    public fun onQueueLeft(event: MatchQueueLeft) {
        if (event.reasonTag == MATCH_FORMED_TAG) {
            live.remove(event.playerId)
            return
        }
        release(event.playerId, event.reasonTag)
    }

    private fun release(player: PlayerId, reason: String) {
        val requestId = live.remove(player) ?: return
        pending.remove(requestId)?.timeout?.cancel()
        log.info("Releasing the bots of request {} ({})", requestId, reason)
        val sent = gateway.release(BotRelease(requestId, reason))
        if (sent is Result.Failure) log.warn("Bot release {} could not be published: {}", requestId, sent.error)
    }

    /**
     * Bots to ask for so that [lane] can start with [queuedNow] players already waiting: the lane's minimum
     * (and at least one player per side) minus the humans, never less than one.
     */
    public fun botsNeeded(lane: QueueLane, queuedNow: Int): Int =
        (maxOf(lane.minPlayers, lane.teams) - queuedNow).coerceAtLeast(1)

    /** Publishes the request; returns the request id, or `null` when the bots cannot be asked at all. */
    public fun request(player: PlayerId, lane: QueueLane, queuedNow: Int): String? {
        // One set of bots per player: asking again while theirs are on the way must not stack another set.
        live[player]?.let { existing ->
            log.info("{} already has bot request {}; not asking for more", nameOf(player) ?: player.uuid, existing)
            return existing
        }
        val count = botsNeeded(lane, queuedNow)
        val request = BotRequest(
            requestId = newRequestId(),
            lane = lane.key,
            count = count,
            requestedBy = nameOf(player) ?: player.uuid.toString(),
        )
        log.info("Bot request {}: {} bot(s) for lane '{}' on behalf of {}", request.requestId, count, lane.key, request.requestedBy)
        when (val sent = gateway.publish(request)) {
            is Result.Failure -> {
                log.warn("Bot request {} could not be published: {}", request.requestId, sent.error)
                tell(player, "unavailable", emptyMap())
                return null
            }
            is Result.Success -> Unit
        }
        val entry = Pending(player, lane, timeout = null)
        entry.timeout = scheduler.run(ExecutionScope.Global, ticks(statusTimeoutSeconds * TICKS_PER_SECOND)) { onTimeout(request.requestId) }
        pending[request.requestId] = entry
        live[player] = request.requestId
        tell(player, "requested", mapOf("count" to count.toString(), "lane" to lane.key))
        return request.requestId
    }

    private fun onTimeout(requestId: String) {
        val entry = pending.remove(requestId) ?: return
        live.remove(entry.player, requestId)
        log.warn(
            "Bot request {} got no status within {}s; bots unavailable for {}",
            requestId, statusTimeoutSeconds, nameOf(entry.player) ?: entry.player.uuid,
        )
        tell(entry.player, "unavailable", emptyMap())
    }

    private fun onStatus(status: BotStatus) {
        val entry = pending[status.requestId] ?: return
        entry.timeout?.cancel()
        entry.timeout = null
        val bots = status.bots.joinToString().ifEmpty { "-" }
        when (status.state) {
            BotState.SPAWNING -> {
                tell(entry.player, "spawning", mapOf("bots" to bots))
                // The runner answered, so it exists; give it longer to finish before giving up.
                entry.timeout = scheduler.run(ExecutionScope.Global, ticks(SPAWN_TIMEOUT_SECONDS * TICKS_PER_SECOND)) { onTimeout(status.requestId) }
            }
            BotState.QUEUED -> {
                pending.remove(status.requestId)
                tell(entry.player, "queued", mapOf("bots" to bots, "lane" to entry.lane.key))
            }
            BotState.FAILED -> {
                pending.remove(status.requestId)
                live.remove(entry.player, status.requestId)
                tell(entry.player, "failed", mapOf("reason" to (status.reason ?: "unknown")))
            }
        }
    }

    private fun tell(player: PlayerId, suffix: String, placeholders: Map<String, String>) {
        notifier.notify(player, MessageKey("astera.play.bots.$suffix"), placeholders)
    }

    private companion object {
        const val TICKS_PER_SECOND = 20L
        const val DEFAULT_STATUS_TIMEOUT_SECONDS = 10
        const val SPAWN_TIMEOUT_SECONDS = 60
        const val MATCH_FORMED_TAG = "match-formed"
    }
}
