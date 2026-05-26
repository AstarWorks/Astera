package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.ICelEvaluator
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.skill.expression.CelContext
import com.astarworks.astera.combat.domain.skill.expression.CelSkillScript
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IParticleRegistry
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.effect.IParticleScheduler
import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.effect.scheduler.ScheduledParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.DirectionRef
import com.astarworks.astera.core.domain.skill.targeter.LocationRef
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.BoolValue
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.skill.value.VectorValue
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Wave _skills-2 (ADR-0077) central runtime: takes a `List<Mechanic>` plus a
 * [MechanicContext] and fires each mechanic against its resolved targets.
 *
 * Replaces the old `SkillEffectDispatcher` (ADR-0074) — the Mechanic surface is
 * ~6x larger and each Mechanic now carries its own targeter, condition, and
 * timing (`delayTicks` / `periodTicks` / `durationTicks`) so per-tick semantics
 * stop being a hand-rolled `fly_tick` callback and become a uniform scheduler
 * fold (see ADR-0077 §"timing fields — scheduler-folded design").
 *
 * Dispatch loop (per mechanic):
 *
 *  1. Resolve the gating [Condition] — if false (or CEL failure), skip.
 *  2. Resolve timing: `delayTicks > 0` → [IScheduler.runLater]; `periodTicks > 0`
 *     → [IScheduler.runRepeating] (terminated by `durationTicks` or by phase
 *     end, whichever comes first); else execute now.
 *  3. Resolve targets via [TargeterResolver] and dispatch the gameplay /
 *     visual side-effect to the right outbound port.
 *  4. Recursively expand control-flow mechanics ([Mechanic.IfThen],
 *     [Mechanic.Loop], [Mechanic.Choose], [Mechanic.CastSkill]). Cycles are
 *     gated by [MechanicContext.castedSkills] + [MAX_CHAIN_DEPTH].
 *
 * Graceful degradation: ports that are not wired in Phase 1 (e.g. a real
 * [IBroadcaster] sendTitle, an `IMobSpawnService`) warn-log and continue —
 * a missing port never throws. Phase 6 wires the real impls and the visual
 * mechanics light up automatically.
 *
 * CEL: [NumericValue.Cel] / [IntValue.Cel] / [BoolValue.Cel] / [VectorValue.Cel]
 * are evaluated via [ICelEvaluator]; the Phase 1 [StubCelEvaluator] returns
 * `Failure` for every script, so [resolveDouble] / [resolveInt] / [resolveBool]
 * / [resolveVec3] log a warning and substitute the documented fallback (`0.0` /
 * `0` / `false` / [Vec3.ZERO]). Real evaluator (Phase 6) makes Cel work
 * automatically.
 */
