package com.astarworks.astera.combat.application.usecase.skill

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.usecase.skill.dispatch.SkillCastDispatcher
import com.astarworks.astera.combat.domain.event.CastRejectionReason
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.event.SkillCastRejected
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IEgoGauge
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ResourceError
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.event.SkillCooldownStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.PhaseOverrides
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger

/**
 * Gates a skill cast: looks up the spec, checks silence, per-skill cooldown and
 * reload state, **spends the cast's resource** (ammo / energy, WP2; ADR-0194),
 * records the cooldown via [ICooldownTracker], publishes [SkillCast], and
 * returns the spec.
 *
 * # Resource
 *
 * The cost comes from the skill (`resource_type` / `resource_cost`) and falls
 * back to the item the cast originates from (`resource_type` /
 * `resource_cost_per_use`). The pool is [IResourcePoolService] (core); a spend
 * failure rejects the cast *before* the cooldown starts, so the player can
 * retry as soon as the pool refills, and publishes [SkillCastRejected] for the
 * HUD feedback. [resourcePool] / [items] are nullable for unit tests that do
 * not care about resources.
 *
 * # Reload
 *
 * A skill with `reload_ticks` is a reload: it starts a timer; when it fires the
 * source item's ammo is refilled via [IResourcePoolService.reload]. While the
 * timer runs, ammo casts (and another reload) are rejected with
 * [CastSkillError.Reloading].
 *
 * # Cooldown
 *
 * Starting one publishes [SkillCooldownStarted] (ADR-0197) with the input [trigger] that cast it.
 *
 * [cooldownOverrideTicks] (the item binding's `cooldown_override_ticks`) wins
 * over the skill's own `cooldown_ticks` when present. Cooldowns are tracked by
 * [ICooldownTracker] keyed on [CooldownKey.Skill].
 *
 * Downstream skill effects (ApplyStatus, ApplyDamage) are triggered by
 * runtime mechanics that subscribe to [SkillCast] — they are not invoked here.
 */
