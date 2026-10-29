package com.astarworks.astera.core.application.skillengine.load.spec

import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.WriteStats

/** What an op contributes. Rows are relative to the op's own occurrence tick 0; the unroller shifts them. */
public data class Lowered(
    val rows: List<Row>,
    /** Projectile `on:`, part/skill emits, cancel exits. */
    val subTables: List<SubTable> = emptyList(),
    /** What it spawned (for ownership / End rows / display cap). */
    val instances: List<InstanceId> = emptyList(),
    /** Events this op PRODUCES (check 12). */
    val events: List<EventPath> = emptyList(),
    val writes: Map<InstanceId, WriteStats> = emptyMap(),
)
