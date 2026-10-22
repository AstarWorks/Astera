package com.astarworks.astera.match.application.eventbus.listeners.world

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.WorldUnloading
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import org.slf4j.LoggerFactory

/**
 * On [WorldUnloading], end every match whose `MatchWorld.worldId` matches
 * the unloaded world (Wave L 5hardening-complete / ADR-0129).
 *
 * # Why this listener
 *
 * Operator-issued world unloads (`/world unload`) and Bukkit-driven world
 * teardowns (server stop, world-corruption-driven force-unload) leave any
 * match running in that world in an unrecoverable state: the world map is
 * gone, players are about to be ejected to the default world, and any
 * subsequent `IBlockStateGateway.applyDamage` / spawn / barrier op against
 * the dead world is a no-op at best, an NPE at worst.
 *
 * Ending the match for [EndReason.WorldUnloaded] **before** Bukkit
 * finalises the unload gives the standard `MatchEnded` pipeline a chance
 * to fire its cleanup chain (RestoreBlocksOnMatchEnded — irrelevant since
 * the world is going away, but the journal is still cleared for the
 * next match;
 * core `MatchInventorySnapshotListener`; betting resolution;
 * teleport-to-lobby) instead of leaving the match in a permanent ACTIVE
 * phantom state.
 *
 * # Phase split
 *
 * - **Wave L (this wave)** — domain event + listener. The event is
 *   only published in tests; production code path is dormant until
 *   the Bukkit bridge lands.
 * - **Wave 6b** — `PaperWorldUnloadAdapter` registers a Bukkit
 *   `WorldUnloadEvent` listener and publishes [WorldUnloading] before the
 *   actual unload completes.
 *
 * # Multi-match-on-same-world
 *
 * The repository's `all()` snapshot is filtered for `worldId == event.worldId`
 * so multiple concurrent matches sharing a world (Hub-style hub world hosting
 * mini-events) all end together. Sequence is repository-iteration order; each
 * `end()` is independent.
 *
 * # Failure mode
 *
 * `IMatchLifecycle.end` failure (match-not-found / match-already-ended) is
 * logged at DEBUG and treated as already-cleaned-up. A `Result.Failure` for
 * any other reason is logged at WARN; the next match in the loop still
 * runs. **The listener never re-throws** — a WorldUnloadEvent handler that
 * throws would break the unload chain and leave the world half-detached.
 */
public class EndMatchOnWorldUnloadListener(
    private val matchWorlds: IMatchWorldRepository,
    private val matchLifecycle: IMatchLifecycle,
) {

    private val log = LoggerFactory.getLogger(EndMatchOnWorldUnloadListener::class.java)

    public fun handle(event: WorldUnloading) {
        val affected = matchWorlds.all().filter { it.worldId == event.worldId }
        if (affected.isEmpty()) {
            log.debug("WorldUnloading for {}: no active match in this world; skipping.", event.worldId)
            return
        }
        log.info(
            "WorldUnloading for {}: ending {} active match(es) with reason=WorldUnloaded.",
            event.worldId,
            affected.size,
        )
        for (mw in affected) {
            val outcome = try {
                matchLifecycle.end(mw.matchId, EndReason.WorldUnloaded)
            } catch (
                @Suppress("TooGenericExceptionCaught") t: RuntimeException,
            ) {
                log.warn(
                    "IMatchLifecycle.end({}) threw during WorldUnloading handling: {} — continuing.",
                    mw.matchId,
                    t.message,
                )
                continue
            }
            when (outcome) {
                is Result.Success -> log.info("Ended match {} (world {}) cleanly.", mw.matchId, event.worldId)
                is Result.Failure -> log.debug(
                    "IMatchLifecycle.end({}) returned {} — treating as already-cleaned-up.",
                    mw.matchId,
                    outcome.error::class.simpleName,
                )
            }
        }
    }

    /**
     * Convenience for tests/wiring: filter snapshot of affected matches
     * without firing the end pipeline. Useful for assertions that only the
     * matches in the target world were selected.
     */
    public fun matchesIn(worldId: WorldId): List<com.astarworks.astera.match.domain.match.MatchWorld> =
        matchWorlds.all().filter { it.worldId == worldId }
}