public class MechanicDispatcher(
    private val applyDamage: ApplyDamageUseCase?,
    private val applyStatus: ApplyStatusUseCase?,
    private val worldGateway: IWorldGateway,
    private val knockbackGateway: IKnockbackGateway?,
    private val nearbyQuery: INearbyTargetQuery,
    private val broadcaster: IBroadcaster?,
    private val celEvaluator: ICelEvaluator,
    private val scheduler: IScheduler,
    @Suppress("unused") private val eventBus: IDomainEventBus,
    /**
     * Wave 5effects (ADR-0109): tick-scheduled emission engine. When wired
     * `Mechanic.SpawnParticle` produces a real emission (single-tick burst
     * routed through the scheduler so it shares cancellation infrastructure
     * with `EffectChain` / `Pulse` / `RingExpansion`). When null, the
     * mechanic warn-logs and continues (same posture as the other
     * deferred-port mechanics).
     */
    private val particleScheduler: IParticleScheduler? = null,
    /**
     * Wave 5effects (ADR-0109): catalog of [com.astarworks.astera.core.domain.effect.ParticleDefinition]s
     * loaded from `content/particles/`. Resolves a `Mechanic.SpawnParticle.particleId`
     * to a vendor-neutral particle key; without the registry the dispatcher
     * falls back to the id's `value` as the key (best-effort, matches the
     * pre-5effects behaviour).
     */
    private val particleRegistry: IParticleRegistry? = null,
    /**
     * Wave 5effects (ADR-0109): catalog of [com.astarworks.astera.core.domain.effect.sound.SoundDefinition]s
     * loaded from `content/sounds/`. Resolves a `Mechanic.PlaySound.soundId`
     * to a default volume / pitch when the mechanic does not override them.
     */
    private val soundRegistry: ISoundRegistry? = null,
    private val log: Logger = LoggerFactory.getLogger(MechanicDispatcher::class.java),
) {

    /**
     * Phase 1 hook: invoker for [Mechanic.CastSkill]. The combat plugin wires
     * this to a closure over `CastSkillUseCase.execute(...)`. Left as a
     * functional interface so the dispatcher does not statically depend on the
     * skill cast use case (avoids a wiring cycle).
     */
    public fun interface CastSkillInvoker {
        public fun invoke(skillId: SkillId, targets: Targeter, ctx: MechanicContext)
    }

    private var castSkillInvoker: CastSkillInvoker = CastSkillInvoker { _, _, _ ->
        // Default: no-op + warn. Real impl wired by AsteraCombatPlugin.
        log.warn("MechanicDispatcher.castSkillInvoker not wired; Mechanic.CastSkill dropped")
    }

    /** Wire the CastSkill invoker after construction (avoids wiring cycle). */
    public fun setCastSkillInvoker(invoker: CastSkillInvoker) {
        this.castSkillInvoker = invoker
    }

    internal val targeterResolver: TargeterResolver = TargeterResolver(nearbyQuery, log)

    /**
     * Dispatch each mechanic in [mechanics] against [ctx]. Used by archetype
     * handlers once per phase (cast / hit_entity / hit_block / miss / expire …).
     */
    public fun dispatch(mechanics: List<Mechanic>, ctx: MechanicContext) {
        for (mechanic in mechanics) {
            try {
                dispatchOne(mechanic, ctx)
            } catch (ex: Exception) {
                log.warn(
                    "MechanicDispatcher: mechanic {} threw; skipping",
                    mechanic::class.simpleName,
                    ex,
                )
            }
        }
    }

    private fun dispatchOne(mechanic: Mechanic, ctx: MechanicContext) {
        // 1. Gate by condition (if any).
        val gate = mechanic.condition
        if (gate != null && !resolveCondition(gate, ctx)) return

        // 2. Resolve timing.
        val delay = resolveInt(mechanic.delayTicks, ctx).coerceAtLeast(0)
        val period = resolveInt(mechanic.periodTicks, ctx).coerceAtLeast(0)
        val duration = resolveInt(mechanic.durationTicks, ctx).coerceAtLeast(0)

        when {
            period > 0 -> {
                // Repeating fire. duration = 0 → fire forever (phase-handler
                // is expected to cancel handles when its phase ends). Phase 1:
                // we self-terminate after duration ticks; Phase 6 will let the
                // archetype handler hold the handle and cancel on phase end.
                val handle = scheduler.runRepeating(
                    initialDelay = ticks(delay.toLong()),
                    period = ticks(period.toLong()),
                ) {
                    execute(mechanic, ctx)
                }
                if (duration > 0) {
                    scheduler.runLater(ticks((delay + duration).toLong())) { handle.cancel() }
                }
            }
            delay > 0 -> {
                scheduler.runLater(ticks(delay.toLong())) { execute(mechanic, ctx) }
            }
            else -> {
                execute(mechanic, ctx)
            }
        }
    }

    /**
     * Apply the mechanic to its resolved targets. Routed in one big when —
     * 35-branch surface is intentional (ADR-0077): types are exhaustive so the
     * compiler catches a new Mechanic variant. New variants land as new `is`
     * branches; missing branches are a compile error.
     */
    private fun execute(mechanic: Mechanic, ctx: MechanicContext) {
        when (mechanic) {
            // ===== Gameplay mutation =================================
            is Mechanic.Damage -> {
                val targets = targeterResolver.resolvePlayers(mechanic.targets, ctx)
                if (targets.isEmpty()) return
                if (applyDamage == null) {
                    log.warn("Damage mechanic dropped: ApplyDamageUseCase not wired")
                    return
                }
                val profile = buildDamageProfile(mechanic.components)
                val sourceSkillId = ctx.castedSkills.firstOrNull() ?: FALLBACK_SKILL_ID
                for (target in targets) {
                    applyDamage.execute(
                        DamageAttempt(
                            targetId = target,
                            baseProfile = profile,
                            source = DamageSource.Skill(
                                skillId = sourceSkillId,
                                casterId = ctx.actorId,
                                casterPosition = ctx.origin,
                            ),
                        ),
                    )
                }
            }

            is Mechanic.Heal -> {
                val amount = resolveDouble(mechanic.amount, ctx)
                log.info(
                    "Heal mechanic for {} targets, amount={} (impl deferred to Phase 6)",
                    targeterResolver.resolvePlayers(mechanic.targets, ctx).size, amount,
                )
            }

            is Mechanic.Resurrect -> {
                log.warn("Resurrect mechanic deferred to Phase 6 (no port wired)")
            }

            is Mechanic.ApplyStatus -> {
                val targets = targeterResolver.resolvePlayers(mechanic.targets, ctx)
                if (targets.isEmpty()) return
                if (applyStatus == null) {
                    log.warn("ApplyStatus mechanic dropped: ApplyStatusUseCase not wired")
                    return
                }
                val durationTicks = resolveInt(mechanic.effectDurationTicks, ctx)
                val chance = resolveDouble(mechanic.chance, ctx).coerceIn(0.0, 1.0)
                for (target in targets) {
                    if (chance < 1.0 && kotlin.random.Random.nextDouble() > chance) continue
                    applyStatus.execute(
                        targetId = target,
                        effectId = mechanic.statusId,
                        durationTicks = durationTicks,
                    )
                }
            }

            is Mechanic.RemoveStatus -> {
                log.info(
                    "RemoveStatus mechanic ({}); container.remove deferred to Phase 6",
                    mechanic.statusId,
                )
            }

            is Mechanic.Cleanse -> {
                log.info("Cleanse mechanic (onlyHarmful={}); impl deferred to Phase 6",
                    resolveBool(mechanic.onlyHarmful, ctx))
            }

            is Mechanic.Ignite,
            is Mechanic.Freeze,
            is Mechanic.Silence -> {
                log.info("{} mechanic dispatched (impl deferred to Phase 6)", mechanic::class.simpleName)
            }

            // ===== Movement ===========================================
            is Mechanic.Knockback -> {
                if (knockbackGateway == null) {
                    log.warn("Knockback mechanic dropped: IKnockbackGateway not wired")
                    return
                }
                val targets = targeterResolver.resolvePlayers(mechanic.targets, ctx)
                val magnitude = resolveDouble(mechanic.strength, ctx)
                val dir = resolveDirection(mechanic.direction, ctx)
                val impulse = dir * magnitude
                for (target in targets) {
                    knockbackGateway.applyImpulse(target, impulse)
                }
            }

            is Mechanic.Knockup -> {
                if (knockbackGateway == null) {
                    log.warn("Knockup mechanic dropped: IKnockbackGateway not wired")
                    return
                }
                val targets = targeterResolver.resolvePlayers(mechanic.targets, ctx)
                val magnitude = resolveDouble(mechanic.strength, ctx)
                for (target in targets) {
                    knockbackGateway.applyImpulse(target, Vec3(0.0, magnitude, 0.0))
                }
            }

            is Mechanic.SetVelocity,
            is Mechanic.Propel,
            is Mechanic.Leap -> {
                log.info(
                    "{} mechanic dispatched (movement port deferred to Phase 6)",
                    mechanic::class.simpleName,
                )
            }

            is Mechanic.Teleport -> {
                val targets = targeterResolver.resolvePlayers(mechanic.targets, ctx)
                val dest = resolveLocation(mechanic.destination, ctx) ?: return
                for (target in targets) {
                    worldGateway.teleport(target, dest.position, dest.world)
                }
            }

            // ===== World ==============================================
            is Mechanic.SummonLightning,
            is Mechanic.Summon,
            is Mechanic.SetBlock,
            is Mechanic.RemoveBlock,
            is Mechanic.Explode -> {
                log.info(
                    "{} mechanic dispatched (world port deferred to Phase 6)",
                    mechanic::class.simpleName,
                )
            }

            // ===== Item / inventory ===================================
            is Mechanic.GiveItem,
            is Mechanic.ConsumeItem,
            is Mechanic.ModifyAttribute,
            is Mechanic.SetCooldown -> {
                log.info(
                    "{} mechanic dispatched (inventory/attribute port deferred to Phase 6)",
                    mechanic::class.simpleName,
                )
            }

            // ===== Visual / cosmetic ==================================
            is Mechanic.SpawnParticle -> {
                val locations = targeterResolver.resolveLocations(mechanic.targets, ctx)
                if (locations.isEmpty()) return
                val count = resolveInt(mechanic.count, ctx).coerceAtLeast(1)
                val definition = particleRegistry?.find(mechanic.particleId)
                // Wave 5effects: route through IParticleScheduler if wired;
                // otherwise fall through to IWorldGateway.spawnParticle (which
                // itself may be a no-op until Wave 6).
                val particleKey = definition?.id?.value ?: mechanic.particleId.value
                val spec = ParticleSpec(key = particleKey, count = count)
                val ps = particleScheduler
                if (ps != null && ctx.worldId != null) {
                    for (loc in locations) {
                        ps.schedule(
                            ScheduledParticleSpec.EmitOnce(
                                particle = spec,
                                position = loc,
                                atTick = scheduler.currentTick(),
                                world = ctx.worldId,
                            ),
                        )
                    }
                } else {
                    if (ctx.worldId == null) {
                        log.debug("SpawnParticle {} dropped: ctx.worldId is null", mechanic.particleId)
                        return
                    }
                    for (loc in locations) {
                        worldGateway.spawnParticle(ctx.worldId, loc, spec)
                    }
                }
            }

            is Mechanic.PlaySound -> {
                val locations = targeterResolver.resolveLocations(mechanic.targets, ctx)
                if (locations.isEmpty()) return
                if (ctx.worldId == null) {
                    log.debug("PlaySound {} dropped: ctx.worldId is null", mechanic.soundId)
                    return
                }
                val definition = soundRegistry?.find(mechanic.soundId)
                val volume = resolveDouble(mechanic.volume, ctx)
                    .takeIf { it > 0.0 } ?: (definition?.defaultVolume ?: 1.0)
                val pitch = resolveDouble(mechanic.pitch, ctx)
                    .takeIf { it > 0.0 } ?: (definition?.defaultPitch ?: 1.0)
                val key = definition?.resourceKey ?: mechanic.soundId.value
                val spec = SoundSpec(
                    key = key,
                    volume = volume.coerceIn(0.0, SoundSpec.PRACTICAL_MAX_VOLUME),
                    pitch = pitch.coerceIn(SoundSpec.MIN_PITCH, SoundSpec.MAX_PITCH),
                )
                for (loc in locations) {
                    worldGateway.playSound(ctx.worldId, loc, spec)
                }
            }

            is Mechanic.SpawnDisplay -> {
                log.debug("SpawnDisplay mechanic (DisplaySpec lookup deferred to Phase 6)")
            }

            is Mechanic.ScheduleParticleAnimation,
            is Mechanic.Glow,
            is Mechanic.CameraShake -> {
                log.debug("{} mechanic dispatched (Phase 6 port wire)", mechanic::class.simpleName)
            }

            // ===== Messages ===========================================
            is Mechanic.SendMessage -> {
                if (broadcaster == null) {
                    log.debug("SendMessage dropped (IBroadcaster not wired)")
                    return
                }
                val targets = targeterResolver.resolvePlayers(mechanic.targets, ctx)
                for (target in targets) {
                    // Phase 1: best-effort; real localized routing arrives in Phase 6.
                    log.debug("SendMessage to {} (LocalizedText routing deferred)", target)
                }
            }

            is Mechanic.SendActionBar,
            is Mechanic.SendTitle -> {
                log.debug("{} mechanic dispatched (Phase 6 send-API wire)", mechanic::class.simpleName)
            }

            // ===== Control flow =======================================
            is Mechanic.CastSkill -> {
                if (ctx.depth >= MAX_CHAIN_DEPTH) {
                    log.warn(
                        "CastSkill {} dropped: max chain depth ({}) reached",
                        mechanic.skillId, MAX_CHAIN_DEPTH,
                    )
                    return
                }
                if (mechanic.skillId in ctx.castedSkills) {
                    log.warn(
                        "CastSkill {} dropped: cycle detected (already on stack)",
                        mechanic.skillId,
                    )
                    return
                }
                val nextCtx = ctx.copy(
                    depth = ctx.depth + 1,
                    castedSkills = ctx.castedSkills + mechanic.skillId,
                )
                castSkillInvoker.invoke(mechanic.skillId, mechanic.targets, nextCtx)
            }

            is Mechanic.IfThen -> {
                val branch = if (resolveCondition(mechanic.predicate, ctx)) mechanic.then_ else mechanic.else_
                dispatch(branch, ctx)
            }

            is Mechanic.Loop -> {
                val iters = resolveInt(mechanic.count, ctx).coerceAtLeast(0)
                repeat(iters) { dispatch(mechanic.mechanics, ctx.copy(tickIndex = ctx.tickIndex + it)) }
            }

            is Mechanic.Choose -> {
                if (mechanic.weighted.isEmpty()) return
                val total = mechanic.weighted.sumOf { it.weight }
                if (total <= 0.0) return
                val pick = kotlin.random.Random.nextDouble() * total
                var acc = 0.0
                for (branch in mechanic.weighted) {
                    acc += branch.weight
                    if (pick <= acc) {
                        dispatch(branch.mechanics, ctx)
                        return
                    }
                }
            }
        }
    }

    // -------- Value resolution (CEL injection points) ----------------

    private fun buildDamageProfile(components: List<DamageComponent>): DamageProfile =
        if (components.isEmpty()) {
            DamageProfile.single(0.0, DamageAttribute.PHYSICAL)
        } else {
            DamageProfile(components)
        }

    internal fun resolveDouble(value: NumericValue, ctx: MechanicContext): Double = when (value) {
        is NumericValue.Literal -> value.value
        is NumericValue.Cel -> evalCelScalar(value.script) { (it as? Number)?.toDouble() ?: 0.0 } ?: 0.0
    }

    internal fun resolveInt(value: IntValue, ctx: MechanicContext): Int = when (value) {
        is IntValue.Literal -> value.value
        is IntValue.Cel -> evalCelScalar(value.script) { (it as? Number)?.toInt() ?: 0 } ?: 0
    }

    internal fun resolveBool(value: BoolValue, ctx: MechanicContext): Boolean = when (value) {
        is BoolValue.Literal -> value.value
        is BoolValue.Cel -> evalCelScalar(value.script) { it as? Boolean ?: false } ?: false
    }

    internal fun resolveVec3(value: VectorValue, ctx: MechanicContext): Vec3 = when (value) {
        is VectorValue.Literal -> value.value
        is VectorValue.Cel -> {
            log.warn("VectorValue.Cel script '{}' not yet supported; defaulting to ZERO", value.script)
            Vec3.ZERO
        }
    }

    private fun <T> evalCelScalar(script: String, project: (Any) -> T): T? {
        return when (val r = celEvaluator.evaluate(CelSkillScript(script), emptyCelContext())) {
            is Result.Success -> project(r.value)
            is Result.Failure -> {
                log.warn("CEL script '{}' failed ({}); defaulting", script, r.error)
                null
            }
        }
    }

    private fun emptyCelContext(): CelContext = CelContext(
        player = CelContext.PlayerData(
            playerId = PlayerId(java.util.UUID(0, 0)),
            attr = SimpleAttributeContainer(),
        ),
    )

    private fun resolveDirection(direction: DirectionRef, ctx: MechanicContext): Vec3 = when (direction) {
        DirectionRef.FromCaster -> {
            // Phase 1: no look-vector source; default to +X (matches
            // ProjectileHandler.DEFAULT_DIRECTION). Phase 6 will surface
            // the caster's look vector through MechanicContext.
            Vec3(1.0, 0.0, 0.0)
        }
        DirectionRef.FromHitNormal -> Vec3(1.0, 0.0, 0.0) // Phase 1 placeholder
        DirectionRef.Up -> Vec3(0.0, 1.0, 0.0)
        is DirectionRef.Custom -> resolveVec3(direction.vector, ctx)
    }

    private fun resolveLocation(location: LocationRef, ctx: MechanicContext): ResolvedLocation? = when (location) {
        LocationRef.Caster, LocationRef.Origin -> ResolvedLocation(ctx.origin, ctx.worldId)
        LocationRef.Target -> ctx.targetId?.let { ResolvedLocation(ctx.origin, ctx.worldId) }
        is LocationRef.Absolute -> ResolvedLocation(location.position, location.world)
        is LocationRef.Offset -> {
            val base = resolveLocation(location.from, ctx) ?: return null
            val delta = resolveVec3(location.delta, ctx)
            ResolvedLocation(base.position + delta, base.world)
        }
    }

    private fun resolveCondition(condition: Condition, ctx: MechanicContext): Boolean = when (condition) {
        is Condition.Cel -> evalCelScalar(condition.script) { it as? Boolean ?: false } ?: false
        is Condition.HasStatus -> {
            // Phase 1: HasStatus needs an IStatusEffectContainer the dispatcher
            // does not yet hold. Treat as false until Phase 6 wires it.
            log.debug("Condition.HasStatus deferred to Phase 6 (returning false)")
            false
        }
        is Condition.IsAlly, is Condition.IsEnemy ->
            // No team service wired into dispatcher in Phase 1.
            false
        is Condition.HealthBelow, is Condition.DistanceLessThan ->
            // Need player attribute store; Phase 6.
            false
        is Condition.And -> resolveCondition(condition.left, ctx) && resolveCondition(condition.right, ctx)
        is Condition.Or -> resolveCondition(condition.left, ctx) || resolveCondition(condition.right, ctx)
        is Condition.Not -> !resolveCondition(condition.inner, ctx)
    }

    private data class ResolvedLocation(val position: Vec3, val world: WorldId?)

    public companion object {
        /** Max recursive depth across `CastSkill` chains. Aligns with ADR-0035 §"cycle guard". */
        public const val MAX_CHAIN_DEPTH: Int = 8

        // Phase 1 placeholder skill id used for DamageSource.Skill when a
        // Mechanic.Damage fires outside an active CastSkill chain (e.g.
        // when archetype handlers feed a top-level phase context).
        internal val FALLBACK_SKILL_ID: SkillId = SkillId("mechanic-anonymous")
    }
}

