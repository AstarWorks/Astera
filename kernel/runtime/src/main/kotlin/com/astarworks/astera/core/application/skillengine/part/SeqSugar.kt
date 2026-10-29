package com.astarworks.astera.core.application.skillengine.part

import com.astarworks.astera.core.domain.skillengine.part.OpMap

/**
 * ADR-0303 11.1 (R17): `seq: [{wait: N}, X, ...]` is sugar. Each item fires at the running offset (`at: offset`), a `{wait: N}` advances it.
 * The loader desugars every op list through here before anything else reads it, so the table is the one the declarative `at` form gives.
 */
internal object SeqSugar {
    /** Replaces each `{seq: [...]}` element of [items] by its `at`-carrying ops; every other element is kept as is. */
    fun expandList(items: List<*>, where: String, fail: (String, String) -> Nothing): List<Any?> {
        if (items.none { it is Map<*, *> && "seq" in it }) return items
        val out = ArrayList<Any?>()
        items.forEachIndexed { i, e ->
            if (e is Map<*, *> && "seq" in e) out.addAll(expand(e, "$where[$i]", fail)) else out.add(e)
        }
        return out
    }

    private fun expand(m: Map<*, *>, where: String, fail: (String, String) -> Nothing): List<OpMap> {
        if (m.size != 1) fail(where, "a seq: item carries nothing else than the list")
        val items = m["seq"] as? List<*> ?: fail("$where.seq", "seq must be a list of ops and {wait: N}")
        var offset = 0
        val refs = ArrayList<String>() // `wait: "${p}"`: the offset stays a sum that ParamSubst folds with the params (the TickFields rule)
        val out = ArrayList<OpMap>()
        items.forEachIndexed { j, item ->
            val at = "$where.seq[$j]"
            val im = item as? Map<*, *> ?: fail(at, "expected a mapping")
            if ("wait" in im) {
                val w = im["wait"]
                if (im.size != 1 || !(TickFields.isWholeTicks(w) || TickFields.isParamRef(w))) {
                    fail(at, "wait must be the only key and a non-negative integer of ticks or \"\${param}\"")
                }
                if (w is Number) offset += w.toInt() else refs += w as String
            } else {
                if ("at" in im || "seq" in im) fail(at, "a seq item takes its tick from the running offset: no 'at', no nested seq")
                val op = LinkedHashMap<String, Any?>()
                for ((k, v) in im) op[k as? String ?: fail(at, "non-string key '$k'")] = v
                op["at"] = if (refs.isEmpty()) offset else "= " + (if (offset != 0) listOf(offset.toString()) + refs else refs).joinToString(" + ")
                out += op
            }
        }
        return out
    }
}
