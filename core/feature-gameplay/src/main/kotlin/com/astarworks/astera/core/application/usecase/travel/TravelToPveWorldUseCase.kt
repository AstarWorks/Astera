package com.astarworks.astera.core.application.usecase.travel

import java.util.concurrent.CompletableFuture

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.executor
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerTraveledToPveWorld
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Teleport a player into the PvE open-world (Wave 5wireup, ADR-0116 §G13).
 *
 * # Single-server Phase 1
 *
 * Phase 1 PoC runs everything on one Paper server, so "travel to PvE" is
 * just an `IWorldGateway.teleport` to a configured PvE world id. Wave 5r
 * `IProxyMessenger` lands the cross-server transfer path later (the use case
 * stays as-is; only the wiring changes).
 *
 * # PvE world id
 *
 * Resolved from [pveWorldIdProvider]. The default wiring passes a constant
 * `"pve-overworld"` — the eventual Wave 5pve content registry will swap
 * in a real lookup, but the use case stays unchanged.
 *
 * # PvE spawn point
 *
 * Phase 1 PoC teleports to a fixed origin near `(0, 100, 0)` — high enough
 * to clear any temporary chunk landscape. Wave 5pve-content will hand the
 * use case a real spawn coordinate via a separate port; until then this
 * hard-coded value documents the intent.
 */
public class TravelToPveWorldUseCase(
    private val worldGateway: IWorldGateway,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val pveWorldIdProvider: () -> WorldId = { WorldId(DEFAULT_PVE_WORLD_ID) },
) {

    public fun travel(playerId: PlayerId): CompletableFuture<Result<Unit, TravelError>> {
        val worldId = pveWorldIdProvider()

        return worldGateway.teleportAsync(
            playerId = playerId,
            at = DEFAULT_PVE_SPAWN,
            world = worldId,
        ).thenApplyAsync({ moved ->
            if (!moved) return@thenApplyAsync Result.failure(TravelError.TeleportFailed)
            eventBus.publish(
                PlayerTraveledToPveWorld(
                    playerId = playerId,
                    worldId = worldId,
                    atTick = scheduler.currentTick(),
                ),
            )
            return@thenApplyAsync Result.success(Unit)
        }, scheduler.executor(ExecutionScope.Entity(playerId.uuid)))
    }

    public companion object {
        public const val DEFAULT_PVE_WORLD_ID: String = "pve-overworld"
        public val DEFAULT_PVE_SPAWN: Vec3 = Vec3(0.5, 100.0, 0.5)
    }
}

/**
 * Sealed failure surface common to all three travel use cases. Each variant
 * carries its own [messageKey] so the command executor can surface a single
 * i18n message without re-mapping a string.
 */
public sealed class TravelError {
    public abstract val messageKey: MessageKey

    /** Player offline / target world unknown — `IWorldGateway.teleport` returned `false`. */
    public data object TeleportFailed : TravelError() {
        override val messageKey: MessageKey = MessageKey("astera.travel.error.teleport-failed")
    }

    /** [TravelToSurvivalIslandUseCase] only — player is not in a party. */
    public data object NoParty : TravelError() {
        override val messageKey: MessageKey = MessageKey("astera.travel.error.no-party")
    }

    /**
     * [TravelToSurvivalIslandUseCase] only — island lifecycle (`create` or
     * `load`) failed; the wrapped [detail] is the lower-level error name for
     * log triage.
     */
    public data class IslandLoadFailed(public val detail: String) : TravelError() {
        override val messageKey: MessageKey = MessageKey("astera.travel.error.island-load-failed")
    }
}
