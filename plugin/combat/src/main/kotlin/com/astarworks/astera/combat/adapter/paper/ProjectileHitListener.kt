package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ProjectileOrigin
import com.astarworks.astera.combat.domain.projectile.OnHitEffect
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory

/**
 * Wave 6a-N2 / ADR-0149 §C — Astera-side projectile impact listener.
 *
 * # Why this exists
 *
 * [PaperProjectileService] spawns a Bukkit Projectile entity as a *visual
 * carrier* only — every damage / status / particle / child-spawn the projectile
 * triggers is resolved by Astera's domain pipeline, not by vanilla Bukkit
 * damage maths. This listener is the bridge: it observes Bukkit's collision
 * events, looks up the Astera-side tracking entry, and routes each
 * [OnHitEffect] in `spec.onHit` through the canonical use cases.
 *
 * Pattern reference: [com.astarworks.astera.core.adapter.paper.event.BukkitEventAdapter]
 * (Wave 6a-6 / ADR-0152). That adapter cancels vanilla `EntityDamageEvent`
 * environment causes and re-routes through `IPlayerHealthGateway`. This
 * listener does the analogous re-route for Astera-spawned projectile damage
 * so vanilla armor / Protection / Resistance / Absorption math does not
 * double-count Astera's `ArmorReductionModifier` (the same `PaperDamageGateway`
 * invariant under ADR-0149 §C the gateway itself enforces in non-projectile
 * paths).
 *
 * # Roles
 *
 *  - **Role 1: onProjectileHit** — observes the projectile's terminal
 *    `ProjectileHitEvent` (entity hit or block hit). Looks up Astera tracking
 *    by the Bukkit projectile UUID; if absent, the projectile was not spawned
 *    by Astera (vanilla skeleton arrow, wandering trader, etc.) and the
 *    listener returns immediately so other plugins own the hit. If present,
 *    the spec's `onHit` list is dispatched: `DealDamage` -> ApplyDamageUseCase,
 *    `InflictStatus` -> ApplyStatusUseCase. After dispatch the tracking entry
 *    is untracked.
 *
 *  - **Role 2: onEntityDamageByEntity** — defensive cancellation. When the
 *    damager is an Astera-tracked projectile, the vanilla damage event is
 *    cancelled (the Role 1 path is authoritative). When the damager is a
 *    non-Astera projectile, the listener is silent and the core
 *    `BukkitEventAdapter` Role 2 cancels + re-routes environment intrusion as
 *    appropriate.
 *
 * # Phase 1 dispatch coverage
 *
 *  - [OnHitEffect.DealDamage] — wired end-to-end (Phase 1 minimum viable).
 *  - [OnHitEffect.InflictStatus] — wired end-to-end via [ApplyStatusUseCase].
 *  - [OnHitEffect.Explode] — Phase 6 (requires resolved damage radius +
 *    falloff against `INearbyTargetQuery` snapshot).
 *  - [OnHitEffect.SpawnChild] — Phase 6 (requires direction inheritance from
 *    the parent projectile's velocity at impact tick).
 *  - [OnHitEffect.PlaySound] / [OnHitEffect.SpawnParticle] — Phase 6 (needs
 *    Core's `IParticleScheduler` / `ISoundRegistry` injection through this
 *    listener; Phase 1 logs at debug so YAML authors can validate spec
 *    parsing without a renderer).
 *
 * # Threading
 *
 * Bukkit dispatches events on the main thread; this listener runs synchronously
 * on main and never touches asynchronous state. ApplyDamageUseCase /
 * ApplyStatusUseCase already run on main when invoked from event handlers per
 * the existing combat call-chain convention.
 *
 * # Registration
 *
 * Construct in `AsteraCombatPlugin.onEnable` after the projectile service is
 * built and call [start] with the plugin instance to wire the
 * `@EventHandler`s. No teardown handle is needed — Paper invokes
 * `HandlerList.unregisterAll(plugin)` during normal `onDisable`.
 */
