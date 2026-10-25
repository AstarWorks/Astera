package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.world.BlockKey
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.JournalError
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: write-through persistent journal for per-position block
 * state (Wave 6c, ADR-0160).
 *
 * # Why "journal" and not "repository"
 *
 * Wave 5x shipped an [IBlockDurabilityRepository]-style port with a
 * **periodic flush + final-snapshot-on-shutdown** API
 * (`loadAll` / `upsertAll(world, Map<Vec3, BlockState>)`). In practice that
 * port was wired into the persistence plugin but never consumed by
 * `PaperBlockStateGateway` — the gateway kept all damage in a
 * `ConcurrentHashMap` and lost everything on `/restart`. The latent
 * snapshot port also widened the window of un-persisted writes to one
 * flush interval (default 30s).
 *
 * Wave 6c removes the periodic-flush port entirely (Phase 1 PoC posture:
 * forward-only break, no fallback shim — `CLAUDE.md §Pre-release posture`)
 * and replaces it with a **write-through journal**. The gateway calls
 * [save] on every mutation, [delete] when a block is broken, and [loadAll]
 * once during plugin enable to seed the in-memory tracker from the
 * persistent journal. This trades one extra DB write per damage event
 * (bounded by the Phase 1 PoC TPS, well below Postgres throughput) for a
 * "no writes lost on `/restart`" durability guarantee.
 *
 * # Lifecycle (paired with `PaperBlockStateGateway`)
 *
 * - **Plugin enable (warm-up)**: orchestrator (Phase C) calls
 *   [loadAll] for each loaded world and feeds the result into
 *   `PaperBlockStateGateway.warmUp`. Until that call lands the gateway
 *   trusts only seeded defaults — no resurrected HP from a half-loaded
 *   journal.
 * - **Mutation (write-through)**: `PaperBlockStateGateway` calls [save]
 *   on every `tracker[key] = state`. The call is **best-effort**: a
 *   [JournalError.Storage] is logged at WARN level and swallowed.
 *   Gameplay continues against the in-memory tracker so a transient
 *   Postgres outage doesn't freeze block damage; the next mutation will
 *   re-attempt with the latest state.
 * - **Break (durable removal)**: gateway calls [delete] when
 *   `tracker.remove(key)` fires after a `BlockDamageOutcome.Broken`. A
 *   delete failure is logged at WARN; the row will be ground out on the
 *   next save for that key (or never, if the block stays broken — fine,
 *   since [loadAll]'s tracker hydration will misreport at most the
 *   placement of one previously-broken block, which placement itself
 *   re-saves on the next damage event).
 *
 * # Why `Map<BlockKey, BlockState>` for [loadAll]
 *
 * Mirrors the in-memory tracker's `ConcurrentHashMap<BlockKey, BlockState>`
 * shape exactly. The Paper adapter loops the result into `tracker` on
 * warm-up with no per-entry translation. (The Wave 5x port returned
 * `Map<Vec3, BlockState>` and required the caller to re-key — wasteful
 * given Vec3 carries fractional axes that the journal cannot represent.)
 *
 * # Priority contract (ServiceRegistry)
 *
 * - Core registers `InMemoryBlockChangeJournalRepository` at priority 0
 *   as the boot-time default (vanilla / no-Postgres operators get
 *   process-local journal semantics, identical to the pre-6c gateway).
 * - Persistence registers `PostgresBlockChangeJournalRepository` at
 *   priority 100 when `ASTERA_PG_URL` is set — overlays the in-memory
 *   default per `ServiceRegistry` highest-priority-wins.
 */
public interface IBlockChangeJournalRepository {

    /**
     * Upsert a single tracked block state. Write-through; called from
     * `PaperBlockStateGateway` on every `tracker[key] = state` mutation.
     *
     * The Postgres impl uses an UPDATE-then-INSERT pattern under a single
     * Exposed transaction (cheaper than `ON CONFLICT` for the unbounded
     * dialect surface). The in-memory impl is a plain `ConcurrentHashMap`
     * put.
     *
     * Returns [JournalError.Storage] on driver / serialization failure;
     * the caller decides whether to log-and-swallow (gateway: yes) or
     * propagate (a future admin command: maybe).
     */
    public fun save(key: BlockKey, state: BlockState): Result<Unit, JournalError>

    /**
     * Load a single tracked block state, or null if no row exists for the
     * key. The gateway does NOT call this on the hot path (it consults
     * its own tracker first); [load] exists for admin queries + tests.
     */
    public fun load(key: BlockKey): Result<BlockState?, JournalError>

    /**
     * Load every tracked block state for [world]. Called once at plugin
     * enable to hydrate `PaperBlockStateGateway`'s in-memory tracker
     * before any damage event lands.
     *
     * Returns an empty map (not a failure) when no rows exist — a fresh
     * world is a normal startup state.
     */
    public fun loadAll(world: WorldId): Result<Map<BlockKey, BlockState>, JournalError>

    /**
     * Remove a tracked block state. Called when the gateway sees a
     * `BlockDamageOutcome.Broken` and removes the position from its
     * tracker — the journal mirrors the removal so a future [loadAll]
     * does not resurrect the broken block.
     *
     * Deleting a non-existent key is a no-op success (per repository
     * idempotency convention used by all other Astera persistence ports).
     */
    public fun delete(key: BlockKey): Result<Unit, JournalError>
}
