package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueProgress
import com.astarworks.astera.match.application.port.outbound.matchmaking.LaneProgress
import com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueNotifier
import com.astarworks.astera.match.application.usecase.matchmaking.MatchFormer
import com.astarworks.astera.match.domain.matchmaking.LaneKind
import com.astarworks.astera.match.domain.matchmaking.QueueLane
import com.astarworks.astera.match.domain.matchmaking.QueueSettings
import org.slf4j.LoggerFactory

/**
 * Forms matches from the queues on a timer (WP8).
 *
 * Every [QueueSettings.checkIntervalSeconds] [tick] looks at each configured
 * lane:
 *
 *  - fewer than `min_players` queued: nothing (an already running countdown is
 *    cancelled and the queued players told);
 *  - enough players: a `queue_countdown_seconds` countdown runs and is
 *    announced at 10/5/3/2/1 s; it restarts whenever a queued player leaves;
 *  - countdown over, or the lane is full: [MatchFoundUseCase.execute] forms
 *    the match, which starts, balances teams and teleports everyone in;
 *  - the lane's stage already hosts a match: wait (players are told once).
 *
 * Time is measured in scheduler ticks so the unit tests drive it with
 * `FakeScheduler`. [settings] is a supplier so a `/astera reload matchmaking`
 * takes effect on the next tick.
 */
public class QueueMatchScheduler(
    private val matchmaker: IMatchmaker,
    private val matchFound: MatchFormer,
    private val notifier: IQueueNotifier,
    private val scheduler: IScheduler,
    private val settings: () -> QueueSettings,
) : IQueueProgress {

    private val log = LoggerFactory.getLogger(javaClass)

    private class LaneState {
        var countdownStartTick: Long? = null
        var lastAnnouncedSeconds: Int? = null
        var lastQueued: Int = 0
        var heldNotice: String? = null
        var held: Boolean = false
        var remainingSeconds: Int? = null

        fun reset() {
            countdownStartTick = null
            lastAnnouncedSeconds = null
            remainingSeconds = null
        }
    }

    private val states: MutableMap<String, LaneState> = mutableMapOf()

    /** The countdown / hold state of [laneKey] as of the last tick (ADR-0248: sidebar + action bar). */
    override fun progressOf(laneKey: String): LaneProgress {
        val state = states[laneKey] ?: return LaneProgress.IDLE
        return LaneProgress(countdownSecondsLeft = state.remainingSeconds, held = state.held)
    }

    /** Arm the repeating check. The caller owns the handle and cancels it on disable. */
    public fun start(): IScheduler.TaskHandle {
        val period = ticks(settings().checkIntervalSeconds * TICKS_PER_SECOND)
        return scheduler.runRepeating(initialDelay = period, period = period) { tick() }
    }

    public fun tick() {
        val config = settings()
        for (lane in config.lanes.values) {
            runCatching { tickLane(lane, config) }
                .onFailure { log.warn("Queue tick failed for lane '{}'", lane.key, it) }
        }
    }

    private fun tickLane(lane: QueueLane, config: QueueSettings) {
        val mode = lane.mode
        val state = states.getOrPut(lane.key) { LaneState() }
        val queued = matchmaker.pendingByMode(mode)
        val now = scheduler.currentTick()
        val previousQueued = state.lastQueued
        state.lastQueued = queued.size

        // PvP: a side needs a player of its own. Parties are indivisible, solos are free agents, so the
        // queue can fill `teams` sides only when it holds at least that many parties + solos.
        val sides = queued.count { it.partyId == null } + queued.mapNotNull { it.partyId }.toSet().size
        val missingOpponents = lane.kind == LaneKind.PVP && sides < lane.teams
        if (queued.size < lane.minPlayers || missingOpponents) {
            if (state.countdownStartTick != null) {
                log.info("Queue lane '{}' countdown cancelled: {} queued, {} needed", lane.key, queued.size, lane.minPlayers)
                queued.forEach { notify(it, "countdown-cancelled", lane, queued.size) }
            }
            state.reset()
            state.held = false
            val waiting = "waiting-opponents:${queued.size}"
            if (queued.isNotEmpty() && state.heldNotice != waiting) {
                queued.forEach { notify(it, "waiting-opponents", lane, queued.size) }
            }
            state.heldNotice = waiting
            return
        }
        if (matchFound.isStageBusy(mode)) {
            val notice = matchFound.holdNotice(mode)
            if (state.heldNotice != notice) {
                log.info("Queue lane '{}' held: {} ({} queued)", lane.key, notice, queued.size)
                queued.forEach { notify(it, notice, lane, queued.size) }
                state.heldNotice = notice
            }
            state.reset()
            state.held = true
            return
        }
        state.heldNotice = null
        state.held = false

        if (state.countdownStartTick != null && queued.size < previousQueued) {
            state.reset() // somebody left: restart the countdown
        }
        val startTick = state.countdownStartTick ?: now.also {
            state.countdownStartTick = it
            log.info(
                "Queue lane '{}' roster ready: {} queued, countdown {}s",
                lane.key, queued.size, config.queueCountdownSeconds,
            )
        }
        val remainingTicks = config.queueCountdownSeconds * TICKS_PER_SECOND - (now - startTick)
        if (queued.size >= lane.capacity || remainingTicks <= 0) {
            state.reset()
            form(lane)
            return
        }
        val remainingSeconds = ((remainingTicks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND).toInt()
        state.remainingSeconds = remainingSeconds
        if (remainingSeconds != state.lastAnnouncedSeconds &&
            (state.lastAnnouncedSeconds == null || remainingSeconds in ANNOUNCE_AT)
        ) {
            val first = state.lastAnnouncedSeconds == null
            state.lastAnnouncedSeconds = remainingSeconds
            queued.forEach { entry ->
                notify(entry, "countdown", lane, queued.size, remainingSeconds)
                // Titles (ADR-0248): "match found" when the countdown opens, big digits for the last three seconds.
                if (first) notify(entry, "found", lane, queued.size, remainingSeconds)
                else if (remainingSeconds <= 3) notify(entry, "tick", lane, queued.size, remainingSeconds)
            }
        }
    }

    private fun form(lane: QueueLane) {
        when (val result = matchFound.execute(lane.mode)) {
            is Result.Success -> if (result.value != null) {
                log.info("Queue lane '{}' formed match {}", lane.key, result.value)
            } else {
                log.info("Queue lane '{}' could not form a match yet (roster changed or arena unavailable)", lane.key)
            }
            is Result.Failure -> log.warn("Queue lane '{}' could not form a match: {}", lane.key, result.error)
        }
    }

    private fun notify(
        entry: com.astarworks.astera.match.domain.matchmaking.QueueEntry,
        suffix: String,
        lane: QueueLane,
        queued: Int,
        seconds: Int = 0,
    ) {
        notifier.notify(
            entry.playerId,
            MessageKey("astera.match.matchmaking.$suffix"),
            mapOf(
                "seconds" to seconds.toString(),
                "players" to queued.toString(),
                "required" to lane.minPlayers.toString(),
            ),
        )
    }

    private companion object {
        const val TICKS_PER_SECOND = 20L
        val ANNOUNCE_AT: Set<Int> = setOf(10, 5, 3, 2, 1)
    }
}
