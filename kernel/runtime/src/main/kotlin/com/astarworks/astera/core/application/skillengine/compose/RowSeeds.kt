package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CancelScope
import com.astarworks.astera.core.domain.skillengine.table.Guard
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.TargetRule

/**
 * The content seed of a table row (ADR-0303 rev4.8): `rand()` and the particle spread of a row draw from a hash of what the row IS, not of
 * what its op is called, so renaming, reordering, splitting or merging ops keeps every spread, and changing what a row is (tick, position,
 * type) re-rolls only that row.
 *
 * Identity = tick, action kind, the copy indices of the instance path (`i`, `parent.i`, ...), sample `k/n`, and every cell of the action:
 * resolved values as they print, live cells as normalized source text. Left out: op ids and instance names, anchor names held as fields,
 * event and state names, the YAML path, `mask until`, and any live cell that contains `rand()` / `roll()` / `draw()` (the randomness
 * itself must not feed its own seed). Rows with byte-identical identity get a counter in document order of the list they sit in, so a
 * deliberately doubled emitter still gets two streams; only true duplicates depend on order.
 *
 * Sim and Paper share this: the assembler stamps [Row.seed] once and the one table player reads it.
 */
public object RowSeeds {
    private val RAND = Regex("""(?<![A-Za-z0-9_.])(?:rand|roll|roll_unit|draw|draw_unit)\s*\(""")
    private val LIVE = Regex("""Live\(name=(.*?), bound=""", RegexOption.DOT_MATCHES_ALL)
    private val SPACE = Regex("""\s+""")
    private val NONE = InstanceId(emptyList())

    /** [rows] in document order (before sorting); the same rows with [Row.seed] set. */
    public fun assign(rows: List<Row>): List<Row> {
        val seen = HashMap<String, Int>()
        return rows.map { r ->
            val id = identity(r)
            val dup = seen.merge(id, 1, Int::plus)!! - 1
            r.copy(seed = V3Bindings.fnv1a(id, V3Bindings.fnv1a("dup#$dup")))
        }
    }

    /** The row's identity text; two rows with equal text are byte-identical duplicates. */
    public fun identity(row: Row): String {
        val sb = StringBuilder()
        sb.append(row.tick).append('|').append(row.action::class.simpleName).append('|')
        row.instance.path.joinTo(sb, ",") { it.index?.toString() ?: "-" }
        sb.append('|').append(row.sample?.let { "${it.k}/${it.n}" } ?: "").append('|')
        sb.append(
            when (val g = row.guard) {
                null -> ""
                is Guard.Named -> "named"
                is Guard.Expr -> normalize(g.source)
            },
        ).append('|')
        sb.append(canonical(row.action))
        return sb.toString()
    }

    private fun canonical(a: Action): String {
        val text = when (a) {
            is Action.Spawn -> a.copy(anchor = null, anchorName = "")
            is Action.Key -> a.copy(display = NONE)
            is Action.Teleport -> a.copy(display = NONE)
            is Action.TrailPoint -> a.copy(ribbon = NONE)
            is Action.Hit -> a.copy(anchor = null, anchorName = "")
            is Action.Damage -> a.copy(targets = rule(a.targets))
            is Action.Status -> a.copy(targets = rule(a.targets))
            is Action.Call -> a.copy(callId = NONE, frames = emptyMap())
            is Action.Cancel -> a.copy(scope = if (a.scope is CancelScope.Instance) CancelScope.Instance(NONE) else a.scope)
            is Action.Emit, is Action.End, is Action.Enter -> ""
            is Action.Exit -> a.reason.name
            else -> a
        }.toString()
        return LIVE.replace(text) { m ->
            val name = m.groupValues[1]
            "Live(name=" + (if (RAND.containsMatchIn(name)) "<rand>" else normalize(name)) + ", bound="
        }
    }

    private fun rule(t: TargetRule): TargetRule = if (t is TargetRule.Area) t.copy(around = null, aroundName = "") else t

    private fun normalize(source: String): String = SPACE.replace(source.trim(), " ")
}
