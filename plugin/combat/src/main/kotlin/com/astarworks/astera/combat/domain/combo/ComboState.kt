package com.astarworks.astera.combat.domain.combo

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Live state of a player's in-flight combo (Wave 5combat-depth, ADR-0105).
 *
 * `IComboTracker` mints + advances a [ComboState] every time the player
 * records a step. The state is **per (playerId, weapon)** — switching
 * weapons mid-chain resets the tracker because Astera's combo rules are
 * weapon-bound (sword chains differ from staff chains).
 *
 * # Why a value snapshot, not a mutable handle
 *
 * The tracker is the owner of mutation; consumers only read snapshots. This
 * mirrors `BossInstance` and matches the silly-knuth §5 convention: domain
 * records are immutable, application services hold the live state.
 *
 * # Decay contract
 *
 * The state expires at `lastStepAtTick + timeoutTicks`. The tracker is
 * responsible for evicting expired states on the next tick / next record
 * call. The state itself does not carry "now" — callers compare against
 * the server tick they hold.
 *
 * @property playerId Combo owner.
 * @property weaponId Item driving this combo. Switching weapons resets.
 * @property steps Ordered list of recorded steps. Index 0 is the oldest.
 * @property lastStepAtTick Server tick of the most-recent step. Decay anchor.
 * @property timeoutTicks Ticks of inactivity before the combo expires.
 *   Default 60 ticks (3 s) per PoC tuning; per-route YAML can override.
 */
public data class ComboState(
    public val playerId: PlayerId,
    public val weaponId: ItemId,
    public val steps: List<ComboStep>,
    public val lastStepAtTick: Long,
    public val timeoutTicks: Int = DEFAULT_TIMEOUT_TICKS,
) {
    init {
        require(steps.isNotEmpty()) {
            "ComboState($playerId, $weaponId): steps must not be empty — drop the state instead of creating an empty one."
        }
        require(lastStepAtTick >= 0L) {
            "ComboState.lastStepAtTick must be >= 0: $lastStepAtTick"
        }
        require(timeoutTicks > 0) {
            "ComboState.timeoutTicks must be > 0: $timeoutTicks"
        }
        val ticks = steps.map { it.atTick }
        require(ticks == ticks.sorted()) {
            "ComboState.steps must be in ascending atTick order: $ticks"
        }
        require(lastStepAtTick == steps.last().atTick) {
            "ComboState.lastStepAtTick ($lastStepAtTick) must equal last step's atTick (${steps.last().atTick})"
        }
    }

    /** Convenience: how many steps recorded so far. */
    public val streakCount: Int get() = steps.size

    /** Returns true iff the combo would have expired by [nowTick]. */
    public fun isExpiredAt(nowTick: Long): Boolean =
        nowTick - lastStepAtTick > timeoutTicks

    public companion object {
        public const val DEFAULT_TIMEOUT_TICKS: Int = 60
    }
}
