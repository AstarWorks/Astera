package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.expr.cel.CondBindings
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.EventBinding
import com.astarworks.astera.core.application.skillengine.runtime.player.PlayerHooks
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.table.Guard
import org.bukkit.Server
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What the table player hands to the platform (ADR-0303 10.6, W1-24): CEL guard evaluation and caster input locks.
 *
 * - `lock(pitch / yaw)` goes through [ISkillGameplayGateway.lockLook] (re-sends the held angle on drift).
 * - `lock(move)` is a transient walk-speed cut plus a jump cut (never saved, so a crash cannot leave a player rooted);
 *   it lifts at the `untilTick` the row gave, on `unlock`, and in [releaseAll].
 * - `guard` expands `cond.<name>` and evaluates the CEL against the variables of the cast and the event binding.
 *   A source that fails to compile or evaluate answers `null`: the player records a fault and treats the guard as false.
 */
public class PaperPlayerHooks(
    private val server: Server,
    private val scheduler: IScheduler,
    private val gameplay: ISkillGameplayGateway,
    private val live: PaperLiveValueSource,
) : PlayerHooks {
    private val log = LoggerFactory.getLogger(javaClass)
    private val compiler = CelExprCompiler()
    private val compiled = ConcurrentHashMap<String, CompiledExpr?>()
    private val lockKey = NamespacedKey("astera", "skill_move_lock")
    private val moveLocks = ConcurrentHashMap<UUID, Int>()

    override fun guard(cast: CastHandle, guard: Guard, conditions: Map<String, String>, binding: EventBinding?, localTick: Int): Boolean? {
        val request = live.requestOf(cast) ?: return null
        val source = when (guard) {
            is Guard.Expr -> guard.source
            is Guard.Named -> "cond.${guard.cond}"
        }
        val expanded = when (val r = CondBindings.expand(request.skill.table.skillId, "guard", source, conditions)) {
            is Result.Success -> r.value
            is Result.Failure -> {
                log.error("[skill-error] guard '{}' of {}: {}", source, request.skill.table.skillId, r.error)
                return null
            }
        }
        val expr = compiled.getOrPut(expanded) {
            when (val r = compiler.compile(expanded, live.envSpec(), ExprType.BOOL)) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    log.error("[skill-error] guard '{}' of {} does not compile: {}", expanded, request.skill.table.skillId, r.error.message)
                    null
                }
            }
        } ?: return null
        return try {
            val env = ExprEnv(
                variables = live.variables(cast, binding, castTick = scheduler.currentTick().toInt() - request.startTick, stateTick = localTick),
                frames = live.frames(cast, binding),
                seed = request.seed,
                castSeed = request.seed,
            )
            (expr.eval(env) as? ExprValue.Bool)?.value
        } catch (e: RuntimeException) {
            log.error("[skill-error] guard '{}' of {} failed: {}", expanded, request.skill.table.skillId, e.message)
            null
        }
    }

    override fun lock(caster: UUID, move: Boolean, pitch: Boolean, yaw: Boolean, untilTick: Int): Boolean {
        if (pitch || yaw) gameplay.lockLook(caster, pitch, yaw, untilTick)
        if (move) lockMove(caster, untilTick)
        return true
    }

    override fun unlock(caster: UUID, move: Boolean, pitch: Boolean, yaw: Boolean) {
        if (pitch || yaw) gameplay.unlockLook(caster)
        if (move) unlockMove(caster)
    }

    /** Called every tick by the platform: lifts the move locks whose `untilTick` passed. */
    public fun tick(now: Int) {
        if (moveLocks.isEmpty()) return
        val it = moveLocks.entries.iterator()
        while (it.hasNext()) {
            val (caster, until) = it.next()
            if (now >= until) {
                it.remove()
                clearMove(caster)
            }
        }
    }

    /** Lifts every lock of [caster] (quit, death, world change). */
    public fun release(caster: UUID) {
        gameplay.unlockLook(caster)
        unlockMove(caster)
    }

    /** Lifts every lock held (plugin disable, match end). */
    public fun releaseAll() {
        moveLocks.keys.toList().forEach { release(it) }
    }

    private fun lockMove(caster: UUID, untilTick: Int) {
        val e = server.getEntity(caster) as? LivingEntity ?: return
        clearMove(caster)
        moveLocks[caster] = untilTick
        e.getAttribute(Attribute.MOVEMENT_SPEED)?.addTransientModifier(modifier(-1.0))
        e.getAttribute(Attribute.JUMP_STRENGTH)?.addTransientModifier(modifier(-1.0))
    }

    private fun unlockMove(caster: UUID) {
        moveLocks.remove(caster)
        clearMove(caster)
    }

    /** No alive check: a lock must come off a dying or respawning player too. An offline player has no entity; transient modifiers die with it. */
    private fun clearMove(caster: UUID) {
        val e = server.getEntity(caster) as? LivingEntity ?: return
        e.getAttribute(Attribute.MOVEMENT_SPEED)?.removeModifier(lockKey)
        e.getAttribute(Attribute.JUMP_STRENGTH)?.removeModifier(lockKey)
    }

    private fun modifier(amount: Double) = AttributeModifier(lockKey, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1)
}
