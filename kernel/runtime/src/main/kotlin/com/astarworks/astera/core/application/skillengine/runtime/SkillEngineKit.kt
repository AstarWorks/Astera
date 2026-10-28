package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.load.CompiledSkill
import com.astarworks.astera.core.application.skillengine.runtime.ops.DashStrikeDriver
import com.astarworks.astera.core.application.skillengine.runtime.ops.MovementLockDriver
import com.astarworks.astera.core.application.skillengine.runtime.ops.ISkillWorldEffectGateway
import com.astarworks.astera.core.application.skillengine.runtime.ops.damageExecutors
import com.astarworks.astera.core.application.skillengine.runtime.ops.movementExecutors
import com.astarworks.astera.core.application.skillengine.runtime.ops.registerControlOpExecutors
import com.astarworks.astera.core.application.skillengine.runtime.ops.registerFrameOpExecutors
import com.astarworks.astera.core.application.skillengine.runtime.ops.registerHitOpExecutors
import com.astarworks.astera.core.application.skillengine.runtime.ops.spawnExecutors
import com.astarworks.astera.core.application.skillengine.runtime.ops.statusExecutors
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillCasterAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillScreenGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.PresentationRuntime
import com.astarworks.astera.core.application.skillengine.runtime.presentation.presentationExecutors
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.hit.ContactResolver
import com.astarworks.astera.core.domain.skillengine.motion.MotionLawFactory
import java.util.UUID

/** Every outbound port the cast runner and its executors use; the Paper plugin implements them. */
public class SkillEngineGateways(
    public val ports: SkillRuntimePorts,
    public val movement: ISkillMovementGateway,
    public val world: ISkillWorldGateway,
    public val casterAnim: ISkillCasterAnimGateway,
    public val screen: ISkillScreenGateway,
    public val cooldowns: ISkillCooldownGateway,
    public val items: ISkillItemGateway,
    public val effects: ISkillWorldEffectGateway,
)

/**
 * The assembled runtime: one [runner] with every op family registered. The platform calls [DefaultCastRunner.tick]
 * once per server tick (it also ticks dashes and delayed world edits) and [shutdown] on disable. [world] keeps its
 * edits past cast end; call `world.revertAll()` at match end.
 */
public class SkillEngineKit(
    public val runner: DefaultCastRunner,
    public val world: SkillWorldEditor,
    public val dashes: DashStrikeDriver,
    public val locks: MovementLockDriver,
    public val presentation: PresentationRuntime,
    public val hosts: CastHosts,
) {
    public fun shutdown() {
        dashes.shutdown()
        locks.shutdown()
        world.revertAll()
    }

    public companion object {
        public fun build(
            gateways: SkillEngineGateways,
            newTree: () -> FrameTree,
            motion: MotionLawFactory,
            contacts: ContactResolver,
            exprs: ExprCompiler,
            points: ModelPointSource,
            skills: (String) -> CompiledSkill?,
            isAlly: (caster: UUID, entity: UUID) -> Boolean = { a, b -> a == b },
            observer: CastObserver? = null,
            maxCastTicks: Int = 1200,
        ): SkillEngineKit {
            val hosts = CastHosts()
            val dashes = DashStrikeDriver(gateways.ports, gateways.movement, hosts)
            val locks = MovementLockDriver(gateways.movement)
            val editor = SkillWorldEditor(gateways.world)
            val presentation = PresentationRuntime(hosts, gateways.casterAnim, gateways.screen)
            val registry = DefaultOpExecutorRegistry()
            registerFrameOpExecutors(registry)
            registerHitOpExecutors(registry)
            registerControlOpExecutors(registry)
            registry.registerAll(damageExecutors())
            registry.registerAll(spawnExecutors())
            registry.registerAll(statusExecutors(hosts) { hosts.owner(it.cast) })
            registry.registerAll(movementExecutors(hosts, gateways.movement, dashes, locks))
            registry.registerAll(worldExecutors(editor))
            registry.registerAll(presentationExecutors(presentation))
            val runner = DefaultCastRunner(
                CastRunnerDeps(
                    ports = gateways.ports, newTree = newTree, motion = motion, contacts = contacts, exprs = exprs,
                    executors = registry, points = points, hosts = hosts, dashes = dashes, locks = locks, world = editor,
                    presentation = presentation, cooldowns = gateways.cooldowns, items = gateways.items,
                    effects = gateways.effects, skills = skills, isAlly = isAlly, observer = observer, maxCastTicks = maxCastTicks,
                ),
            )
            return SkillEngineKit(runner, editor, dashes, locks, presentation, hosts)
        }
    }
}
