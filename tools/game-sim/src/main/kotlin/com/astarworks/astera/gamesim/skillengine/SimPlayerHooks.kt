package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.expr.cel.CondBindings
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.EventBinding
import com.astarworks.astera.core.application.skillengine.runtime.player.PlayerHooks
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.table.Guard
import com.astarworks.astera.core.domain.skillengine.table.LockMove
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
public class SimPlayerHooks(
    private val scheduler: IScheduler,
    private val gameplay: ISkillGameplayGateway,
    private val live: SimLiveValueSource,
) : PlayerHooks {
    private val log = LoggerFactory.getLogger(javaClass)
    private val compiler = CelExprCompiler()
    private val compiled = ConcurrentHashMap<String, CompiledExpr?>()

    override fun guard(cast: CastHandle, guard: Guard, conditions: Map<String, String>, binding: EventBinding?, localTick: Int, seed: Long): Boolean? {
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
                seed = seed,
                castSeed = request.seed,
                host = live.exprHost(cast, binding),
            )
            (expr.eval(env) as? ExprValue.Bool)?.value
        } catch (e: RuntimeException) {
            log.error("[skill-error] guard '{}' of {} failed: {}", expanded, request.skill.table.skillId, e.message)
            null
        }
    }

    /** The sim has no client camera and no walk speed: a held look is the gateway's no-op, a move lock only shows in the trace. */
    override fun lock(caster: UUID, move: LockMove, pitch: Boolean, yaw: Boolean, untilTick: Int): Boolean {
        if (pitch || yaw) gameplay.lockLook(caster, pitch, yaw, untilTick)
        return true
    }

    override fun unlock(caster: UUID, move: LockMove, pitch: Boolean, yaw: Boolean) {
        if (pitch || yaw) gameplay.unlockLook(caster)
    }

    override fun setDisplayField(handle: VisualHandle, field: String, value: Double): Boolean = field == "text_opacity"

    override fun setDisplayText(handle: VisualHandle, field: String, value: String): Boolean = true

    public fun tick(now: Int) {}

    public fun release(caster: UUID) {
        gameplay.unlockLook(caster)
    }

    public fun releaseAll() {}
}
