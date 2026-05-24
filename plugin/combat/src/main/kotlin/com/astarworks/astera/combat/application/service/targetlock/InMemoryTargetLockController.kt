package com.astarworks.astera.combat.application.service.targetlock

import com.astarworks.astera.combat.application.port.outbound.ITargetLockController
import com.astarworks.astera.combat.domain.event.TargetLockAcquired
import com.astarworks.astera.combat.domain.event.TargetLockReleased
import com.astarworks.astera.combat.domain.targetlock.TargetLockConfig
import com.astarworks.astera.combat.domain.targetlock.TargetLockState
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Default in-memory [ITargetLockController] implementation
 * (Wave 5combat-depth, ADR-0105). Not thread-safe; main-thread only.
 *
 * # Bonus arming
 *
 * On acquisition, [armedBonuses] records `(player, target) → true` so
 * the first ranged hit consumes the bonus via [consumeBonusFor]. The
 * lock itself survives the consumption (it doesn't end at first hit
 * — only the bonus does), which matches the "one shot reward" model
 * in ADR-0105 §"Target lock bonus is single-use per acquisition".
 */
public class InMemoryTargetLockController(
    private val eventBus: IDomainEventBus,
    initialConfig: TargetLockConfig = TargetLockConfig.DEFAULT,
) : ITargetLockController {

    private var cfg: TargetLockConfig = initialConfig

    private val states: MutableMap<PlayerId, TargetLockState> = HashMap()

    /** (playerId, target.id) keyed map; value true = bonus armed, awaiting consumption. */
    private val armedBonuses: MutableMap<Pair<PlayerId, EntityHandle>, Boolean> = HashMap()

    override fun configure(config: TargetLockConfig) {
        this.cfg = config
    }

    override fun config(): TargetLockConfig = cfg

    override fun tickAim(
        playerId: PlayerId,
        weapon: ItemId,
        target: EntityHandle,
        atTick: Long,
    ) {
        val cur = states[playerId]

        // Weapon switched → drop the old state.
        if (cur != null && cur.weaponId != weapon) {
            dropState(playerId, cur, TargetLockReleased.Reason.WeaponSwitched, atTick)
        }

        val state = states[playerId]
        if (state == null) {
            // Fresh acquisition build-up.
            states[playerId] = TargetLockState(
                playerId = playerId,
                weaponId = weapon,
                target = target,
                aimingTicks = 1,
                lockedSinceTick = null,
                lastAimAtTick = atTick,
            )
            maybeAcquire(playerId, atTick)
            return
        }

        // Different target → drop the old (TargetLost), start anew.
        if (state.target != target) {
            dropState(playerId, state, TargetLockReleased.Reason.TargetLost, atTick)
            states[playerId] = TargetLockState(
                playerId = playerId,
                weaponId = weapon,
                target = target,
                aimingTicks = 1,
                lockedSinceTick = null,
                lastAimAtTick = atTick,
            )
            maybeAcquire(playerId, atTick)
            return
        }

        // Same target, same weapon — extend.
        states[playerId] = state.copy(
            aimingTicks = state.aimingTicks + 1,
            lastAimAtTick = atTick,
        )
        maybeAcquire(playerId, atTick)
    }

    override fun breakAim(playerId: PlayerId, atTick: Long) {
        val cur = states[playerId] ?: return
        if (cur.isLocked) {
            // Locked state survives short aim breaks until its lifetime elapses.
            // We still update lastAimAtTick so expiry math works off "now".
            if (cur.isLockExpiredAt(cfg, atTick)) {
                dropState(playerId, cur, TargetLockReleased.Reason.Expired, atTick)
            }
            return
        }
        // Acquisition build-up: reset aimingTicks but keep the state so a
        // re-tick on the same target picks up from 1.
        states[playerId] = cur.copy(aimingTicks = 0, lastAimAtTick = atTick)
    }

    override fun release(playerId: PlayerId, atTick: Long) {
        val cur = states[playerId] ?: return
        dropState(playerId, cur, TargetLockReleased.Reason.ManualRelease, atTick)
    }

    override fun stateOf(playerId: PlayerId, nowTick: Long): TargetLockState? {
        val cur = states[playerId] ?: return null
        if (cur.isLocked && cur.isLockExpiredAt(cfg, nowTick)) {
            dropState(playerId, cur, TargetLockReleased.Reason.Expired, nowTick)
            return null
        }
        return cur
    }

    override fun consumeBonusFor(
        playerId: PlayerId,
        target: EntityHandle,
        nowTick: Long,
    ): Double {
        val state = stateOf(playerId, nowTick) ?: return 1.0
        if (!state.isLocked) return 1.0
        if (state.target != target) return 1.0
        val key = playerId to target
        val armed = armedBonuses[key] == true
        if (!armed) return 1.0
        armedBonuses.remove(key)
        return cfg.damageBonusMultiplier
    }

    // ─── internals ──────────────────────────────────────────────────────────

    private fun maybeAcquire(playerId: PlayerId, atTick: Long) {
        val cur = states[playerId] ?: return
        if (cur.isLocked) return
        if (cur.aimingTicks >= cfg.acquisitionTicks) {
            val locked = cur.copy(lockedSinceTick = atTick, lastAimAtTick = atTick)
            states[playerId] = locked
            armedBonuses[playerId to cur.target] = true
            eventBus.publish(
                TargetLockAcquired(
                    playerId = playerId,
                    weaponId = cur.weaponId,
                    target = cur.target,
                    atTick = atTick,
                ),
            )
        }
    }

    private fun dropState(
        playerId: PlayerId,
        state: TargetLockState,
        reason: TargetLockReleased.Reason,
        atTick: Long,
    ) {
        states.remove(playerId)
        armedBonuses.remove(playerId to state.target)
        if (state.isLocked) {
            eventBus.publish(
                TargetLockReleased(
                    playerId = playerId,
                    weaponId = state.weaponId,
                    target = state.target,
                    reason = reason,
                    atTick = atTick,
                ),
            )
        }
    }
}
