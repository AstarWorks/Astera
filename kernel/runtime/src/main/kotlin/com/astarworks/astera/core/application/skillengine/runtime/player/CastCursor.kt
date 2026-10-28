package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.SubTable

/** Per-cast state: nothing else exists at runtime (C7). */
public class CastCursor(
    public val handle: CastHandle,
    public val skill: CompiledSkill,
    public val seed: SeededStream,
    /** One per active machine level (nested / parallel). */
    public var levels: List<LevelCursor>,
    /** Live spawned things. */
    public val instances: MutableMap<InstanceId, GatewayHandle>,
    /** Pasted sub-tables in flight (event tick, SubTable, next row index). */
    public val subCursors: MutableList<SubCursor>,
)

public data class LevelCursor(val state: StatePath, val enteredAt: Int, var nextRow: Int)

public data class SubCursor(val sub: SubTable, val firedAt: Int, var nextRow: Int, val binding: EventBinding)
