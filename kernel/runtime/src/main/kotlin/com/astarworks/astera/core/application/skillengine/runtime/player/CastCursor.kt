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

    /** The event (contact, victim, attacker) whose transition entered this level; its rows resolve `contact` / `event.*` against it. Null for the root and for a level entered without an event. */
    public var entryBinding: EventBinding? = null

    /** Entered a `final` state; the level plays nothing more. */
    public var done: Boolean = false

    /** Server tick of the last transition of this level (one transition per level per tick, 10.2). */
    public var firedTick: Int = Int.MIN_VALUE

    internal val depth: Int get() = generateSequence(parent) { it.parent }.count()
}

public data class SubCursor(val sub: SubTable, val firedAt: Int, var nextRow: Int, val binding: EventBinding) {
    /** State that owns instances this cursor spawns; null = the cast (exit sub-tables must not be reaped by the state they run for). */
    public var owner: StatePath? = null

    /** Per-chain picks made so far by this firing's chain rows (ADR-0303 9.4). */
    internal var chain: ChainState = ChainState()

    /** This firing with only [rows] left to play (rows keep their load ticks relative to [firedAt]): same owner, binding and chain picks, so a chain hop's later rows still know their pick. */
    internal fun withRows(rows: List<com.astarworks.astera.core.domain.skillengine.table.Row>): SubCursor =
        SubCursor(sub.copy(rows = rows), firedAt, 0, binding).also { it.owner = owner; it.chain = chain }
}

/** The picks of the chains of one sub-table firing: chain id -> victims chosen by hops 1..k, and the chains that ran out of candidates. */
internal class ChainState {
    val picks = HashMap<String, MutableList<com.astarworks.astera.core.domain.skillengine.table.EntityRef>>()
    val dead = HashSet<String>()
}