public class CastSkillUseCase(
    private val skillRegistry: ISkillRegistry,
    private val ownership: com.astarworks.astera.combat.application.port.outbound.ICombatOwnership? = null,
    private val statusContainer: IStatusEffectContainer,
    private val eventBus: IDomainEventBus,
    private val cooldownTracker: ICooldownTracker,
    private val scheduler: IScheduler,
    private val resourcePool: IResourcePoolService? = null,
    private val items: IItemRegistry? = null,
    /**
     * Optional reference to the runtime [SkillCastDispatcher] (Wave _skills-2,
     * ADR-0077) so we can stash per-cast [PhaseOverrides] before publishing
     * [SkillCast]. `null` in unit tests that don't exercise overrides. The
     * dispatcher consumes (and removes) the entry on the same SkillCast event.
     *
     * Plugin wiring sets this via [bindSkillCastDispatcher] after both this
     * use case and the dispatcher exist (the dispatcher chain ultimately
     * loops back here via `MechanicDispatcher.setCastSkillInvoker`, so we
     * can't constructor-inject without a forward declaration).
     */
    private var skillCastDispatcher: SkillCastDispatcher? = null,
    /**
     * ADR-0174: source of the caster's world / eye / look direction. Stamped
     * onto [SkillCast.pose] so handlers can aim projectiles and set
     * `MechanicContext.worldId`. `null` keeps the pre-0174 behaviour (no
     * facing, no world => particles / sounds drop).
     */
    private val poseLookup: IPlayerPoseLookup? = null,
    /** ADR-0231: the E.G.O gauge a skill's `ego_cost` is checked against and spent from; `null` ignores `ego_cost`. */
    private val egoGauge: IEgoGauge? = null,
) {

    /** Tick at which each reloading player's reload completes. */
    private val reloadingUntil: MutableMap<PlayerId, Long> = java.util.concurrent.ConcurrentHashMap()

    public fun bindSkillCastDispatcher(dispatcher: SkillCastDispatcher) {
        this.skillCastDispatcher = dispatcher
    }

    public fun execute(
        casterId: PlayerId,
        skillId: SkillId,
        at: Vec3 = Vec3.ZERO,
        phaseOverrides: PhaseOverrides = PhaseOverrides.EMPTY,
        sourceItemId: ItemId? = null,
        cooldownOverrideTicks: Int? = null,
        /** Admin casts (F2-5): do not spend the skill's / item's resource (the cooldown still applies). */
        ignoreResource: Boolean = false,
        trigger: SkillTrigger? = null,
    ): Result<SkillSpec, CastSkillError> {
        ownership?.assertOwned(casterId)
        val castPose = poseLookup?.poseOf(casterId)
        if (castPose != null) com.astarworks.astera.core.application.service.observability.CombatTickBudget.enter(
            scheduler.currentTick(), com.astarworks.astera.core.application.port.outbound.regionScope(castPose.world, castPose.position))
        // 1. Look up spec
        val spec = skillRegistry.find(skillId)
            ?: return Result.failure(CastSkillError.SkillNotFound(skillId))

        // 2. Silence check
        if (statusContainer.has(casterId, SILENCE_ID)) {
            return reject(casterId, skillId, CastRejectionReason.SILENCED, CastSkillError.Silenced(casterId))
        }

        // 3. Cooldown check via ICooldownTracker
        val key = CooldownKey.Skill(skillId.value)
        if (cooldownTracker.isCoolingDown(casterId, key)) {
            val remainingTicks = cooldownTracker.remainingTicks(casterId, key)
            val remainingMs = remainingTicks * TICKS_TO_MS
            return Result.failure(CastSkillError.OnCooldown(skillId, remainingMs))
        }

        // 3b. E.G.O gate (ADR-0231): checked before anything is spent, consumed once the resource spend passed.
        val egoCost = if (ignoreResource) 0 else spec.egoCost ?: 0
        val ego = egoGauge
        if (ego != null && egoCost > 0) {
            val have = ego.value(casterId)
            if (have < egoCost) {
                return reject(
                    casterId,
                    skillId,
                    CastRejectionReason.INSUFFICIENT_EGO,
                    CastSkillError.InsufficientEgo(have = have, tried = egoCost),
                )
            }
        }

        // 4. Resource gate (WP2): reload state, then spend.
        val now = scheduler.currentTick()
        val reloading = reloadingUntil[casterId]?.let { it > now } ?: false
        val pool = resourcePool
        val sourceItem = sourceItemId?.let { items?.find(it) }
        var reloadItem: ItemId? = null
        if (pool != null && !ignoreResource) {
            pool.initialiseFor(casterId)
            if (sourceItemId != null) pool.setActiveWeapon(casterId, sourceItemId)
            if (spec.reloadTicks != null) {
                if (reloading) return reject(casterId, skillId, CastRejectionReason.RELOADING, CastSkillError.Reloading(casterId))
                val reloadable = sourceItem != null && sourceItem.resourceType == ResourceType.Ammo && sourceItem.resourceReloadable
                if (!reloadable) {
                    return reject(casterId, skillId, CastRejectionReason.NOT_RELOADABLE, CastSkillError.NotReloadable(skillId))
                }
                val ammo = pool.currentPool(casterId)?.ammo ?: 0
                if (ammo >= pool.capFor(casterId, ResourceType.Ammo).maxPool) {
                    return reject(casterId, skillId, CastRejectionReason.MAGAZINE_FULL, CastSkillError.MagazineFull(skillId))
                }
                reloadItem = sourceItemId
            } else {
                val type = spec.resourceType ?: sourceItem?.resourceType
                val cost = spec.resourceCost ?: sourceItem?.resourceCostPerUse ?: 0
                if (type != null && cost > 0) {
                    if (type == ResourceType.Ammo && reloading) {
                        return reject(casterId, skillId, CastRejectionReason.RELOADING, CastSkillError.Reloading(casterId))
                    }
                    val spend = pool.spendResource(casterId, type, cost)
                    if (TRACE) log.info("[weapons-debug] cast spend {} skill={} {}x{} -> {} tick={}", casterId, skillId.value, type, cost, spend, now)
                    if (spend is Result.Failure) {
                        val have = (spend.error as? ResourceError.Insufficient)?.available ?: 0
                        return reject(
                            casterId,
                            skillId,
                            rejectionFor(type),
                            CastSkillError.InsufficientResource(type = type, have = have, tried = cost),
                        )
                    }
                }
            }
        }

        if (ego != null && egoCost > 0) ego.spend(casterId, egoCost)

        // 5. Record cooldown (the item binding's override wins over the skill's own).
        val cooldownTicks = cooldownOverrideTicks ?: spec.cooldownTicks
        if (cooldownTicks > 0) {
            cooldownTracker.start(casterId, key, cooldownTicks.toLong())
        }

        // 5b. Reload skills: refill the source item's ammo after the reload time.
        if (reloadItem != null && pool != null && spec.reloadTicks != null) {
            val ticks = spec.reloadTicks.toLong()
            reloadingUntil[casterId] = now + ticks
            val weapon = reloadItem
            eventBus.publish(
                com.astarworks.astera.core.domain.event.ResourceReloadStarted(
                    playerId = casterId,
                    weapon = weapon,
                    durationTicks = ticks,
                    atTick = now,
                ),
            )
            scheduler.runLaterTicks(ExecutionScope.Entity(casterId.uuid), ticks) {
                reloadingUntil.remove(casterId)
                pool.reload(casterId, weapon)
            }
        }

        // 5c. ADR-0197: tell the item side / HUD that the skill is cooling down (hotbar sweep, ready cue).
        val reloadTicks = if (reloadItem != null) spec.reloadTicks else null
        if (cooldownTicks > 0 || reloadTicks != null) {
            eventBus.publish(
                SkillCooldownStarted(
                    playerId = casterId,
                    skillId = skillId,
                    sourceItemId = sourceItemId,
                    trigger = trigger,
                    durationTicks = cooldownTicks,
                    reloadTicks = reloadTicks,
                    atTick = now,
                ),
            )
        }

        // 6. Stash phase overrides for the dispatcher to consume on the same event.
        skillCastDispatcher?.setPendingOverrides(
            SkillCastDispatcher.OverrideKey(casterId, skillId),
            phaseOverrides,
        )

        // 7. Publish event
        val atTick = scheduler.currentTick()
        val pose = castPose
        // Callers without a position (charged casts) pass Vec3.ZERO — use the live feet position.
        val origin = if (at == Vec3.ZERO && pose != null) pose.position else at
        eventBus.publish(
            SkillCast(caster = casterId, skillId = skillId, at = origin, atTick = atTick, pose = pose),
        )

        com.astarworks.astera.core.application.service.observability.CombatTickBudget.overrun(scheduler.currentTick())
        return Result.success(spec)
    }

    private fun reject(
        casterId: PlayerId,
        skillId: SkillId,
        reason: CastRejectionReason,
        error: CastSkillError,
    ): Result<SkillSpec, CastSkillError> {
        if (TRACE) log.info("[weapons-debug] cast rejected {} skill={} reason={} tick={}", casterId, skillId.value, reason, scheduler.currentTick())
        eventBus.publish(SkillCastRejected(casterId, skillId, reason, scheduler.currentTick()))
        return Result.failure(error)
    }

    private fun rejectionFor(type: ResourceType): CastRejectionReason = when (type) {
        ResourceType.Ammo -> CastRejectionReason.INSUFFICIENT_AMMO
        ResourceType.Energy -> CastRejectionReason.INSUFFICIENT_ENERGY
    }

    private val log = org.slf4j.LoggerFactory.getLogger(CastSkillUseCase::class.java)

    private companion object {
        /** E2E-only trace (`ASTERA_DEBUG_WEAPONS`), quiet by default. */
        val TRACE: Boolean = System.getenv("ASTERA_DEBUG_WEAPONS") != null
        val SILENCE_ID = StatusEffectId("silence")
        const val TICKS_TO_MS: Long = 50L // 20 tps
    }
}

/** Expected failure modes of [CastSkillUseCase.execute]. */
public sealed class CastSkillError {
    public data class SkillNotFound(val skillId: SkillId) : CastSkillError()
    public data class OnCooldown(val skillId: SkillId, val remainingMs: Long) : CastSkillError()
    public data class Silenced(val casterId: PlayerId) : CastSkillError()

    /** The caster's pool of [type] held [have] but the cast costs [tried]. */
    public data class InsufficientResource(val type: ResourceType, val have: Int, val tried: Int) : CastSkillError()

    /** The caster's E.G.O gauge held [have] but the cast costs [tried] (ADR-0231). */
    public data class InsufficientEgo(val have: Int, val tried: Int) : CastSkillError()

    /** An ammo cast (or another reload) was attempted while a reload is in progress. */
    public data class Reloading(val casterId: PlayerId) : CastSkillError()

    /** A reload skill was cast from an item that has no reloadable ammo. */
    public data class NotReloadable(val skillId: SkillId) : CastSkillError()

    /** A reload skill was cast with a full magazine. */
    public data class MagazineFull(val skillId: SkillId) : CastSkillError()
}