public class ProjectileHitListener(
    private val projectileService: PaperProjectileService,
    private val applyDamageUseCase: ApplyDamageUseCase,
    private val applyStatusUseCase: ApplyStatusUseCase,
    private val scheduler: IScheduler,
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Register the listener on [plugin]'s event bus. Returns this so the call site can chain. */
    public fun start(plugin: JavaPlugin): ProjectileHitListener {
        plugin.server.pluginManager.registerEvents(this, plugin)
        return this
    }

    // ── Role 1: Astera-tracked projectile hit ─────────────────────────────

    @EventHandler(priority = EventPriority.LOW)
    public fun onProjectileHit(event: ProjectileHitEvent) {
        val projectile: Projectile = event.entity
        val tracking = projectileService.trackingForBukkitEntity(projectile.uniqueId) ?: return
        val hitEntity = event.hitEntity
        val hitBlockPos: Vec3? = event.hitBlock?.location?.let { Vec3(it.x, it.y, it.z) }
        val impactPos: Vec3 = when {
            hitEntity != null -> Vec3(hitEntity.location.x, hitEntity.location.y, hitEntity.location.z)
            hitBlockPos != null -> hitBlockPos
            else -> Vec3(projectile.location.x, projectile.location.y, projectile.location.z)
        }
        try {
            dispatchOnHit(tracking, hitEntity, impactPos)
        } catch (t: Throwable) {
            log.warn(
                "ProjectileHitListener.onProjectileHit: dispatch failed for projectile={} owner={}",
                tracking.id,
                tracking.ownerId,
                t,
            )
        } finally {
            // UntilHit projectiles die on impact regardless; Ticks/Distance
            // projectiles also die because the impact terminates the visual
            // carrier (vanilla removes arrow/snowball post-collision).
            projectileService.untrack(tracking.id)
        }
    }

    // ── Role 2: vanilla EntityDamageByEntity cancellation for Astera projectiles ──

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        if (damager !is Projectile) return
        // Astera-tracked projectile: cancel the vanilla damage write. The
        // canonical damage application happens in onProjectileHit via
        // ApplyDamageUseCase. Without this cancel, Bukkit's vanilla damage
        // path would apply (a) a fixed arrow/snowball damage number, and (b)
        // vanilla armor / Protection enchantment math — both of which
        // double-count against Astera's PaperDamageGateway + ArmorReductionModifier
        // pipeline (ADR-0149 §C: Astera-internal HP is source of truth).
        if (projectileService.trackingForBukkitEntity(damager.uniqueId) != null) {
            event.isCancelled = true
            return
        }
        // Non-Astera projectile (vanilla mob arrow, wandering trader, etc.).
        // The core BukkitEventAdapter Role 2 owns the environment-intrusion
        // cancel + re-route for non-Astera damage paths; this listener stays
        // silent so we do not double-handle.
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun dispatchOnHit(
        tracking: PaperProjectileService.ProjectileTracking,
        hitEntity: org.bukkit.entity.Entity?,
        impactPos: Vec3,
    ) {
        val targetPlayerId: PlayerId? = (hitEntity as? Player)?.let { PlayerId(it.uniqueId) }
        val source = DamageSource.Projectile(
            projectileId = tracking.id,
            ownerId = tracking.ownerId,
            origin = ProjectileOrigin.FromSkill(PROJECTILE_FALLBACK_SKILL_ID),
        )
        for (effect in tracking.spec.onHit) {
            when (effect) {
                is OnHitEffect.DealDamage -> {
                    if (targetPlayerId == null) {
                        // Block hits do not apply player damage. Future:
                        // route to IBlockStateGateway under
                        // DamageSource.Block when ADR-0090 wiring lands.
                        continue
                    }
                    val attempt = DamageAttempt(
                        targetId = targetPlayerId,
                        baseProfile = effect.profile,
                        source = source,
                        headshot = false,
                    )
                    val result = applyDamageUseCase.execute(attempt)
                    if (result is com.astarworks.astera.core.domain.Result.Failure) {
                        if (log.isDebugEnabled) {
                            log.debug(
                                "ProjectileHitListener.dispatchOnHit: ApplyDamage failed for projectile={} target={} error={}",
                                tracking.id,
                                targetPlayerId,
                                result.error,
                            )
                        }
                    }
                }
                is OnHitEffect.InflictStatus -> {
                    if (targetPlayerId == null) continue
                    val effectId = StatusEffectId(effect.statusRef.value)
                    // Duration converts from kotlin.time.Duration to tick count
                    // using the canonical 50 ms / tick ratio (matches the
                    // `TICK` constant in `IScheduler`).
                    val durationTicks = (effect.duration.inWholeMilliseconds / TICK_MS).toInt()
                        .coerceAtLeast(1)
                    val result = applyStatusUseCase.execute(targetPlayerId, effectId, durationTicks)
                    if (result is com.astarworks.astera.core.domain.Result.Failure) {
                        if (log.isDebugEnabled) {
                            log.debug(
                                "ProjectileHitListener.dispatchOnHit: ApplyStatus failed for projectile={} target={} ref={} error={}",
                                tracking.id,
                                targetPlayerId,
                                effect.statusRef.value,
                                result.error,
                            )
                        }
                    }
                }
                is OnHitEffect.Explode -> {
                    // Phase 6: routed through IExplosionGateway with falloff.
                    if (log.isDebugEnabled) {
                        log.debug(
                            "ProjectileHitListener.dispatchOnHit: Explode effect held for Phase 6 (projectile={}, radius={})",
                            tracking.id,
                            effect.radius,
                        )
                    }
                }
                is OnHitEffect.SpawnChild -> {
                    // Phase 6: spawn sub-projectile inheriting parent velocity
                    // tangent. Requires direction-at-impact reconstruction.
                    val unused: ProjectileSpec = effect.sub
                    if (log.isDebugEnabled) {
                        log.debug(
                            "ProjectileHitListener.dispatchOnHit: SpawnChild effect held for Phase 6 (projectile={})",
                            tracking.id,
                        )
                    }
                    @Suppress("UNUSED_VARIABLE")
                    val _phase6 = unused
                }
                is OnHitEffect.PlaySound -> {
                    if (log.isDebugEnabled) {
                        log.debug(
                            "ProjectileHitListener.dispatchOnHit: PlaySound effect held for Phase 6 (projectile={})",
                            tracking.id,
                        )
                    }
                }
                is OnHitEffect.SpawnParticle -> {
                    if (log.isDebugEnabled) {
                        log.debug(
                            "ProjectileHitListener.dispatchOnHit: SpawnParticle effect held for Phase 6 (projectile={} at={})",
                            tracking.id,
                            impactPos,
                        )
                    }
                }
            }
        }
        // Reference the scheduler so a future at-tick replay implementation
        // (deferred damage / lingering AOE tick) has the hook without a
        // constructor signature change. Tagged unused for Phase 1 silence.
        @Suppress("UNUSED_VARIABLE")
        val _tick = scheduler.currentTick()
    }

    public companion object {
        /**
         * Conversion ratio between Bukkit ticks (50 ms) and milliseconds,
         * mirroring the [com.astarworks.astera.core.application.port.outbound.TICK]
         * constant in the Core scheduler port. Duplicated here to keep the
         * combat adapter free of an `import com.astarworks.astera.core.application.port.outbound.TICK`
         * that would only be used inside this conversion site.
         */
        public const val TICK_MS: Long = 50L

        /**
         * Phase 1 placeholder skill id used to populate
         * [DamageSource.Projectile.origin]. The Projectile spawn API does not
         * yet carry the originating skill (item / spell), so this listener
         * tags every projectile-hit as `FromSkill("projectile")`. Wave 6
         * (when `ProjectileSpec` gains an originating-skill field, or
         * `PaperProjectileService.spawn` accepts an origin discriminator)
         * supersedes this constant.
         */
        public val PROJECTILE_FALLBACK_SKILL_ID: SkillId = SkillId("projectile")

        /**
         * Phase 1 placeholder item id for `ProjectileOrigin.FromItem` paths,
         * not currently selected by the dispatcher above but reserved so a
         * future caller can pick between FromItem / FromSkill without
         * importing core's `ItemId` factory ad hoc.
         */
        public val PROJECTILE_FALLBACK_ITEM_ID: ItemId = ItemId("astera:projectile")
    }
}
