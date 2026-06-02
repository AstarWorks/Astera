package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.domain.event.StatusExpired
import com.astarworks.astera.combat.domain.event.StatusRemoved
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory

/**
 * Paper-side tick + Bukkit-event glue for [PaperStatusEffectContainer]
 * (Wave 6a-N2, ADR-0155 — Combat polish).
 *
 * # Two responsibilities
 *
 *  1. **Tick decrement + DoT application** — every 20 ticks (1 second of
 *     wall clock), walk every active effect on every player tracked by
 *     [container.activeEffects], decrement `remainingTicks` by [TICK_PERIOD_TICKS],
 *     apply tick-based side effects (BURN → DoT damage via
 *     [IPlayerHealthGateway.damage], future HEAL_AMP-style ticks via
 *     [IPlayerHealthGateway.heal]), and on `remainingTicks <= 0` remove the
 *     effect + publish [StatusExpired].
 *
 *  2. **Vanilla potion REMOVED translation** — when Bukkit fires
 *     [EntityPotionEffectEvent] with action REMOVED (typical trigger: the
 *     player drank a milk bucket, which clears every active vanilla
 *     potion), translate the removed Bukkit type back to whichever Astera
 *     [StatusEffectId]s mapped onto it via
 *     [PaperStatusEffectContainer.bukkitPotionTypeFor] and remove those
 *     Astera-side too. Publishes [StatusRemoved] with reason
 *     `"vanilla-cleared"`.
 *
 * # Why a Listener-shaped adapter
 *
 * The 6a sweep deliberately kept most physiology gateways as pure
 * write-through types. Two callsites needed a Bukkit inbound hook —
 * [com.astarworks.astera.core.adapter.paper.physiology.PaperPlayerFoodGateway]
 * (Wave 6a-5, ADR-0148 §A: neutralise vanilla hunger decrement) and
 * [com.astarworks.astera.core.adapter.paper.event.BukkitEventAdapter] (Wave
 * 6a-6, ADR-0152: translate a curated set of Bukkit events into
 * DomainEvents). This listener is the third — same shape as ADR-0148 §A
 * (`Listener` + `start(plugin)` that registers the listener and returns a
 * scheduler [TaskHandle][IScheduler.TaskHandle] so `onDisable` can cancel).
 *
 * The container itself is intentionally NOT a `Listener` — keeping the
 * inbound-event surface in a separate type means the container can be
 * unit-tested without a Bukkit harness, and the tick / event-translation
 * cost lives in one focused class.
 *
 * # Threading
 *
 * The scheduler tick task runs on main (Paper's tick scheduler is
 * main-thread). `IPlayerHealthGateway.damage` / `.heal` and Bukkit potion
 * mutations are main-thread only — same contract as
 * [PaperDamageGateway]. The `onEntityPotionEffect` handler is invoked on
 * main by Bukkit. No off-main work is performed here.
 *
 * # Registration
 *
 * Construct in `AsteraCombatPlugin.onEnable` AFTER
 * [PaperStatusEffectContainer] (the listener depends on its `active`
 * map). Call [start] with the plugin instance + retain the returned
 * [IScheduler.TaskHandle] so `onDisable` can cancel the tick loop. The
 * Bukkit listener side is unregistered automatically by Paper's
 * `HandlerList.unregisterAll(plugin)` during normal teardown.
 *
 * @see com.astarworks.astera.core.adapter.paper.event.BukkitEventAdapter
 *      for the Listener + `start(plugin)` precedent (ADR-0152).
 * @see com.astarworks.astera.core.adapter.paper.physiology.PaperPlayerFoodGateway
 *      for the Listener-shaped adapter precedent inside a feature plugin
 *      (ADR-0148 §A — first Listener inside `adapter/paper/physiology/`).
 */
