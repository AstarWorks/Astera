package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.ICelEvaluator
import com.astarworks.astera.combat.application.port.outbound.IBlockEditGuard
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.ISummonGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.service.dash.DashStrikeRunner
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.skill.expression.CelContext
import com.astarworks.astera.combat.domain.skill.expression.CelSkillScript
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IParticleRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.effect.IParticleScheduler
import com.astarworks.astera.core.application.port.outbound.effect.ISoundScheduler
import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.damage.StackHolder
import com.astarworks.astera.core.domain.effect.ParticleEmitContext
import com.astarworks.astera.core.domain.effect.ParticleEmitter
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.PlannedParticle
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.effect.scheduler.ScheduledParticleSpec
import com.astarworks.astera.core.domain.effect.sound.ScheduledSoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
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
import com.astarworks.astera.core.domain.status.StatusEffectId
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
    /** ADR-0196: plays `schedule_particle_animation`; `null` in unit tests that do not need it. */
    private val particleAnimator: com.astarworks.astera.core.application.port.outbound.effect.IParticleAnimator? = null,
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
    /**
     * ADR-0174: when wired, `Mechanic.PlaySound` goes through the sound
     * scheduler (same path as particles) instead of calling
     * [IWorldGateway.playSound] directly, so the simulator can stamp it.
     */
    private val soundScheduler: ISoundScheduler? = null,
    /** ADR-0174: live positions for ForwardRay / Cone / per-target locations. */
    private val poseLookup: IPlayerPoseLookup? = null,
    /** ADR-0174: team membership for Enemies / Allies targeters (null = no team info). */
    private val teamLookup: IMatchTeamLookup? = null,
    /** WP4: status read/remove for HasStatus, Cleanse, RemoveStatus. */
    private val statusContainer: IStatusEffectContainer? = null,
    /** WP4: lets a `content/status/` entry override the built-in Freeze / Silence specs. */
    private val statusRegistry: IStatusEffectRegistry? = null,
    /** WP4: SetVelocity / Propel / Leap / Ignite / Freeze body effects (players and mobs). */
    private val entityEffects: IEntityEffectsGateway? = null,
    /** WP4: ActionBar / Title output. */
    private val feedback: IPlayerFeedbackGateway? = null,
    /** WP4: renders `LocalizedText` for SendMessage / ActionBar / Title. */
    private val messageRenderer: IMessageRenderer? = null,
    /** WP4: chat delivery for SendMessage. */
    private val playerGateway: IPlayerGateway? = null,
    /** WP4: SetCooldown. */
    private val cooldownTracker: ICooldownTracker? = null,
    /** WP4: ModifyAttribute. */
    private val attributeStore: IPlayerAttributeContainerStore? = null,
    /** WP4: lazily resolved player HP (HealthBelow, dead-target skip). */
    private val playerHealth: (() -> IPlayerHealthGateway?)? = null,
    /** WP4: lazily resolved mob HP (HealthBelow, dead-target skip). */
    private val entityHealth: (() -> IEntityDamageGateway?)? = null,
    /** WP5: lazily resolved explosion gateway (registered after the damage pipeline exists). */
    private val explosionGateway: (() -> IExplosionGateway?)? = null,
    /** WP5: lazily resolved lightning gateway. */
    private val lightningGateway: (() -> ILightningGateway?)? = null,
    /** WP5: gate for SetBlock / RemoveBlock / block-breaking Explode; null = terrain is never edited. */
    private val blockEditGuard: IBlockEditGuard? = null,
    /** WP5: SpawnDisplay. */
    private val displayGateway: IDisplayEntityGateway? = null,
    /** ADR-0220: catalog for `play_vfx`; needs [displayGateway] too, otherwise the mechanic warns and drops. */
    private val vfxRegistry: com.astarworks.astera.core.application.port.outbound.IVfxRegistry? = null,
    private val spriteFrames: com.astarworks.astera.core.domain.effect.vfx.SpriteFrames = com.astarworks.astera.core.domain.effect.vfx.SpriteFrames.NONE,
    /** WP5: Summon. */
    private val summonGateway: ISummonGateway? = null,
    /** WP5: summon ownership / allegiance bookkeeping, shared with the host's targeting guard. */
    private val summonRegistry: SummonRegistry = SummonRegistry { teamLookup },
    /** Shared random source for `chance:` rolls and weighted `Choose` branches (seedable in the simulator). */
    private val randomizer: IDamageRandomizer = KotlinRandomDamageRandomizer(),
    /** `restore_resource`: lazily resolved held-item resource pool. */
    private val resourcePool: (() -> com.astarworks.astera.core.application.port.outbound.IResourcePoolService?)? = null,
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

    internal val targeterResolver: TargeterResolver = TargeterResolver(nearbyQuery, log, poseLookup, teamLookup)

    init {
        targeterResolver.conditionGate = ::resolveCondition
    }

    private val vitals: TargetVitals? =
        if (playerHealth != null || entityHealth != null) {
            TargetVitals({ playerHealth?.invoke() }, { entityHealth?.invoke() })
        } else {
            null
        }

    private val conditions = ConditionEvaluator(
        targeters = targeterResolver,
        teamLookup = teamLookup,
        statusContainer = statusContainer,
        vitals = vitals,
        resolveDouble = { value, c -> resolveDouble(value, c) },
    )

    /** WP5 mechanics batch B (explode, lightning, blocks, display, summon, heal, glow, shake). */
    private val worldMechanics = WorldMechanics(
        host = this,
        targeters = targeterResolver,
        scheduler = scheduler,
        worldGateway = worldGateway,
        explosion = { explosionGateway?.invoke() },
        lightning = { lightningGateway?.invoke() },
        blockGuard = blockEditGuard,
        displays = displayGateway,
        summons = summonGateway,
        summonRegistry = summonRegistry,
        effects = entityEffects,
        feedback = feedback,
        messageRenderer = messageRenderer,
        teamLookup = teamLookup,
        playerHealth = { playerHealth?.invoke() },
        entityHealth = { entityHealth?.invoke() },
        living = ::living,
        log = log,
    )

    /** ADR-0220: `play_vfx` display choreographies; null unless both the display gateway and the vfx catalog are wired. */
    private val vfxMechanics: com.astarworks.astera.combat.application.service.vfx.VfxMechanics? =
        if (displayGateway != null && vfxRegistry != null) {
            com.astarworks.astera.combat.application.service.vfx.VfxMechanics(
                player = com.astarworks.astera.combat.application.service.vfx.VfxPlayer(
                    scheduler = scheduler,
                    displays = displayGateway,
                    registry = vfxRegistry,
                    sprites = spriteFrames,
                    emitParticle = ::emitVfxParticle,
                    log = log,
                ),
                targeters = targeterResolver,
                poseLookup = poseLookup,
                scheduler = scheduler,
                log = log,
            )
        } else {
            null
        }

    private fun emitVfxParticle(call: com.astarworks.astera.combat.application.service.vfx.VfxParticleCall) {
        val definition = particleRegistry?.find(call.particleId)
        val context = ParticleEmitContext(
            look = call.look,
            tangent = call.tangent,
            center = call.center,
            toward = call.toward,
            source = call.source,
        )
        val planned = definition?.let { ParticleEmitter.plan(it, call.at, context) }
            ?: listOf(PlannedParticle(call.at, ParticleSpec(key = call.particleId.value, count = 1, force = true)))
        emitPlanned(call.world, planned)
    }

    /** Spawns planned particles through [IParticleScheduler] when wired, else straight through the world gateway. */
    private fun emitPlanned(world: WorldId, planned: List<PlannedParticle>) {
        val ps = particleScheduler
        for (p in planned) {
            if (ps != null) {
                ps.schedule(ScheduledParticleSpec.EmitOnce(particle = p.spec, position = p.at, atTick = scheduler.currentTick(), world = world))
            } else {
                worldGateway.spawnParticle(world, p.at, p.spec)
            }
        }
    }

    /** `dash_strike` runtime (per-tick teleports + capsule sweep). */
    private val dashRunner = DashStrikeRunner(
        scheduler = scheduler,
        worldGateway = worldGateway,
        poseLookup = poseLookup,
        nearbyQuery = nearbyQuery,
        effects = entityEffects,
        applyStatus = applyStatus,
        statusContainer = statusContainer,
        statusRegistry = statusRegistry,
        living = ::living,
        runOnHit = { mechanics, hitCtx -> dispatch(mechanics, hitCtx) },
        log = log,
    )

    /** Undoes every temporary world change batch B made (blocks, displays, summons, glow) and ends running dashes. */
    public fun shutdown() {
        dashRunner.shutdown()
        heldUntil.clear()
        deferredMovement.clear()
        recentMovement.clear()
        vfxMechanics?.shutdown()
        worldMechanics.shutdown()
    }

    /**
     * Drops targets that are already dead: a victim killed by an earlier
     * mechanic of the same hit must not receive statuses / movement afterwards.
     */
    private fun living(ids: List<PlayerId>): List<PlayerId> =
        ids.filter { it !in killedThisDispatch && (vitals?.isAlive(it) ?: true) }

    /**
     * Targets a Damage mechanic killed during the current top-level [dispatch].
     * Needed on top of [vitals]: outside a match a dead player is revived
     * synchronously by the `PlayerKilled` listeners, so HP alone no longer says
     * "died in this hit" by the time the next mechanic runs.
     */
    private val killedThisDispatch: MutableSet<PlayerId> = HashSet()
    private var dispatchDepth: Int = 0

    /**
     * Dispatch each mechanic in [mechanics] against [ctx]. Used by archetype
     * handlers once per phase (cast / hit_entity / hit_block / miss / expire …).
     */
    public fun dispatch(mechanics: List<Mechanic>, ctx: MechanicContext) {
        dispatchDepth++
        try {
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
        } finally {
            if (--dispatchDepth == 0) killedThisDispatch.clear()
        }
    }

    private fun dispatchOne(mechanic: Mechanic, ctx: MechanicContext) {
        // 1. The condition gates each firing: immediately here, or at fire time for delayed / repeating mechanics.
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
                    fireGated(mechanic, livePose(mechanic, ctx))
                }
                if (duration > 0) {
                    scheduler.runLater(ticks((delay + duration).toLong())) { handle.cancel() }
                }
            }
            delay > 0 -> {
                scheduler.runLater(ticks(delay.toLong())) { fireGated(mechanic, livePose(mechanic, ctx)) }
            }
            else -> {
                fireGated(mechanic, ctx)
            }
        }
    }

    /** Runs [mechanic] if its `condition` holds against [ctx] at THIS moment (cast time or fire time). */
    private fun fireGated(mechanic: Mechanic, ctx: MechanicContext) {
        val gate = mechanic.condition
        if (gate != null && !resolveCondition(gate, ctx)) return
        execute(mechanic, ctx)
    }

    // ---- hit_stop vs. movement ordering -------------------------------------------------------

    /** Tick at which a hit-stopped entity is released. */
    private val heldUntil: MutableMap<PlayerId, Long> = HashMap()

    /** Movement impulses applied to a held entity, replayed on release. */
    private val deferredMovement: MutableMap<PlayerId, MutableList<() -> Unit>> = HashMap()

    /** Movement applied this tick per entity, so a hit_stop that follows can defer it instead of wiping it. */
    private val recentMovement: MutableMap<PlayerId, Pair<Long, MutableList<() -> Unit>>> = HashMap()

    /**
     * Applies a knockback / knockup / propel [op] now, unless [target] is hit-stopped, in which case it
     * is deferred and applied on release. Remembered for the current tick so a later hit_stop in the
     * same tick defers it too (authors can order the two freely).
     */
    private fun applyMovement(target: PlayerId, op: () -> Unit) {
        val now = scheduler.currentTick()
        if ((heldUntil[target] ?: Long.MIN_VALUE) > now) {
            deferredMovement.getOrPut(target) { ArrayList() } += op
            return
        }
        op()
        val recent = recentMovement[target]
        if (recent == null || recent.first != now) recentMovement[target] = now to mutableListOf(op) else recent.second += op
    }

    private fun holdEntity(effects: IEntityEffectsGateway, id: PlayerId, ticks: Int) {
        val now = scheduler.currentTick()
        val sameTick = recentMovement.remove(id)?.takeIf { it.first == now }?.second.orEmpty()
        effects.hold(id, ticks) // zeroes velocity: re-apply this tick's movement after the release
        if (sameTick.isNotEmpty()) deferredMovement.getOrPut(id) { ArrayList() } += sameTick
        val release = now + ticks
        heldUntil[id] = maxOf(heldUntil[id] ?: 0L, release)
        scheduler.runLater(ticks(ticks.toLong())) {
            if ((heldUntil[id] ?: Long.MAX_VALUE) <= scheduler.currentTick()) {
                heldUntil.remove(id)
                deferredMovement.remove(id)?.forEach { it() }
            }
        }
    }

    /**
     * Delayed / repeating executions re-read the caster's CURRENT feet position and look so
     * geometric targeters follow a dash. Skipped for `snapshot_pose: true`, for contexts without a
     * cast pose, and for projectile-impact contexts (`hitLocation` set), which anchor on the impact.
     */
    internal fun livePose(mechanic: Mechanic, ctx: MechanicContext): MechanicContext {
        if (mechanic.snapshotPose || ctx.pose == null || ctx.hitLocation != null) return ctx
        val live = poseLookup?.poseOf(ctx.actorId) ?: return ctx
        return ctx.copy(origin = live.position, pose = live, worldId = ctx.worldId ?: live.world)
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
                val sourceSkillId = ctx.castedSkills.firstOrNull() ?: FALLBACK_SKILL_ID
                // per_stack.consume: stacks counted before the hit, removed once every target has been hit.
                val consumed = LinkedHashMap<Pair<PlayerId, StatusEffectId>, Int>()
                val ignoreInvuln = resolveBool(mechanic.ignoreInvulnerability, ctx)
                for (target in targets) {
                    val outcome = applyDamage.execute(
                        DamageAttempt(
                            targetId = target,
                            baseProfile = buildDamageProfile(mechanic.components, target, ctx, consumed),
                            source = DamageSource.Skill(
                                skillId = sourceSkillId,
                                casterId = ctx.actorId,
                                casterPosition = ctx.origin,
                            ),
                            ignoreInvulnerability = ignoreInvuln,
                        ),
                    )
                    if (outcome is Result.Success && outcome.value is DamageOutcome.Killed) {
                        killedThisDispatch += target
                    }
                }
                for ((key, stacks) in consumed) statusContainer?.removeStacks(key.first, key.second, stacks)
            }

            is Mechanic.Heal -> worldMechanics.heal(mechanic, ctx)

            is Mechanic.Resurrect -> {
                log.warn("Resurrect mechanic deferred to Phase 6 (no port wired)")
            }

            is Mechanic.ApplyStatus -> {
                val targets = living(targeterResolver.resolvePlayers(mechanic.targets, ctx))
                if (targets.isEmpty()) return
                if (applyStatus == null) {
                    log.warn("ApplyStatus mechanic dropped: ApplyStatusUseCase not wired")
                    return
                }
                val durationTicks = resolveInt(mechanic.effectDurationTicks, ctx)
                val chance = resolveDouble(mechanic.chance, ctx).coerceIn(0.0, 1.0)
                val stacks = resolveInt(mechanic.stackCount, ctx).coerceAtLeast(1)
                for (target in targets) {
                    if (chance < 1.0 && randomizer.nextDouble() > chance) continue
                    applyStatus.execute(
                        targetId = target,
                        effectId = mechanic.statusId,
                        durationTicks = durationTicks,
                        sourceId = ctx.actorId,
                        stacks = stacks,
                    )
                }
            }

            is Mechanic.RemoveStatus -> {
                val container = statusContainer ?: return warnMissing("RemoveStatus", "IStatusEffectContainer")
                val stacks = mechanic.stacks?.let { resolveInt(it, ctx) }
                for (target in targeterResolver.resolvePlayers(mechanic.targets, ctx)) {
                    when {
                        stacks == null -> container.remove(target, mechanic.statusId)
                        stacks > 0 -> container.removeStacks(target, mechanic.statusId, stacks)
                    }
                }
            }

            is Mechanic.Cleanse -> {
                val onlyHarmful = resolveBool(mechanic.onlyHarmful, ctx)
                for (target in targeterResolver.resolvePlayers(mechanic.targets, ctx)) {
                    statusContainer?.active(target)
                        ?.filter { !onlyHarmful || it.spec.kind.harmful }
                        ?.forEach { statusContainer.remove(target, it.spec.id) }
                    // Fire and frost are harmful by definition, so they go either way.
                    entityEffects?.setFireTicks(target, 0)
                    entityEffects?.thaw(target)
                }
            }

            is Mechanic.Ignite -> {
                val effects = entityEffects ?: return warnMissing("Ignite", "IEntityEffectsGateway")
                val burnTicks = resolveInt(mechanic.ticks, ctx).coerceAtLeast(0)
                for (target in living(targeterResolver.resolvePlayers(mechanic.targets, ctx))) {
                    effects.setFireTicks(target, burnTicks)
                }
            }

            is Mechanic.Freeze -> {
                val freezeTicks = resolveInt(mechanic.ticks, ctx).coerceAtLeast(0)
                val spec = statusRegistry?.find(MechanicStatuses.FREEZE_ID) ?: MechanicStatuses.FREEZE
                for (target in living(targeterResolver.resolvePlayers(mechanic.targets, ctx))) {
                    applyStatus?.applySpec(target, spec, freezeTicks)
                    entityEffects?.freeze(target, freezeTicks)
                }
                if (applyStatus == null && entityEffects == null) warnMissing("Freeze", "ApplyStatusUseCase")
            }

            is Mechanic.Silence -> {
                val status = applyStatus ?: return warnMissing("Silence", "ApplyStatusUseCase")
                val silenceTicks = resolveInt(mechanic.ticks, ctx).coerceAtLeast(0)
                val spec = statusRegistry?.find(MechanicStatuses.SILENCE_ID) ?: MechanicStatuses.SILENCE
                for (target in living(targeterResolver.resolvePlayers(mechanic.targets, ctx))) {
                    status.applySpec(target, spec, silenceTicks)
                }
            }

            // ===== Movement ===========================================
            is Mechanic.Knockback -> {
                if (knockbackGateway == null) {
                    log.warn("Knockback mechanic dropped: IKnockbackGateway not wired")
                    return
                }
                val targets = living(targeterResolver.resolvePlayers(mechanic.targets, ctx))
                val magnitude = resolveDouble(mechanic.strength, ctx)
                for (target in targets) {
                    val impulse = resolveDirection(mechanic.direction, ctx, target) * magnitude
                    applyMovement(target) { knockbackGateway.applyImpulse(target, impulse) }
                }
            }

            is Mechanic.Knockup -> {
                if (knockbackGateway == null) {
                    log.warn("Knockup mechanic dropped: IKnockbackGateway not wired")
                    return
                }
                val targets = living(targeterResolver.resolvePlayers(mechanic.targets, ctx))
                val magnitude = resolveDouble(mechanic.strength, ctx)
                for (target in targets) {
                    applyMovement(target) { knockbackGateway.applyImpulse(target, Vec3(0.0, magnitude, 0.0)) }
                }
            }

            is Mechanic.SetVelocity -> {
                val effects = entityEffects ?: return warnMissing("SetVelocity", "IEntityEffectsGateway")
                val magnitude = resolveDouble(mechanic.magnitude, ctx)
                for (target in living(targeterResolver.resolvePlayers(mechanic.targets, ctx))) {
                    effects.setVelocity(target, resolveDirection(mechanic.direction, ctx, target) * magnitude)
                }
            }

            is Mechanic.Propel -> {
                val effects = entityEffects ?: return warnMissing("Propel", "IEntityEffectsGateway")
                val strength = resolveDouble(mechanic.strength, ctx)
                for (target in living(targeterResolver.resolvePlayers(mechanic.targets, ctx))) {
                    val delta = resolveDirection(mechanic.direction, ctx, target) * strength
                    applyMovement(target) { effects.addVelocity(target, delta) }
                }
            }

            is Mechanic.Leap -> {
                val effects = entityEffects ?: return warnMissing("Leap", "IEntityEffectsGateway")
                val dest = resolveLocation(mechanic.destination, ctx) ?: return
                val arc = resolveDouble(mechanic.arcHeight, ctx)
                for (target in living(targeterResolver.resolvePlayers(mechanic.targets, ctx))) {
                    val from = positionOf(target, ctx)
                    effects.setVelocity(target, LeapPhysics.velocity(from, dest.position, arc))
                }
            }

            is Mechanic.Teleport -> {
                val targets = targeterResolver.resolvePlayers(mechanic.targets, ctx)
                val dest = resolveLocation(mechanic.destination, ctx) ?: return
                for (target in targets) {
                    worldGateway.teleport(target, dest.position, dest.world)
                }
            }

            is Mechanic.DashStrike -> {
                dashRunner.start(
                    caster = ctx.actorId,
                    params = DashStrikeRunner.Params(
                        distance = resolveDouble(mechanic.distance, ctx),
                        ticks = resolveInt(mechanic.ticks, ctx),
                        radius = resolveDouble(mechanic.radius, ctx).coerceAtLeast(0.0),
                        hitOnce = resolveBool(mechanic.hitOnce, ctx),
                        invulnerable = resolveBool(mechanic.invulnerable, ctx),
                        stopOnBlock = resolveBool(mechanic.stopOnBlock, ctx),
                        followPitch = resolveBool(mechanic.followPitch, ctx),
                        stopBeforeTarget = mechanic.stopBeforeTarget,
                        onHit = mechanic.onHit,
                    ),
                    ctx = ctx,
                )
            }

            is Mechanic.RestoreResource -> {
                val pool = resourcePool?.invoke() ?: return warnMissing("RestoreResource", "IResourcePoolService")
                val amount = resolveInt(mechanic.amount, ctx).coerceAtLeast(0)
                if (amount == 0) return
                for (target in targeterResolver.resolvePlayers(mechanic.targets, ctx)) {
                    pool.applyRegen(target, mechanic.resource, amount)
                }
            }

            is Mechanic.HitStop -> {
                val effects = entityEffects ?: return warnMissing("HitStop", "IEntityEffectsGateway")
                val holdTicks = resolveInt(mechanic.ticks, ctx).coerceAtLeast(0)
                if (holdTicks == 0) return
                val held = LinkedHashSet(living(targeterResolver.resolvePlayers(mechanic.targets, ctx)))
                if (resolveBool(mechanic.includeCaster, ctx)) held += ctx.actorId
                for (id in held) holdEntity(effects, id, holdTicks)
            }

            // ===== World ==============================================
            is Mechanic.SummonLightning -> worldMechanics.lightning(mechanic, ctx)
            is Mechanic.Summon -> worldMechanics.summon(mechanic, ctx)
            is Mechanic.SetBlock -> worldMechanics.setBlock(mechanic, ctx)
            is Mechanic.RemoveBlock -> worldMechanics.removeBlock(mechanic, ctx)
            is Mechanic.Explode -> worldMechanics.explode(mechanic, ctx)

            // ===== Item / inventory ===================================
            is Mechanic.GiveItem,
            is Mechanic.ConsumeItem -> {
                log.info(
                    "{} mechanic dispatched (inventory port deferred to Phase 6)",
                    mechanic::class.simpleName,
                )
            }

            is Mechanic.ModifyAttribute -> {
                val store = attributeStore ?: return warnMissing("ModifyAttribute", "IPlayerAttributeContainerStore")
                val key = runCatching { AttributeKey(mechanic.attributeKey) }.getOrNull()
                if (key == null) {
                    log.warn("ModifyAttribute dropped: invalid attribute key '{}'", mechanic.attributeKey)
                    return
                }
                val delta = resolveDouble(mechanic.delta, ctx)
                val lifetime = resolveInt(mechanic.ticks, ctx).coerceAtLeast(0)
                for (target in living(targeterResolver.resolvePlayers(mechanic.targets, ctx))) {
                    val container = store.containerFor(target)
                    val source = "mechanic:modify_attribute:${java.util.UUID.randomUUID()}"
                    container.addModifier(AttributeModifier(key, AttributeModifier.Operation.ADD, delta, source))
                    // ticks = 0 keeps the modifier for good (until the container is rebuilt).
                    if (lifetime > 0) {
                        scheduler.runLater(ticks(lifetime.toLong())) { container.removeModifiersBySource(source) }
                    }
                }
            }

            is Mechanic.SetCooldown -> {
                val tracker = cooldownTracker ?: return warnMissing("SetCooldown", "ICooldownTracker")
                val cooldown = resolveInt(mechanic.cooldownTicks, ctx).coerceAtLeast(0)
                val key = CooldownKey.Skill(mechanic.skillId.value)
                for (target in targeterResolver.resolvePlayers(mechanic.targets, ctx)) {
                    if (cooldown == 0) tracker.reset(target, key) else tracker.start(target, key, cooldown.toLong())
                }
            }

            // ===== Visual / cosmetic ==================================
            is Mechanic.SpawnParticle -> {
                val locations = targeterResolver.resolveLocations(mechanic.targets, ctx)
                if (locations.isEmpty()) return
                val count = resolveInt(mechanic.count, ctx).coerceAtLeast(1)
                val definition = particleRegistry?.find(mechanic.particleId)
                val world = ctx.worldId ?: run {
                    log.debug("SpawnParticle {} dropped: ctx.worldId is null", mechanic.particleId)
                    return
                }
                // A vanilla-mapped definition owns its count / spread / colour / motion; a mechanic count > 1
                // overrides the count. Routed through IParticleScheduler when wired.
                val look = ctx.pose?.direction?.takeIf { it.lengthSquared() > 1e-9 }
                for (loc in locations) {
                    val planned = definition?.let {
                        ParticleEmitter.plan(
                            it,
                            loc,
                            ParticleEmitContext(look = look, source = ctx.actorId),
                            countOverride = count.takeIf { c -> c > 1 },
                        )
                    } ?: listOf(PlannedParticle(loc, ParticleSpec(key = mechanic.particleId.value, count = count)))
                    emitPlanned(world, planned)
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
                val ss = soundScheduler
                for (loc in locations) {
                    if (ss != null) {
                        ss.schedule(
                            ScheduledSoundSpec.EmitOnce(
                                sound = spec,
                                position = loc,
                                atTick = scheduler.currentTick(),
                                world = ctx.worldId,
                            ),
                        )
                    } else {
                        worldGateway.playSound(ctx.worldId, loc, spec)
                    }
                }
            }

            is Mechanic.SpawnDisplay -> worldMechanics.display(mechanic, ctx)
            is Mechanic.PlayVfx -> {
                val vfx = vfxMechanics ?: return warnMissing("PlayVfx", "IVfxRegistry / IDisplayEntityGateway")
                vfx.play(mechanic, ctx, resolveDouble(mechanic.scale, ctx), mechanic.follow?.let { resolveBool(it, ctx) }, mechanic.freeze)
            }
            is Mechanic.Glow -> worldMechanics.glow(mechanic, ctx)
            is Mechanic.HideHeldItem -> worldMechanics.hideHeldItem(mechanic, ctx)
            is Mechanic.CameraShake -> worldMechanics.cameraShake(mechanic, ctx)

            is Mechanic.ScheduleParticleAnimation -> {
                val animator = particleAnimator ?: return warnMissing("ScheduleParticleAnimation", "IParticleAnimator")
                val world = ctx.worldId ?: run {
                    log.debug("ScheduleParticleAnimation dropped: ctx.worldId is null")
                    return
                }
                val direction = ctx.pose?.direction?.takeIf { it.lengthSquared() > 0.0 } ?: Vec3(1.0, 0.0, 0.0)
                for (loc in targeterResolver.resolveLocations(mechanic.targets, ctx)) {
                    animator.play(world, loc, direction, mechanic.animation)
                }
            }

            // ===== Messages ===========================================
            is Mechanic.SendMessage -> {
                val renderer = messageRenderer ?: return warnMissing("SendMessage", "IMessageRenderer")
                val gateway = playerGateway ?: return warnMissing("SendMessage", "IPlayerGateway")
                for (target in targeterResolver.resolvePlayers(mechanic.targets, ctx)) {
                    gateway.sendMessage(target, renderer.renderText(target, mechanic.message))
                }
            }

            is Mechanic.SendActionBar -> {
                val renderer = messageRenderer ?: return warnMissing("SendActionBar", "IMessageRenderer")
                val out = feedback ?: return warnMissing("SendActionBar", "IPlayerFeedbackGateway")
                for (target in targeterResolver.resolvePlayers(mechanic.targets, ctx)) {
                    out.sendActionBar(target, renderer.renderText(target, mechanic.message))
                }
            }

            is Mechanic.SendTitle -> {
                val renderer = messageRenderer ?: return warnMissing("SendTitle", "IMessageRenderer")
                val out = feedback ?: return warnMissing("SendTitle", "IPlayerFeedbackGateway")
                val fadeIn = resolveInt(mechanic.fadeInTicks, ctx).coerceAtLeast(0)
                val stay = resolveInt(mechanic.stayTicks, ctx).coerceAtLeast(0)
                val fadeOut = resolveInt(mechanic.fadeOutTicks, ctx).coerceAtLeast(0)
                for (target in targeterResolver.resolvePlayers(mechanic.targets, ctx)) {
                    out.sendTitle(
                        target = target,
                        title = renderer.renderText(target, mechanic.title),
                        subtitle = mechanic.subtitle?.let { renderer.renderText(target, it) },
                        fadeInTicks = fadeIn,
                        stayTicks = stay,
                        fadeOutTicks = fadeOut,
                    )
                }
            }

            is Mechanic.ScreenFlash -> {
                val out = feedback ?: return warnMissing("ScreenFlash", "IPlayerFeedbackGateway")
                val fadeIn = resolveInt(mechanic.fadeInTicks, ctx).coerceAtLeast(0)
                val stay = resolveInt(mechanic.stayTicks, ctx).coerceAtLeast(0)
                val fadeOut = resolveInt(mechanic.fadeOutTicks, ctx).coerceAtLeast(0)
                val color = mechanic.colorRgb ?: mechanic.overlay.defaultColor
                for (target in targeterResolver.resolvePlayers(mechanic.targets, ctx)) {
                    out.sendScreenFlash(target, mechanic.overlay, color, fadeIn, stay, fadeOut)
                }
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
                val stride = resolveInt(mechanic.strideTicks, ctx).coerceAtLeast(0)
                repeat(iters) { i ->
                    val iterCtx = ctx.copy(tickIndex = ctx.tickIndex + i)
                    // Iteration i runs i * stride ticks after the loop fires (stride 0 = all at once).
                    if (stride > 0 && i > 0) {
                        scheduler.runLater(ticks((i.toLong() * stride))) { dispatch(mechanic.mechanics, iterCtx) }
                    } else {
                        dispatch(mechanic.mechanics, iterCtx)
                    }
                }
            }

            is Mechanic.Choose -> {
                if (mechanic.weighted.isEmpty()) return
                val total = mechanic.weighted.sumOf { it.weight }
                if (total <= 0.0) return
                val pick = randomizer.nextDouble() * total
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

    /**
     * Runs [perTarget] for each of [targets] with [profile] folded for that target (`per_stack` bonuses
     * read from the caster / target status stacks), then consumes the counted stacks once every target
     * has been handled. Used by archetype handlers whose `params.damage` honours `per_stack` too.
     */
    public fun forEachWithPerStack(
        targets: List<PlayerId>,
        profile: DamageProfile,
        ctx: MechanicContext,
        perTarget: (PlayerId, DamageProfile) -> Unit,
    ) {
        val consumed = LinkedHashMap<Pair<PlayerId, StatusEffectId>, Int>()
        for (target in targets) {
            perTarget(target, buildDamageProfile(profile.components, target, ctx, consumed))
        }
        for ((key, stacks) in consumed) statusContainer?.removeStacks(key.first, key.second, stacks)
    }

    private fun buildDamageProfile(
        components: List<DamageComponent>,
        target: PlayerId,
        ctx: MechanicContext,
        consumed: MutableMap<Pair<PlayerId, StatusEffectId>, Int>,
    ): DamageProfile =
        if (components.isEmpty()) {
            DamageProfile.single(0.0, DamageAttribute.PHYSICAL)
        } else {
            DamageProfile(components.map { foldPerStack(it, target, ctx, consumed) })
        }

    /**
     * Folds `per_stack` into the component's base for [target]: `final += amount x stacks`, where
     * the bonus lands after the component multiplier (so it is divided by it here).
     */
    private fun foldPerStack(
        component: DamageComponent,
        target: PlayerId,
        ctx: MechanicContext,
        consumed: MutableMap<Pair<PlayerId, StatusEffectId>, Int>,
    ): DamageComponent {
        val bonus = component.perStack ?: return component
        val holder = if (bonus.on == StackHolder.CASTER) ctx.actorId else target
        val stacks = statusContainer?.stacks(holder, bonus.statusId) ?: 0
        if (stacks <= 0) return component.copy(perStack = null)
        if (bonus.consume) consumed.putIfAbsent(holder to bonus.statusId, stacks)
        val multiplier = resolveDouble(component.multiplier, ctx)
        val extra = bonus.amount * stacks
        val base = resolveDouble(component.base, ctx) + if (multiplier > 0.0) extra / multiplier else extra
        return component.copy(base = NumericValue.Literal(base), perStack = null)
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

    private fun resolveDirection(direction: DirectionRef, ctx: MechanicContext, target: PlayerId? = null): Vec3 = when (direction) {
        is DirectionRef.AwayFromCaster -> RadialDirection.compute(
            ctx.origin, target?.let { positionOf(it, ctx) }, ctx.pose?.direction, direction.vertical, toward = false,
        )
        is DirectionRef.TowardCaster -> RadialDirection.compute(
            ctx.origin, target?.let { positionOf(it, ctx) }, ctx.pose?.direction, direction.vertical, toward = true,
        )
        // ADR-0174: caster look vector from MechanicContext.pose; +X when unknown.
        DirectionRef.FromCaster -> ctx.pose?.direction ?: Vec3(1.0, 0.0, 0.0)
        DirectionRef.FromHitNormal -> Vec3(1.0, 0.0, 0.0) // Phase 1 placeholder
        DirectionRef.Up -> Vec3(0.0, 1.0, 0.0)
        is DirectionRef.Custom -> resolveVec3(direction.vector, ctx)
    }

    private fun resolveLocation(location: LocationRef, ctx: MechanicContext): ResolvedLocation? = when (location) {
        LocationRef.Caster, LocationRef.Origin -> ResolvedLocation(ctx.origin, ctx.worldId)
        LocationRef.Target -> ctx.targetId?.let { ResolvedLocation(targeterResolver.targetLocation(ctx), ctx.worldId) }
        is LocationRef.Absolute -> ResolvedLocation(location.position, location.world)
        is LocationRef.Offset -> {
            val base = resolveLocation(location.from, ctx) ?: return null
            val delta = resolveVec3(location.delta, ctx)
            ResolvedLocation(base.position + delta, base.world)
        }
    }

    private fun resolveCondition(condition: Condition, ctx: MechanicContext): Boolean = when (condition) {
        is Condition.Cel -> evalCelScalar(condition.script) { it as? Boolean ?: false } ?: false
        is Condition.And -> resolveCondition(condition.left, ctx) && resolveCondition(condition.right, ctx)
        is Condition.Or -> resolveCondition(condition.left, ctx) || resolveCondition(condition.right, ctx)
        is Condition.Not -> !resolveCondition(condition.inner, ctx)
        is Condition.HasStatus,
        is Condition.IsAlly,
        is Condition.IsEnemy,
        is Condition.HealthBelow,
        is Condition.DistanceLessThan -> conditions.evaluate(condition, ctx) ?: false
    }

    /** Live feet position of [id] (pose lookup), else the cast origin. */
    private fun positionOf(id: PlayerId, ctx: MechanicContext): Vec3 =
        poseLookup?.poseOf(id)?.position ?: ctx.origin

    private fun warnMissing(mechanic: String, port: String) {
        log.warn("{} mechanic dropped: {} not wired", mechanic, port)
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
    /**
     * Caster pose at cast time (ADR-0174): look direction for `FromCaster`
     * direction / ForwardRay / Cone, eye position for ray origins.
     */
    public val pose: PlayerPose? = null,
    /**
     * Impact point for projectile hit / miss / expire phases. `HitLocation`,
     * `ProjectilePosition` and their offsets anchor here; falls back to [origin].
     */
    public val hitLocation: Vec3? = null,
)
