package com.astarworks.astera.match.application.usecase.world

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ISchematicLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.domain.match.MatchWorld
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Use case (Wave 5d): on `MatchStarted`, materialize the match's per-match
 * world by loading + placing the stage schematic, then record a [MatchWorld]
 * via [IMatchWorldRepository].
 *
 * ## Subscription
 *
 * Mirrors `SuddenDeathAcceleratorUseCase.subscribe(...)` — the use case
 * exposes a [subscribe] method that registers the handler against
 * [IDomainEventBus]; the Phase C `AsteraMatchPlugin` wiring calls it once at
 * `onEnable` and stores the returned [IDomainEventBus.Subscription] so
 * `onDisable` can cancel it.
 *
 * ## Naming policies (Phase 1 PoC)
 *
 * The schematic path is derived from the match id as
 * `match-worlds/{matchId}.schem` (placeholder until per-stage schematics
 * land in Wave 6's `PaperSchematicLoader`); the world id is derived as
 * `match-{matchId}`. Both derivations are constructor-injectable
 * (`schematicPathFor` / `worldIdFor`) so tests can pin them and a future
 * wave can extract a `MatchWorldNamingPolicy` interface without touching
 * this use case's signature.
 *
 * ## Failure policy (soft-fail)
 *
 * If the schematic file is missing or placement fails, the use case logs
 * and continues — the [MatchWorld] is still recorded so downstream features
 * (block tracking, barrier placement) have a target world id to work with,
 * and the match runs in the default / pre-existing world. Wave 6+ may
 * tighten this to fail-fast once `PaperSchematicLoader` exists and missing
 * schematics genuinely indicate a misconfiguration. No raw throwables
 * surface — Konsist enforces `Result<T, E>` at the application boundary.
 */
public class CreateMatchWorldUseCase(
    private val schematicLoader: ISchematicLoader,
    private val matchWorlds: IMatchWorldRepository,
    private val schematicPathFor: (MatchId) -> Path = ::defaultSchematicPath,
    private val worldIdFor: (MatchId) -> WorldId = ::defaultWorldId,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Subscribe to [MatchStarted] events. Returns the subscription so the
     * caller (Phase C `AsteraMatchPlugin.onDisable`) can cancel it.
     */
    public fun subscribe(bus: IDomainEventBus): IDomainEventBus.Subscription =
        bus.subscribe(MatchStarted::class) { event -> handle(event) }

    /** Visible for tests; exercised through [subscribe] in production. */
    public fun handle(event: MatchStarted) {
        val matchId = event.matchId
        val schemPath = schematicPathFor(matchId)
        val worldId = worldIdFor(matchId)

        // Load schematic; on missing/invalid, log + still record MatchWorld
        // (soft-fail policy; see kdoc above).
        val schematic = when (val loadResult = schematicLoader.load(schemPath)) {
            is Result.Success -> loadResult.value
            is Result.Failure -> {
                log.info(
                    "MatchWorld for {}: schematic unavailable at {} ({}); recording MatchWorld without placement",
                    matchId,
                    schemPath,
                    loadResult.error,
                )
                matchWorlds.save(MatchWorld(matchId, worldId, schemPath.toString()))
                return
            }
        }

        // Place schematic at origin (Vec3.ZERO). On failure, log + still
        // persist MatchWorld so downstream features have a target.
        when (val placeResult = schematicLoader.place(schematic, worldId, Vec3.ZERO)) {
            is Result.Success -> Unit
            is Result.Failure -> log.warn(
                "MatchWorld for {}: schematic placement into {} failed ({}); MatchWorld will still be recorded",
                matchId,
                worldId,
                placeResult.error,
            )
        }

        matchWorlds.save(MatchWorld(matchId, worldId, schemPath.toString()))
    }

    public companion object {
        /** Default Phase 1 schematic-path policy: `match-worlds/{matchId}.schem`. */
        public fun defaultSchematicPath(matchId: MatchId): Path =
            Paths.get("match-worlds", "${matchId.value}.schem")

        /** Default Phase 1 world-id policy: `match-{matchId}`. */
        public fun defaultWorldId(matchId: MatchId): WorldId =
            WorldId("match-${matchId.value}")
    }
}
