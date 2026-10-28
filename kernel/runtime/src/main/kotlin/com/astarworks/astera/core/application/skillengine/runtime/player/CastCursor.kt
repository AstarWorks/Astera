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
    /** One per active machine level (nested / parallel), in entry order. A finished (final) level stays until its parent leaves. */
    public var levels: List<LevelCursor>,
    /** Live spawned things, in spawn order. */
    public val instances: MutableMap<InstanceId, GatewayHandle>,
    /** Pasted sub-tables in flight (event tick, SubTable, next row index). */
    public val subCursors: MutableList<SubCursor>,
)

public data class LevelCursor(val state: StatePath, val enteredAt: Int, var nextRow: Int) {
    /** The level that owns this one (the state whose rows entered it); null for the root. */
    public var parent: LevelCursor? = null

    /** Entered a `final` state; the level plays nothing more. */
    public var done: Boolean = false

    /** Server tick of the last transition of this level (one transition per level per tick, 10.2). */
    public var firedTick: Int = Int.MIN_VALUE

    internal val depth: Int get() = generateSequence(parent) { it.parent }.count()
}

public data class SubCursor(val sub: SubTable, val firedAt: Int, var nextRow: Int, val binding: EventBinding) {
    /** State that owns instances this cursor spawns; null = the cast (exit sub-tables must not be reaped by the state they run for). */
    public var owner: StatePath? = null
}
