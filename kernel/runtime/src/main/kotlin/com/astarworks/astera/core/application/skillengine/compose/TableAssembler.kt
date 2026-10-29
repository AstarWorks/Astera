package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.table.Bounds
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExpansionTable
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.WriteStats
import com.astarworks.astera.core.domain.skillengine.table.phase

/**
 * Final step of the compiler: sorts rows by (tick, phase, declaration order, occurrence) (ADR-0303 11.3, skeleton delta 3).
 * Callers pass rows in declaration/occurrence order; the sort is stable, so that order breaks every remaining tie.
 * Each row list is stamped with its content seed ([RowSeeds]) before the sort, so the counter of byte-identical rows follows document order.
 */
public object TableAssembler {
    public fun sortRows(rows: List<Row>): List<Row> = rows.sortedWith(compareBy<Row>({ it.tick }, { it.action.phase.ordinal }, { spawnRank(it.action) }))

    /**
     * Inside the spawn phase the things a display can be anchored on (a frame, a projectile body) spawn first, so a rider listed before its
     * body in the document (example b: `shard` before `bolt`) still finds its parent when it spawns; declaration order breaks every other tie.
     */
    private fun spawnRank(a: Action): Int =
        if (a is Action.Spawn && (a.kind == SpawnKind.FRAME || a.kind == SpawnKind.PROJECTILE_BODY)) 0 else 1

    public fun assemble(
        skillId: String,
        sampleSeed: Long,
        params: Map<String, Any>,
        bounds: Bounds,
        states: List<StateTable>,
        subTables: Map<EventPath, SubTable>,
        writes: Map<InstanceId, WriteStats>,
        conditions: Map<String, String>,
        config: EngineConfig,
        worldRows: List<Row> = emptyList(),
    ): ExpansionTable = ExpansionTable(
        skillId = skillId,
        sampleSeed = sampleSeed,
        params = params,
        bounds = bounds,
        states = states.map { it.copy(rows = sortRows(RowSeeds.assign(it.rows))) },
        subTables = subTables.mapValues { (_, s) -> s.copy(rows = sortRows(RowSeeds.assign(s.rows))) },
        writes = writes,
        conditions = conditions,
        config = config,
        worldRows = sortRows(RowSeeds.assign(worldRows)),
    )
}
