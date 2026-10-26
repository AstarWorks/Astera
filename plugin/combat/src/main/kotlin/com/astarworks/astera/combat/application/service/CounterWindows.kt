package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic

/** One active counter per defender, consumed before retaliation to avoid recursive counter chains. */
internal class CounterWindows(
    events: IDomainEventBus,
    private val scheduler: IScheduler,
    private val poses: IPlayerPoseLookup?,
    private val isAlive: (PlayerId) -> Boolean,
    private val run: (List<Mechanic>, MechanicContext) -> Unit,
) {
    private data class Window(val context: MechanicContext, val end: Long, val range: Double, val mechanics: List<Mechanic>)
    private val windows = mutableMapOf<PlayerId, Window>()
    private var closed = false
    private val combat = events.subscribe(DamageDealt::class) { event ->
        if (event.resolved.finalAmount > 0 && (event.source is DamageSource.Item ||
                event.source is DamageSource.Skill || event.source is DamageSource.Projectile)) {
            event.attacker?.let { hit(event.target, it) }
        }
    }
    private val vanilla = events.subscribe(PlayerHealthChanged::class) { event ->
        val source = event.source as? HealthChangeSource.Attack
        if (source != null && event.newHp < event.oldHp) hit(event.playerId, PlayerId(source.attackerId))
    }

    fun arm(context: MechanicContext, duration: Int, range: Double, mechanics: List<Mechanic>) {
        if (closed || duration <= 0 || !range.isFinite() || range <= 0) return
        val window = Window(context, scheduler.currentTick() + duration, range, mechanics)
        windows[context.actorId] = window
        scheduler.run(ExecutionScope.Entity(context.actorId.uuid), ticks(duration.toLong())) {
            if (windows[context.actorId] === window) windows.remove(context.actorId)
        }
    }

    private fun hit(defender: PlayerId, attacker: PlayerId) {
        val window = windows[defender] ?: return
        if (closed || attacker == defender || scheduler.currentTick() >= window.end || !isAlive(defender)) return
        val guardPose = poses?.poseOf(defender) ?: return
        val attackerPose = poses.poseOf(attacker) ?: return
        if (guardPose.world != attackerPose.world ||
            (guardPose.position - attackerPose.position).lengthSquared() > window.range * window.range) return
        windows.remove(defender)
        // Let the incoming damage finish before the counter modifies health or publishes more damage.
        scheduler.run(ExecutionScope.Entity(defender.uuid), ticks(1)) {
            if (closed || !isAlive(defender) || !isAlive(attacker)) return@run
            val live = poses.poseOf(defender) ?: return@run
            val target = poses.poseOf(attacker) ?: return@run
            if (live.world != target.world ||
                (live.position - target.position).lengthSquared() > window.range * window.range) return@run
            run(window.mechanics, window.context.copy(origin = live.position, worldId = live.world,
                pose = live, targetId = attacker, hitLocation = target.position))
        }
    }

    fun shutdown() {
        closed = true
        combat.cancel()
        vanilla.cancel()
        windows.clear()
    }
}
