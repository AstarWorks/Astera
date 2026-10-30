package com.astarworks.astera.core.domain.skillengine.table

/**
 * What a compiled skill does to the game, with every presentation row dropped (engine-gaps-v2 2.5, check 14). Two skills with equal
 * projections hit, damage, apply status, move, emit, end and transition at the same ticks; a skin may differ from its base only outside it.
 *
 * Kept: the skill's item binding, `emits`, callee-only flag, per state the duration / `then` / branches and every gameplay row
 * ([Row.isGameplayRow], plus the `End` of an instance a gameplay row made, `Enter` and `Exit`), the handler sub-tables that hold a gameplay
 * row or a branch, the machine-level `world:` rows, and the cast's tick bounds. Dropped: spawns of displays / ribbons, keys, teleports,
 * trail points, particles, sounds, per-tick field writes, presentation `effect` ops, and the `End` of an instance a presentation row made
 * (a display's `lasts` is presentation timing). A row's [Row.src] is not part of it: inserting a presentation row shifts the indices.
 *
 * [entries] is the flat, ordered list the comparison walks; each entry names where it is, so the first difference can be reported.
 */
public class GameplayProjection private constructor(public val entries: List<Entry>) {
    public data class Entry(val where: String, val text: String, val src: String? = null)

    /** The first entry at which [this] and [other] differ, as (index, this entry or null, other entry or null); null when equal. */
    public fun firstDifference(other: GameplayProjection): Triple<Int, Entry?, Entry?>? {
        val n = maxOf(entries.size, other.entries.size)
        for (i in 0 until n) {
            val a = entries.getOrNull(i)
            val b = other.entries.getOrNull(i)
            if (a == null || b == null || a.where != b.where || a.text != b.text) return Triple(i, a, b)
        }
        return null
    }

    public companion object {
        public fun of(skill: CompiledSkill): GameplayProjection {
            val table = skill.table
            val out = ArrayList<Entry>()
            out += Entry("skill", "item=${skill.def.item} emits=${skill.def.emits} calleeOnly=${skill.def.isCalleeOnly}")
            out += Entry("bounds", "path=${table.bounds.path} tail=${table.bounds.tail}")
            val allRows = table.states.flatMap { it.rows } + table.subTables.values.flatMap { it.rows } + table.worldRows
            val presentationInstances = allRows.filter { it.makesPresentationInstance() }.map { it.instance }.toSet()
            fun keep(row: Row): Boolean = when (val a = row.action) {
                is Action.End -> a.instance !in presentationInstances
                is Action.Enter, is Action.Exit -> true
                else -> row.isGameplayRow
            }
            fun rowEntry(where: String, row: Row) = Entry(
                where,
                "@${row.tick} ${row.instance.text} ${row.action} mask=${row.maskUntil} guard=${row.guard} sample=${row.sample} seed=${row.seed} " +
                    "detached=${row.detached} chain=${row.chain}",
                row.src.value,
            )
            for (s in table.states.sortedBy { it.state.value }) {
                val at = "state ${s.state.value}"
                out += Entry(at, "duration=${s.duration} then=${s.then} final=${s.isFinal}")
                s.branches.forEach { out += Entry("$at branch", it.toString()) }
                s.rows.filter(::keep).forEach { out += rowEntry(at, it) }
            }
            for (t in table.subTables.values.sortedBy { it.event.value }) {
                val rows = t.rows.filter(::keep)
                if (rows.isEmpty() && t.branches.isEmpty()) continue
                val at = "event ${t.event.value}"
                out += Entry(at, "owner=${t.owner.value} window=${t.window} maxFirings=${t.maxFirings}")
                t.branches.forEach { out += Entry("$at branch", it.toString()) }
                rows.forEach { out += rowEntry(at, it) }
            }
            table.worldRows.filter(::keep).forEach { out += rowEntry("world", it) }
            return GameplayProjection(out)
        }

        private fun Row.makesPresentationInstance(): Boolean = when (val a = action) {
            is Action.Spawn -> a.kind != SpawnKind.PROJECTILE_BODY
            is Action.Effect -> gameplayName() == null
            else -> false
        }
    }
}
