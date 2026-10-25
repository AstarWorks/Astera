package com.astarworks.astera.combat.application.service.aoe

import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.combat.application.port.outbound.aoe.IAreaEffectGateway
import com.astarworks.astera.combat.domain.aoe.AreaEffectError
import com.astarworks.astera.combat.domain.aoe.AreaEffectHandle
import com.astarworks.astera.combat.domain.aoe.AreaEffectSpec
import com.astarworks.astera.combat.domain.event.aoe.AreaEffectDespawned
import com.astarworks.astera.combat.domain.event.aoe.AreaEffectSpawned
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import java.util.UUID

/**
 * Default in-memory binding for [IAreaEffectGateway] (Wave 5m / ADR-0088).
 *
 * Owns no Bukkit handles — schedules the per-tick callback through
 * [IScheduler.repeat], publishes spawn / despawn domain events,
 * and tracks live handles in a [linkedMapOf] keyed by [AreaEffectHandle]
 * for deterministic [all] iteration.
 *
 * **Does not** itself enumerate entities or apply the action — the
 * per-tick callback is wired through [TickAction], a single-method SPI
 * that the Wave 6a Paper impl satisfies with `World.getNearbyEntities` +
 * `ApplyDamageUseCase` / `ApplyStatusUseCase`. The InMemory default uses
 * [TickAction.Noop] so tests can assert on lifecycle without wiring the
 * whole combat pipeline.
 *
 * Backend id constant: [BACKEND_ID] = `"in-memory"`. Wave 6a's
 * `PaperAreaEffectGateway` declares its own id; mismatched
 * [AreaEffectHandle.backendId] causes [despawn] to return
 * [AreaEffectError.UnknownHandle].
 */
public class InMemoryAreaEffectGateway(
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val tickAction: TickAction = TickAction.Noop,
) : IAreaEffectGateway {

    private data class Live(
        val spec: AreaEffectSpec,
        val tickTask: IScheduler.TaskHandle,
        val despawnTask: IScheduler.TaskHandle?,
    )

    private val live: MutableMap<AreaEffectHandle, Live> = linkedMapOf()

    override fun spawn(spec: AreaEffectSpec): Result<AreaEffectHandle, AreaEffectError> {
        val handle = AreaEffectHandle(id = UUID.randomUUID(), backendId = BACKEND_ID)

        val tickTask = scheduler.repeat(regionScope(spec.world, spec.center),
            initialDelay = ticks(spec.tickIntervalTicks.toLong()),
            period = ticks(spec.tickIntervalTicks.toLong()),
        ) {
            tickAction.tick(handle, spec)
        }

        val despawnTask: IScheduler.TaskHandle? = if (spec.durationTicks > 0) {
            scheduler.run(regionScope(spec.world, spec.center), ticks(spec.durationTicks.toLong())) {
                despawnInternal(handle, AreaEffectDespawned.DespawnReason.ExpiredNaturally)
            }
        } else {
            null
        }

        live[handle] = Live(spec, tickTask, despawnTask)
        eventBus.publish(
            AreaEffectSpawned(
                handle = handle,
                world = spec.world,
                center = spec.center,
                shape = spec.shape,
                durationTicks = spec.durationTicks,
                tickIntervalTicks = spec.tickIntervalTicks,
                action = spec.effectPerTick,
                atTick = scheduler.currentTick(),
            ),
        )

        if (spec.durationTicks == 0) {
            // duration 0 = fire-once-and-despawn semantics. Spawn event
            // already published above so subscribers still see the
            // life-cycle pair before the despawn event arrives.
            tickAction.tick(handle, spec)
            despawnInternal(handle, AreaEffectDespawned.DespawnReason.ExpiredNaturally)
        }
        return Result.success(handle)
    }

    override fun despawn(handle: AreaEffectHandle): Result<Unit, AreaEffectError> {
        if (handle.backendId != BACKEND_ID) {
            return Result.failure(AreaEffectError.UnknownHandle(handle))
        }
        val removed = live[handle] ?: return Result.failure(AreaEffectError.UnknownHandle(handle))
        despawnInternal(handle, AreaEffectDespawned.DespawnReason.ExplicitDespawn)
        // despawnInternal already cancelled tasks via the live map removal path.
        @Suppress("UNUSED_VARIABLE") val drop = removed
        return Result.success(Unit)
    }

    private fun despawnInternal(
        handle: AreaEffectHandle,
        reason: AreaEffectDespawned.DespawnReason,
    ) {
        val entry = live.remove(handle) ?: return
        entry.tickTask.cancel()
        entry.despawnTask?.cancel()
        eventBus.publish(
            AreaEffectDespawned(
                handle = handle,
                reason = reason,
                atTick = scheduler.currentTick(),
            ),
        )
    }

    override fun all(): Collection<AreaEffectHandle> = live.keys.toList()

    public companion object {
        public const val BACKEND_ID: String = "in-memory"
    }
}

/**
 * SPI for the per-tick action performed by [InMemoryAreaEffectGateway].
 *
 * Decoupled so tests can record ticks without wiring the full combat
 * pipeline. Wave 6a's `PaperAreaEffectGateway` ships its own adapter
 * that translates [com.astarworks.astera.combat.domain.aoe.AreaEffectAction]
 * variants into `ApplyDamageUseCase` / `ApplyStatusUseCase` calls.
 */
public fun interface TickAction {

    public fun tick(handle: AreaEffectHandle, spec: AreaEffectSpec)

    public companion object {
        public val Noop: TickAction = TickAction { _, _ -> }
    }
}
