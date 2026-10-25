package com.astarworks.astera.core.domain.survival

/**
 * Lifecycle state of a [PartyIsland] (Wave 5pve-survival / ADR-0107).
 *
 * Persisted on `party_islands.status` so the unload sweeper can re-load
 * exactly the islands that should be live without re-reading every row.
 *
 * State transitions:
 *
 * ```
 *           ┌──────────┐
 *  Active ─►│ Unloaded │─►  Archived (cold, 7d+ idle)
 *  ◄────────│          │
 *           └──────────┘
 * ```
 *
 * Phase 1 PoC closing only models `Active` / `Unloaded` end-to-end;
 * `Archived` is the cold-storage thaw target named in the idea doc but
 * deferred to Phase 4 in scope. Including it here keeps the sealed shape
 * stable and the SQL CHECK constraint complete.
 */
public enum class IslandStatus {

    /** Island world is loaded and at least one party member is online. */
    Active,

    /**
     * Island world is offline (no party leader online). Re-loadable instantly
     * — the schematic + plot rows stay live; only the runtime world handle is
     * gone. Triggered by the leader-offline detector.
     */
    Unloaded,

    /**
     * Island has been idle past the cold-storage threshold (target: 7 days)
     * and its schematic blob has been moved to cheap storage. Re-loading
     * incurs a thaw round-trip. Wave 5pve-survival foundation does not run
     * the archival job — the column is present so Phase 4 can flip rows
     * in-place without a schema migration.
     */
    Archived,
    ;

    public companion object {
        /** States that survive a [unload] / re-`load` round-trip without thaw. */
        public val HOT: Set<IslandStatus> = setOf(Active, Unloaded)
    }
}
