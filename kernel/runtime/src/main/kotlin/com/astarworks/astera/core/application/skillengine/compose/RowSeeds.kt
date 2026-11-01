package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.domain.skillengine.table.Row

/**
 * The seed of a table row: `rand()` and the particle spread of a row draw from a hash of the row's stable identity, never from the text
 * of its spec. Adding or defaulting a look field, a cell or a flag on an op must not re-roll anything, so no part of the action (its
 * `toString()`, look fields, resolved cells) feeds the seed.
 *
 * Identity = skill id, the instance path (op ids with their copy indices: the id of the row that is an each-victim or `times` instance),
 * the tick, the action kind, sample `k/n` and the chain hop. The label of one `rand()` call is added by the evaluator (the k-th `rand()`
 * of a field is the stream `@k`). Rows that still collide (the same op at the same tick, e.g. a doubled emitter) get a counter in
 * document order of the list they sit in, so each still has its own stream.
 *
 * Sim and Paper share this: the assembler stamps [Row.seed] once and the one table player reads it.
 */
public object RowSeeds {
    /** [rows] in document order (before sorting); the same rows with [Row.seed] set. */
    public fun assign(rows: List<Row>, skillId: String): List<Row> {
        val seen = HashMap<String, Int>()
        return rows.map { r ->
            val id = identity(r, skillId)
            val dup = seen.merge(id, 1, Int::plus)!! - 1
            r.copy(seed = V3Bindings.fnv1a(id, V3Bindings.fnv1a("dup#$dup")))
        }
    }

    /** The row's identity text; two rows with equal text are duplicates told apart by their document order. */
    public fun identity(row: Row, skillId: String): String =
        "$skillId|${row.instance.text}|${row.tick}|${row.action::class.simpleName}|" +
            (row.sample?.let { "${it.k}/${it.n}" } ?: "") + "|" + (row.chain?.toString() ?: "")
}