/**
 * Per-cast dispatch context — threaded through every mechanic in a phase so
 * Targeter / Condition / CEL can resolve from a stable snapshot.
 *
 * Wave _skills-2 / ADR-0077.
 */
public data class MechanicContext(
    public val actorId: PlayerId,
    public val targetId: PlayerId? = null,
    public val origin: Vec3,
    public val worldId: WorldId? = null,
    public val depth: Int = 0,
    public val castedSkills: Set<SkillId> = emptySet(),
    public val sourceItemId: ItemId? = null,
    public val tickIndex: Long = 0,
)

/**
 * Resolves a [Targeter] to a concrete `List<PlayerId>` (for damage / status /
 * knockback) or a concrete `List<Vec3>` (for particle / sound spawn points).
 *
 * Phase 1: the basic targeters (Caster / Target / Origin / HitLocation /
 * ProjectilePosition / *Offset) resolve directly from [MechanicContext];
 * the area targeters (NearbyEntities / ForwardRay / Cone) go through
 * [INearbyTargetQuery] when populated, else warn-log and return empty.
 */
internal class TargeterResolver(
    private val nearbyQuery: INearbyTargetQuery,
    private val log: Logger,
) {
    fun resolvePlayers(targeter: Targeter, ctx: MechanicContext): List<PlayerId> = when (targeter) {
        Targeter.Caster -> listOf(ctx.actorId)
        Targeter.Target -> ctx.targetId?.let { listOf(it) } ?: emptyList()
        Targeter.Origin, Targeter.HitLocation, Targeter.ProjectilePosition ->
            emptyList() // Location-only targeters; no player resolution.
        is Targeter.CasterOffset, is Targeter.TargetOffset,
        is Targeter.ProjectileOffset, is Targeter.HitOffset ->
            emptyList()
        is Targeter.NearbyEntities -> {
            val center = resolveLocationOnly(targeter.center, ctx) ?: return emptyList()
            val radius = when (val r = targeter.radius) {
                is NumericValue.Literal -> r.value
                is NumericValue.Cel -> 0.0
            }
            val found = nearbyQuery.findInRadius(center, radius, ctx.worldId)
            if (targeter.excludeCaster) found.filter { it != ctx.actorId } else found
        }
        is Targeter.ForwardRay, is Targeter.Cone -> {
            log.debug(
                "{} targeter Phase 1: returning empty (Phase 6 wire)",
                targeter::class.simpleName,
            )
            emptyList()
        }
        is Targeter.Enemies, is Targeter.Allies -> {
            log.debug("Team-filter targeter Phase 1: returning empty (team service deferred)")
            emptyList()
        }
        is Targeter.Filter -> resolvePlayers(targeter.base, ctx)
        is Targeter.Random -> {
            val base = resolvePlayers(targeter.from, ctx)
            val n = when (val c = targeter.count) {
                is IntValue.Literal -> c.value
                is IntValue.Cel -> 0
            }
            base.shuffled().take(n)
        }
    }

    fun resolveLocations(targeter: Targeter, ctx: MechanicContext): List<Vec3> {
        return when (targeter) {
            Targeter.Caster, Targeter.Origin -> listOf(ctx.origin)
            Targeter.Target, Targeter.HitLocation, Targeter.ProjectilePosition -> listOf(ctx.origin)
            is Targeter.CasterOffset -> listOf(ctx.origin + literalVec(targeter.offset))
            is Targeter.TargetOffset -> listOf(ctx.origin + literalVec(targeter.offset))
            is Targeter.ProjectileOffset -> listOf(ctx.origin + literalVec(targeter.offset))
            is Targeter.HitOffset -> listOf(ctx.origin + literalVec(targeter.offset))
            is Targeter.NearbyEntities,
            is Targeter.ForwardRay,
            is Targeter.Cone,
            is Targeter.Enemies,
            is Targeter.Allies,
            is Targeter.Filter,
            is Targeter.Random -> {
                // Resolve players → map to ctx.origin (Phase 1: no per-target
                // location plumbing; Phase 6 will surface PlayerPosition lookup).
                val players = resolvePlayers(targeter, ctx)
                if (players.isEmpty()) emptyList() else players.map { ctx.origin }
            }
        }
    }

    private fun resolveLocationOnly(targeter: Targeter, ctx: MechanicContext): Vec3? =
        resolveLocations(targeter, ctx).firstOrNull()

    private fun literalVec(v: VectorValue): Vec3 = when (v) {
        is VectorValue.Literal -> v.value
        is VectorValue.Cel -> Vec3.ZERO
    }
}