public class StatusEffectTickListener(
    private val server: Server,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val container: PaperStatusEffectContainer,
    private val playerHealthGateway: IPlayerHealthGateway? = null,
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Register `this` as a Bukkit listener and start the per-second tick
     * task. Returns the scheduler handle so `AsteraCombatPlugin.onDisable`
     * can cancel — Bukkit listeners are unregistered automatically by
     * Paper's plugin manager teardown.
     */
    public fun start(plugin: JavaPlugin): IScheduler.TaskHandle {
        plugin.server.pluginManager.registerEvents(this, plugin)
        return scheduler.runRepeatingTicks(
            initialDelayTicks = TICK_PERIOD_TICKS,
            periodTicks = TICK_PERIOD_TICKS,
        ) {
            tickAll()
        }
    }

    // ── Tick loop ─────────────────────────────────────────────────────────

    /**
     * Walk every tracked player's effects, decrement durations, apply
     * tick-based side effects, expire effects whose duration reached zero.
     *
     * Iteration is on a snapshot of the per-player effect ids so concurrent
     * mutation (a side-effect call that itself removes an effect) does not
     * trip the iterator.
     */
    internal fun tickAll() {
        val now = scheduler.currentTick()
        val playerIds = container.activeEffects.keys.toList()
        for (playerId in playerIds) {
            val perPlayer = container.activeEffects[playerId] ?: continue
            val effectIds = perPlayer.keys.toList()
            for (effectId in effectIds) {
                val instance = perPlayer[effectId] ?: continue
                applyTickEffect(playerId, instance)
                val nextTicks = instance.remainingTicks - TICK_PERIOD_TICKS.toInt()
                if (nextTicks <= 0) {
                    expire(playerId, instance, now)
                } else {
                    perPlayer[effectId] = instance.copy(remainingTicks = nextTicks)
                }
            }
            if (perPlayer.isEmpty()) {
                container.activeEffects.remove(playerId)
            }
        }
    }

    /**
     * Per-tick side effect dispatch for kinds whose semantics require
     * actually doing something every second (DoT damage, regen heal).
     * Kinds whose semantics are passive modifiers (SPEED, GRAVITY, …) do
     * nothing here — their behaviour is read by the resolver that consults
     * `IStatusEffectContainer.has(...)` / `.active(...)`.
     */
    private fun applyTickEffect(playerId: PlayerId, instance: StatusEffectInstance) {
        val healthGateway = playerHealthGateway ?: return
        when (instance.spec.kind) {
            StatusKind.BURN -> {
                val damage = instance.spec.magnitude * instance.currentStacks
                if (damage > 0.0) {
                    healthGateway.damage(
                        playerId = playerId,
                        amount = damage,
                        source = HealthChangeSource.Status(instance.spec.id.value),
                    )
                }
            }
            StatusKind.HEAL_AMP -> {
                // Tick-amp heal: magnitude HP per tick * stacks. HEAL_REDUCE /
                // HEAL_NULL are read by the heal resolver, not ticked here.
                val healAmount = instance.spec.magnitude * instance.currentStacks
                if (healAmount > 0.0) {
                    healthGateway.heal(
                        playerId = playerId,
                        amount = healAmount,
                        source = HealthChangeSource.Status(instance.spec.id.value),
                    )
                }
            }
            // Passive-modifier kinds: nothing per-tick.
            StatusKind.SPEED_UP,
            StatusKind.SPEED_DOWN,
            StatusKind.PARALYSIS,
            StatusKind.JUMP_DISABLED,
            StatusKind.GRAVITY_UP,
            StatusKind.GRAVITY_DOWN,
            StatusKind.CT_RATE_UP,
            StatusKind.CT_RATE_DOWN,
            StatusKind.CT_FREEZE,
            StatusKind.STAR_GAIN,
            StatusKind.STAR_GAIN_BOOST,
            StatusKind.STAR_GAIN_NULL,
            StatusKind.HEAL_REDUCE,
            StatusKind.HEAL_NULL,
            -> Unit
        }
    }

    /**
     * Remove the expired instance from the Astera-canonical map, mirror
     * the removal to Bukkit, and publish [StatusExpired]. Mirrors the
     * shape of [PaperStatusEffectContainer.remove] but publishes the
     * expiry event instead of the manual-removal event.
     */
    private fun expire(playerId: PlayerId, instance: StatusEffectInstance, atTick: Long) {
        val perPlayer = container.activeEffects[playerId] ?: return
        perPlayer.remove(instance.spec.id) ?: return
        if (perPlayer.isEmpty()) container.activeEffects.remove(playerId)

        val player = server.getPlayer(playerId.uuid)
        if (player != null) {
            mirrorRemoveOnPlayer(player, instance)
        } else {
            log.debug(
                "StatusEffectTickListener.expire: target {} offline; mirror skipped",
                playerId,
            )
        }

        eventBus.publish(
            StatusExpired(
                target = playerId,
                effectId = instance.spec.id,
                atTick = atTick,
            ),
        )
    }

    // ── Vanilla potion REMOVED translation ────────────────────────────────

    /**
     * When a vanilla potion is REMOVED out from under us (typical
     * trigger: milk bucket consumption), translate the removed Bukkit
     * potion type back to whichever Astera [StatusEffectId]s mapped onto
     * it and remove those Astera-side too.
     *
     * Runs at HIGH priority + `ignoreCancelled = true` to match the
     * physiology-gateway precedent — most gameplay plugins observe at
     * NORMAL; we want to react to actual removals only.
     *
     * APPLIED / CLEARED actions are intentionally NOT handled here:
     * Astera does not auto-adopt arbitrary vanilla potion APPLIED, and
     * CLEARED is a single coalesced multi-removal event that we cover
     * via the per-effect REMOVED stream Bukkit also fires.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onEntityPotionEffect(event: EntityPotionEffectEvent) {
        if (event.action != EntityPotionEffectEvent.Action.REMOVED) return
        val entity = event.entity as? Player ?: return
        val removedType = event.oldEffect?.type ?: return
        val playerId = PlayerId(entity.uniqueId)
        val perPlayer = container.activeEffects[playerId] ?: return

        val matches: List<StatusEffectId> = perPlayer.entries
            .filter { (_, inst) -> bukkitPotionTypeFor(inst.spec.kind) == removedType }
            .map { (id, _) -> id }
        if (matches.isEmpty()) return

        val tick = scheduler.currentTick()
        for (effectId in matches) {
            perPlayer.remove(effectId)
            eventBus.publish(
                StatusRemoved(
                    target = playerId,
                    effectId = effectId,
                    reason = "vanilla-cleared",
                    atTick = tick,
                ),
            )
        }
        if (perPlayer.isEmpty()) container.activeEffects.remove(playerId)
    }

    // ── Mirror helpers (kept in sync with PaperStatusEffectContainer) ────

    /**
     * Bukkit-side removal of the vanilla potion icon for [instance].
     * Duplicates [PaperStatusEffectContainer]'s private mirror helper so
     * the listener can expire without going back through the container's
     * `remove` path (which would publish [StatusRemoved] — we want
     * [StatusExpired] instead).
     */
    private fun mirrorRemoveOnPlayer(player: Player, instance: StatusEffectInstance) {
        val potionType = bukkitPotionTypeFor(instance.spec.kind) ?: return
        player.removePotionEffect(potionType)
    }

    /**
     * Mirror of [PaperStatusEffectContainer.bukkitPotionTypeFor] — must
     * stay consistent. Duplicated rather than exposed because the
     * container's helper is private (an internal Bukkit-mapping detail);
     * if the mapping grows the right move is to extract a separate
     * `StatusEffectBukkitMirror` helper that both classes depend on,
     * tracked under ADR-0155 follow-up.
     */
    private fun bukkitPotionTypeFor(kind: StatusKind): org.bukkit.potion.PotionEffectType? = when (kind) {
        StatusKind.SPEED_UP -> org.bukkit.potion.PotionEffectType.SPEED
        StatusKind.SPEED_DOWN -> org.bukkit.potion.PotionEffectType.SLOWNESS
        StatusKind.PARALYSIS -> org.bukkit.potion.PotionEffectType.SLOWNESS
        StatusKind.JUMP_DISABLED -> org.bukkit.potion.PotionEffectType.JUMP_BOOST
        StatusKind.BURN -> org.bukkit.potion.PotionEffectType.WITHER
        StatusKind.HEAL_AMP,
        StatusKind.HEAL_REDUCE,
        StatusKind.HEAL_NULL,
        StatusKind.STAR_GAIN,
        StatusKind.STAR_GAIN_BOOST,
        StatusKind.STAR_GAIN_NULL,
        StatusKind.GRAVITY_UP,
        StatusKind.GRAVITY_DOWN,
        StatusKind.CT_RATE_UP,
        StatusKind.CT_RATE_DOWN,
        StatusKind.CT_FREEZE,
        -> null
    }

    public companion object {
        /**
         * Tick stride between scheduler ticks. 20 ticks = 1 wall-clock
         * second; per-second cadence matches vanilla potion duration
         * granularity (vanilla potion HUD timer also counts down in
         * second-resolution) and keeps `tickAll`'s per-player workload
         * inside one main-thread frame even at high concurrent-effect
         * counts.
         */
        public const val TICK_PERIOD_TICKS: Long = 20L
    }
}
