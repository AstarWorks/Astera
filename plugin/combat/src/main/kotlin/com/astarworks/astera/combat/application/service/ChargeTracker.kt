package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Per-player in-memory tracker for in-flight `Charged` skill windows.
 *
 * The Wave 5a charging model is intentionally **single-slot per player**: a
 * player can have at most one skill charging at a time. Starting a new charge
 * while another is in flight silently cancels the previous one (the timer is
 * cancelled and its scheduled completion callback never fires). Higher-level
 * orchestration (see `ChargedSkillCastUseCase`) is responsible for publishing
 * any `SkillChargeCancelled` domain event for the displaced charge before
 * calling [start] for the replacement.
 *
 * Time is driven exclusively through [IScheduler] — this class never reads
 * wall-clock time, which keeps the FakeScheduler-based unit tests
 * deterministic.
 */
public class ChargeTracker(
    private val scheduler: IScheduler,
) {

    /**
     * Snapshot of an in-flight charge.
     *
     * The [taskHandle] is retained so [release] and [cancel] can abort the
     * scheduled completion callback before it fires.
     */
    public data class ChargeState(
        val playerId: PlayerId,
        val skillId: SkillId,
        val startedAtTick: Long,
        val chargeTicks: Long,
        val taskHandle: IScheduler.TaskHandle?,
    )

    private val states: MutableMap<PlayerId, ChargeState> = mutableMapOf()

    /**
     * Begin a new charge window for [playerId].
     *
     * Any existing charge for the same player is cancelled (its scheduled
     * task is aborted and removed from the table) before the new one is
     * scheduled. The supplied [onComplete] runs exactly once after
     * [chargeTicks] in-game ticks if the charge is neither released nor
     * cancelled before then. On natural completion the entry is removed from
     * the table before [onComplete] fires so callers observe a clean state.
     */
    public fun start(
        playerId: PlayerId,
        skillId: SkillId,
        chargeTicks: Long,
        onComplete: () -> Unit,
    ): ChargeState {
        // Displace any in-flight charge for this player.
        states.remove(playerId)?.taskHandle?.cancel()

        val startedAtTick = scheduler.currentTick()
        // Pre-insert a placeholder so the callback can observe the entry it
        // belongs to and remove it on natural completion.
        val handle = scheduler.runLaterTicks(ExecutionScope.Entity(playerId.uuid), chargeTicks) {
            // Only fire if this charge wasn't displaced / cancelled / released
            // in the meantime. The stored handle identity is the discriminator.
            val current = states[playerId]
            if (current != null && current.skillId == skillId && current.startedAtTick == startedAtTick) {
                states.remove(playerId)
                onComplete()
            }
        }

        val state = ChargeState(
            playerId = playerId,
            skillId = skillId,
            startedAtTick = startedAtTick,
            chargeTicks = chargeTicks,
            taskHandle = handle,
        )
        states[playerId] = state
        return state
    }

    /**
     * Manually release the in-flight charge for [playerId] before its timer
     * fires.
     *
     * The scheduled completion callback is cancelled (it will never run) and
     * the state is removed from the table. The caller is then responsible
     * for invoking whatever "fire the skill" effect would have run.
     *
     * Returns the released [ChargeState], or `null` if the player had no
     * active charge.
     */
    public fun release(playerId: PlayerId): ChargeState? {
        val state = states.remove(playerId) ?: return null
        state.taskHandle?.cancel()
        return state
    }

    /**
     * Abort the in-flight charge for [playerId] without firing the effect.
     *
     * Identical to [release] in terms of state-table side-effects, but
     * semantically distinct: callers must NOT proceed to fire the skill on a
     * cancellation. Provided as a separate entry point so the use case layer
     * can publish `SkillChargeCancelled` instead of executing the skill.
     */
    public fun cancel(playerId: PlayerId): ChargeState? {
        val state = states.remove(playerId) ?: return null
        state.taskHandle?.cancel()
        return state
    }

    /** The currently in-flight charge for [playerId], or `null` if none. */
    public fun activeChargeFor(playerId: PlayerId): ChargeState? = states[playerId]
}
