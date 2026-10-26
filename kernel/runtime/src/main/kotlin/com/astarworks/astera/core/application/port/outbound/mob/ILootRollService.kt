package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.loot.LootDrop
import com.astarworks.astera.core.domain.mob.loot.LootRoll
import com.astarworks.astera.core.domain.mob.loot.LootRollContext

/**
 * Outbound port: resolves a [LootTableId] to a list of [LootDrop] rows and
 * rolls each row to produce a [LootRoll] outcome (Wave 5pve-boss, ADR-0098).
 *
 * Phase 1 ships the deterministic seeded impl
 * ([com.astarworks.astera.core.application.service.mob.DeterministicLootRollService])
 * + a tiny `register(tableId, drops)` mutator so tests + plugin wiring can
 * seed a registry without YAML. Phase 4 will introduce
 * `ContentLootRollService` that loads `content/loot-tables/[id].yaml` via the
 * same substrate `MobContents` uses.
 *
 * # Deterministic seed contract
 *
 * The roll service mixes `(tableId, mobId, killer, seed)` into the random
 * draw so:
 *  - the same context + same seed produces the same drops (replay reload)
 *  - two different mob kills with the same table produce independent rolls
 *
 * The seed is **provided by the caller** rather than minted by the service.
 * This lets Wave 5v match-recording replay reconstruct boss kills bit-exact
 * by feeding back the recorded seed.
 *
 * # Why a port + service split (not just a single class)
 *
 * Splitting the port lets the lifecycle service depend on the abstraction
 * + lets Phase 4 swap impls (deterministic → content-driven) without
 * call-site changes. The deterministic impl ships in core as the Phase 1
 * default; the content-driven impl in `plugin:persistence` (when it lands)
 * can register at higher priority via [com.astarworks.astera.core.application.port.outbound.ServiceRegistry]
 * (priority 100 overrides the in-core default).
 */
public interface ILootRollService {

    /** Register a loot table (Phase 1: in-memory; Phase 4: loaded from YAML). */
    public fun registerTable(tableId: LootTableId, drops: List<LootDrop>)

    /** All rows of a registered table, or empty list if not registered. */
    public fun tableOf(tableId: LootTableId): List<LootDrop>

    /**
     * Roll the table referenced by [context].tableId, mixing in [seed] +
     * context fields to produce a deterministic [LootRoll].
     *
     * Returns [LootRoll.empty] (with the seed echoed back) if the table is
     * unknown — empty drops is a valid outcome and not an error per ADR-0085
     * §"Phase 1 Noop distributor" precedent.
     *
     * Implementations MUST publish [com.astarworks.astera.core.domain.event.LootDropped]
     * via the injected event bus before returning, so audit + replay
     * subscribers see the roll exactly once.
     */
    public fun roll(context: LootRollContext, seed: Long, atTick: Long): LootRoll
}
