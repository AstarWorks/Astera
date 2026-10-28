package com.astarworks.astera.combat.platform.paper

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.event.SkillReleased
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.platform.wiring.WiringContext
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Skill engine v3 on Paper (ADR-0303). The v2 kit is gone (W1b-01); the table player is NOT wired yet: W1b-02 builds
 * `TablePlayer` over the Paper gateways, ticks it, cancels casts when a caster leaves, and starts a cast per [SkillCast].
 * Until then every [SkillCast] is reported as an error naming W1b-02 (never a silent no-op). Only the held-click
 * [SkillReleased] publisher stays, because the items plugin and the future player both consume it.
 */
internal class SkillEnginePaperRuntime(
    private val plugin: JavaPlugin,
    @Suppress("unused") private val ports: com.astarworks.astera.combat.platform.wiring.CombatPlatformPorts,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var tickHandle: IScheduler.TaskHandle? = null
    private var interruptions: org.bukkit.event.Listener? = null
    private val notWired = ConcurrentHashMap.newKeySet<String>()

    @Suppress("UNUSED_PARAMETER")
    fun start(
        ctx: WiringContext,
        applyDamage: ApplyDamageUseCase,
        applyStatus: ApplyStatusUseCase,
        statusContainer: IStatusEffectContainer,
        wired: com.astarworks.astera.combat.platform.wiring.CombatWiringResult,
    ) {
        val services = ctx.services
        val scheduler = services.getOrThrow<IScheduler>()
        val eventBus = services.getOrThrow<IDomainEventBus>()
        log.error("skill engine v3 table player is NOT WIRED (W1b-02): no skill will run; {} skills are loaded", wired.skillCatalog.ids().size)

        tickHandle = scheduler.runRepeatingTicks(ExecutionScope.Global, 1L, 1L) {
            releaseLetGoHolds(eventBus, scheduler.currentTick())
        }

        val listener = object : org.bukkit.event.Listener {
            /** A held right click re-sends every ~4 ticks; the hold ends when the clicks stop (see [releaseLetGoHolds]). */
            @org.bukkit.event.EventHandler(ignoreCancelled = false)
            fun onClick(e: org.bukkit.event.player.PlayerInteractEvent) {
                if (e.action == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || e.action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                    lastRightClick[e.player.uniqueId] = scheduler.currentTick()
                }
            }
        }
        interruptions = listener
        plugin.server.pluginManager.registerEvents(listener, plugin)

        ctx.subscriptions += eventBus.subscribe(SkillCast::class) { event ->
            val id = event.skillId.value
            if (notWired.add(id)) log.error("[skill-error] skill {} cast by {} did nothing: the v3 table player is not wired (W1b-02)", id, event.caster.uuid)
        }
    }

    /** Right-click ticks per player; a player whose clicks stopped for [HOLD_GAP_TICKS] let go: publish [SkillReleased] once. */
    private val lastRightClick = ConcurrentHashMap<java.util.UUID, Long>()

    private fun releaseLetGoHolds(eventBus: IDomainEventBus, now: Long) {
        val it = lastRightClick.entries.iterator()
        while (it.hasNext()) {
            val (caster, last) = it.next()
            if (now - last > HOLD_GAP_TICKS) {
                it.remove()
                eventBus.publish(SkillReleased(com.astarworks.astera.core.domain.player.PlayerId(caster), now))
            }
        }
    }

    private companion object {
        /** Same gap the items plugin uses to call a right click a hold (`ItemInputTriggers.HOLD_GAP_TICKS`). */
        const val HOLD_GAP_TICKS = 5L
    }

    fun stop() {
        tickHandle?.cancel()
        tickHandle = null
        interruptions?.let { org.bukkit.event.HandlerList.unregisterAll(it) }
        interruptions = null
    }
}
