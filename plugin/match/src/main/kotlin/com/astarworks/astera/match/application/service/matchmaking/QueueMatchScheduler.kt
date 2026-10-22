package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueNotifier
import com.astarworks.astera.match.application.usecase.matchmaking.MatchFormer
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
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private class LaneState {
        var countdownStartTick: Long? = null
        var lastAnnouncedSeconds: Int? = null
        var lastQueued: Int = 0
        var busyNotified: Boolean = false

        fun reset() {
            countdownStartTick = null
            lastAnnouncedSeconds = null
        }
    }

    private val states: MutableMap<String, LaneState> = mutableMapOf()

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

        if (queued.size < lane.minPlayers) {
            if (state.countdownStartTick != null) {
                queued.forEach { notify(it, "countdown-cancelled", lane, queued.size) }
            }
            state.reset()
            state.busyNotified = false
            return
        }
        if (matchFound.isStageBusy(mode)) {
            if (!state.busyNotified) {
                queued.forEach { notify(it, "stage-busy", lane, queued.size) }
                state.busyNotified = true
            }
            state.reset()
            return
        }
        state.busyNotified = false

        if (state.countdownStartTick != null && queued.size < previousQueued) {
            state.reset() // somebody left: restart the countdown
        }
        val startTick = state.countdownStartTick ?: now.also { state.countdownStartTick = it }
        val remainingTicks = config.queueCountdownSeconds * TICKS_PER_SECOND - (now - startTick)
        if (queued.size >= lane.capacity || remainingTicks <= 0) {
            state.reset()
            form(lane)
            return
        }
        val remainingSeconds = ((remainingTicks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND).toInt()
        if (remainingSeconds != state.lastAnnouncedSeconds &&
            (state.lastAnnouncedSeconds == null || remainingSeconds in ANNOUNCE_AT)
        ) {
            state.lastAnnouncedSeconds = remainingSeconds
            queued.forEach { notify(it, "countdown", lane, queued.size, remainingSeconds) }
        }
    }

    private fun form(lane: QueueLane) {
        when (val result = matchFound.execute(lane.mode)) {
            is Result.Success -> if (result.value != null) {
                log.info("Queue lane '{}' formed match {}", lane.key, result.value)
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
