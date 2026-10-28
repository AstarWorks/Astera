package com.astarworks.astera.core.domain.skillengine.table

import com.astarworks.astera.core.domain.skillengine.model.EngineConfig

/**
 * [tick] is relative to the state entry (static) or to the event (sub-table). [src] is the YAML path the row came from.
 * [maskUntil] lists the events that suppress this candidate row (printed `mask until a, b`); [guard] is a class-L `when` on a point
 * gameplay op, judged at play time (printed `when <expr>`).
 */
public data class Row(
    val tick: Int,
    val instance: InstanceId,
    val action: Action,
    val src: SrcPath,
    val maskUntil: List<EventPath> = emptyList(),
    val guard: Guard? = null,
)

/** "machine.states.cast.do[0].do[1]"; set by the op's lower() from LowerContext.src. */
@JvmInline
public value class SrcPath(public val value: String)

public data class Branch(val event: EventPath, val window: TickRange, val guard: Guard?, val target: StatePath?, val subTable: EventPath?)

public sealed interface Guard {
    public data class Named(val cond: String) : Guard
    public data class Expr(val source: String) : Guard
}

public data class StateTable(
    val state: StatePath,
    val duration: Duration,
    val then: StatePath?,
    val isFinal: Boolean,
    /** Sorted: tick asc, declaration order, occurrence asc (11.3). */
    val rows: List<Row>,
    val branches: List<Branch>,
)

/** C3: everything after an event, relative to it. */
public data class SubTable(
    val event: EventPath,
    val owner: StatePath,
    /** Where in the owner the event may fire. */
    val window: TickRange,
    /** 1 for hit-once / end; bounce `times`; etc. */
    val maxFirings: Int,
    val rows: List<Row>,
    val branches: List<Branch>,
)

public data class Bounds(
    /** C6, check 11. */
    val instances: Map<OpId, Int>,
    /** Check 10. */
    val liveDisplaysPeak: Int,
    val liveDisplaysPeakTick: Int,
    val groupDepth: Int,
    val partDepth: Int,
    /** Min / max ticks start -> final. */
    val path: TickRange,
    /** Longest chain of @0 rows that produce events within one tick (10.2a). */
    val sameTickDepth: Int,
)

public data class WriteStats(val segments: Int, val maxWritesPerTick: Int)

public data class ExpansionTable(
    val skillId: String,
    val sampleSeed: Long,
    val params: Map<String, Any>,
    val bounds: Bounds,
    /** Machine levels flattened; nested machines carry their parent path in [StatePath]. */
    val states: List<StateTable>,
    val subTables: Map<EventPath, SubTable>,
    val writes: Map<InstanceId, WriteStats>,
    /** Name -> CEL source (3.8). */
    val conditions: Map<String, String>,
    /** The caps this table was compiled under; printed as the `# engine:` header of the dump. */
    val config: EngineConfig,
    /**
     * R45 `machine.world:` rows, owned by the machine root (not by the start state): ticks count from the cast start, nothing ends them on a
     * state exit, only their own `End` rows, the cast's end or its cancel. Check 8 bounds them by the machine's worst-case path.
     */
    val worldRows: List<Row> = emptyList(),
)
