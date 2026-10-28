package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.table.Bounds
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExpansionTable
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.WriteStats
import com.astarworks.astera.core.domain.skillengine.table.phase

/**
 * Final step of the compiler: sorts rows by (tick, phase, declaration order, occurrence) (ADR-0303 11.3, skeleton delta 3).
 * Callers pass rows in declaration/occurrence order; the sort is stable, so that order breaks every remaining tie.
 */
public object TableAssembler {
    public fun sortRows(rows: List<Row>): List<Row> = rows.sortedWith(compareBy<Row>({ it.tick }, { it.action.phase.ordinal }))

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
        states = states.map { it.copy(rows = sortRows(it.rows)) },
        subTables = subTables.mapValues { (_, s) -> s.copy(rows = sortRows(s.rows)) },
        writes = writes,
        conditions = conditions,
        config = config,
        worldRows = sortRows(worldRows),
    )
}
