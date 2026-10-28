package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.load.CompiledSkill
import com.astarworks.astera.core.application.skillengine.runtime.ops.DashStrikeDriver
import com.astarworks.astera.core.application.skillengine.runtime.ops.MovementLockDriver
import com.astarworks.astera.core.application.skillengine.runtime.ops.ISkillWorldEffectGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.application.skillengine.runtime.presentation.PresentationRuntime
import java.util.UUID
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.hit.ContactResolver
import com.astarworks.astera.core.domain.skillengine.motion.MotionLawFactory
import java.util.concurrent.atomic.AtomicLong

/** A named attachment point of an item model (from `content/frames/<model>.yaml`, F-3). */
public data class ModelPoint(val name: String, val offset: Vec3, val axis: Vec3? = null)

/** Point table lookup for ITEM / PART frames. [part] is null for the whole item. */
public fun interface ModelPointSource {
    public fun points(model: String, part: String?): List<ModelPoint>
}

/** Sees every cast after each tick (frame poses for `astera-sim-trace`, debug overlays). */
public fun interface CastObserver {
    public fun onTick(cast: CastId, tick: Int, tree: FrameTree, nodes: Set<String>, events: List<CastEvent>)
}

/** An op / law / expression failed; the cast kept running. */
public data class CastDiagnostic(val cast: CastId, val tick: Int, val opId: String?, val message: String)

public class CastRunnerDeps(
    public val ports: SkillRuntimePorts,
    public val newTree: () -> FrameTree,
    public val motion: MotionLawFactory,
    public val contacts: ContactResolver,
    public val exprs: ExprCompiler,
    public val executors: OpExecutorRegistry,
    public val points: ModelPointSource,
    public val hosts: CastHosts,
    public val dashes: DashStrikeDriver,
    public val locks: MovementLockDriver,
    public val world: SkillWorldEditor,
    public val presentation: PresentationRuntime,
    public val cooldowns: ISkillCooldownGateway,
    public val items: ISkillItemGateway,
    public val effects: ISkillWorldEffectGateway,
    /** Looks up the current definition for `cast_skill`. */
    public val skills: (String) -> CompiledSkill?,
    /** Team test for ally/enemy target selection (default: only oneself is an ally). */
    public val isAlly: (caster: UUID, entity: UUID) -> Boolean = { a, b -> a == b },
    public val observer: CastObserver? = null,
    public val maxCastTicks: Int = 1200,
)

/**
 * Drives casts (ADR-0301). It implements dispatch only: timeline rows, motion laws, hit volumes and event
 * handlers call the registered [OpExecutor]s; no op semantics live here. A cast keeps the `CompiledSkill` it
 * started with, so a per-skill reload swaps definitions for the next cast only.
 */
public class DefaultCastRunner(private val deps: CastRunnerDeps) : CastRunner {
    private val ids = AtomicLong(1)
    private val casts = LinkedHashMap<Long, CastInstance>()
    private val bank = ExprBank(deps.exprs)

    private val log = ArrayList<CastDiagnostic>()

    @Synchronized
    override fun start(request: CastRequest): Result<CastId, CastError> {
        return startChain(request, emptyList())
    }

    private fun startChain(request: CastRequest, chain: List<String>): Result<CastId, CastError> {
        val id = CastId(ids.getAndIncrement())
        val cast = CastInstance(id, request, deps, bank, chain, { r, c -> startChain(r, c) }, ::record)
        deps.hosts.register(cast)
        val err = cast.setup()
        if (err != null) {
            deps.hosts.unregister(id)
            deps.presentation.endCast(id)
            return Result.Failure(err)
        }
        if (cast.ended) deps.hosts.unregister(id) else casts[id.value] = cast
        return Result.Success(id)
    }

    @Synchronized
    override fun raise(cast: CastId, event: CastEvent) {
        casts[cast.value]?.enqueueExternal(event)
    }

    @Synchronized
    override fun cancel(cast: CastId) {
        val c = casts.remove(cast.value) ?: return
        c.cancel()
        deps.hosts.unregister(c.id)
    }

    @Synchronized
    override fun isRunning(cast: CastId): Boolean = casts[cast.value]?.ended == false

    @Synchronized
    override fun tick(currentTick: Int) {
        for (cast in casts.values.toList()) {
            cast.tick(currentTick)
            if (cast.ended) {
                casts.remove(cast.id.value)
                deps.hosts.unregister(cast.id)
            }
        }
        deps.dashes.tick()
        deps.locks.tick()
        deps.world.tick()
    }

    /** Failures reported so far (ops without executor, bad expressions, ...). */
    @Synchronized
    public fun diagnostics(): List<CastDiagnostic> = log.toList()

    @Synchronized
    public fun clearDiagnostics() {
        log.clear()
    }

    @Synchronized
    private fun record(d: CastDiagnostic) {
        log += d
    }
}
