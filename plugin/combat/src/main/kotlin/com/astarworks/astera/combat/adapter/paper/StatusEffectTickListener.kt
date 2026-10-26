package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
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
 * Region-phase DoT/regen and expiry, retaining the existing 20-tick cadence.
 * Workers compute immutable magnitudes; side effects and re-reads merge on the owner.
 * Vanilla potion removals (e.g. milk) still translate to Astera StatusRemoved.
 */
public class StatusEffectTickListener(
    private val server: Server,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val container: PaperStatusEffectContainer,
    private val phase: PaperCombatPhase,
    private val grid: CombatSpatialGrid,
    private val compute: com.astarworks.astera.combat.application.service.CombatChunking,
    private val playerHealthGateway: IPlayerHealthGateway? = null,
) : Listener {

    private val lastTicks = java.util.concurrent.ConcurrentHashMap<PlayerId, Long>()
    private val ownership = PaperCombatOwnership(scheduler, grid)
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Register `this` as a Bukkit listener and start the per-second tick
     * task. Returns the scheduler handle so `AsteraCombatPlugin.onDisable`
     * can cancel — Bukkit listeners are unregistered automatically by
     * Paper's plugin manager teardown.
     */
    public fun start(plugin: JavaPlugin): IScheduler.TaskHandle {
        plugin.server.pluginManager.registerEvents(this, plugin)
        phase.tickStatuses = ::tickBatch
        return object : IScheduler.TaskHandle {
            private var cancelled = false
            override fun cancel() { cancelled = true; phase.tickStatuses = {} }
            override val isCancelled: Boolean get() = cancelled
            override fun onRetired(cb: () -> Unit) = Unit
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
    private fun tickBatch(playerIds: List<PlayerId>) {
        val now = scheduler.currentTick()
        // Compute only immutable magnitudes in workers. Re-read state in order after every side effect.
        val localPlayers = playerIds.filter { id ->
            if (ownership.deferIfForeign(id) { tickBatch(listOf(id)) }) false
            else {
                ownership.assertOwned(id)
                lastTicks.put(id, now) != now
            }
        }
        val inputs = localPlayers.flatMap { id ->
            container.activeEffects[id]?.values?.sortedBy { it.spec.id.value }?.map { id to it }.orEmpty()
        }
        val amounts = compute.map(inputs) { (_, instance) -> instance.spec.magnitude * instance.currentStacks }
        for ((index, input) in inputs.withIndex()) {
            val (playerId, snapshot) = input
            ownership.assertOwned(playerId)
            val current = container.activeEffects[playerId]?.get(snapshot.spec.id) ?: continue
            // A preceding tick effect can refresh or remove the next effect.
            val amount = if (current === snapshot) amounts[index] else current.spec.magnitude * current.currentStacks
            applyTickEffect(playerId, current, amount)
            val after = container.activeEffects[playerId]?.get(snapshot.spec.id) ?: continue
            val nextTicks = after.remainingTicks - TICK_PERIOD_TICKS.toInt()
            if (nextTicks <= 0) expire(playerId, after, now)
            else container.activeEffects[playerId]?.put(snapshot.spec.id, after.copy(remainingTicks = nextTicks))
        }
        for (id in localPlayers) if (container.activeEffects[id].isNullOrEmpty()) {
            container.activeEffects.remove(id)
            phase.removeStatus(id.uuid)
            lastTicks.remove(id)
        }
    }

    /**
     * Per-tick side effect dispatch for kinds whose semantics require
     * actually doing something every second (DoT damage, regen heal).
     * Kinds whose semantics are passive modifiers (SPEED, GRAVITY, …) do
     * nothing here — their behaviour is read by the resolver that consults
     * `IStatusEffectContainer.has(...)` / `.active(...)`.
     */
    private fun applyTickEffect(playerId: PlayerId, instance: StatusEffectInstance, amount: Double) {
        val healthGateway = playerHealthGateway ?: return
        when (instance.spec.kind) {
            StatusKind.BURN -> {
                val damage = amount
                if (damage > 0.0) {
                    healthGateway.damage(
                        playerId = playerId,
                        amount = damage,
                        source = HealthChangeSource.Status(instance.spec.id.value, instance.sourcePlayerId),
                    )
                }
            }
            StatusKind.HEAL_AMP -> {
                // Tick-amp heal: magnitude HP per tick * stacks. HEAL_REDUCE /
                // HEAL_NULL are read by the heal resolver, not ticked here.
                val healAmount = amount
                if (healAmount > 0.0) {
                    healthGateway.heal(
                        playerId = playerId,
                        amount = healAmount,
                        source = HealthChangeSource.Status(instance.spec.id.value, instance.sourcePlayerId),
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
            StatusKind.SILENCE,
            StatusKind.STACK_DEBUFF,
            StatusKind.STACK_BUFF,
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
            .filter { (_, inst) -> container.bukkitPotionTypeFor(inst.spec.kind) == removedType }
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
     * Bukkit-side removal of the vanilla potion icon for [instance]. Uses the
     * container's single [PaperStatusEffectContainer.bukkitPotionTypeFor]
     * mapping, but skips its `remove` path (which would publish
     * [StatusRemoved] — we want [StatusExpired] instead).
     */
    private fun mirrorRemoveOnPlayer(player: Player, instance: StatusEffectInstance) {
        val potionType = container.bukkitPotionTypeFor(instance.spec.kind) ?: return
        player.removePotionEffect(potionType)
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
