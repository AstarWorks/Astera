package com.astarworks.astera.core.application.skillengine.compose

/**
 * Kubernetes-style strategic merge on generic YAML trees (maps `Map<String, Any?>`, lists, scalars).
 *
 *  - maps merge deep; scalars replace; a `null` patch value replaces with null;
 *  - lists containing maps merge by their `id` key (base order kept, new ids appended); lists of scalars replace;
 *  - `$patch: delete` inside a map removes that entry (list item by id, or map value by key); deleting something the
 *    base does not have is a [ComposeError.PatchError.TargetNotFound], never silently ignored;
 *  - `$patch: replace` inside a map replaces the base value with the rest of the patch map.
 *
 * Inputs are never mutated; the result shares no mutable state with them.
 */
internal object StrategicMerge {
    const val DIRECTIVE = "\$patch"
    private const val ID = "id"

    class MergeFailure(val toError: (file: String) -> ComposeError)

    /** Returns the merged tree, or the first failure. */
    fun merge(base: Map<String, Any?>, patch: Map<String, Any?>): Pair<Map<String, Any?>?, MergeFailure?> {
        val failure = arrayOfNulls<MergeFailure>(1)
        val out = mergeMaps(base, patch, "", failure)
        return if (failure[0] != null) null to failure[0] else out to null
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeMaps(base: Map<String, Any?>, patch: Map<String, Any?>, path: String, fail: Array<MergeFailure?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        base.forEach { (k, v) -> out[k] = copy(v) }
        for ((k, pv) in patch) {
            if (k == DIRECTIVE) continue
            val childPath = if (path.isEmpty()) k else "$path.$k"
            if (pv is Map<*, *> && pv[DIRECTIVE] == "delete") {
                if (!out.containsKey(k)) recordNotFound(fail, childPath, "\$patch: delete names key '$k', which the base does not have")
                out.remove(k)
                continue
            }
            if (pv is Map<*, *> && pv[DIRECTIVE] == "replace") { out[k] = copy(pv.filterKeys { it != DIRECTIVE }); continue }
            val bv = out[k]
            out[k] = when {
                !out.containsKey(k) -> stripDirectives(pv)
                bv is Map<*, *> && pv is Map<*, *> -> mergeMaps(bv as Map<String, Any?>, pv as Map<String, Any?>, childPath, fail)
                bv is List<*> && pv is List<*> -> mergeLists(bv, pv, childPath, fail)
                else -> stripDirectives(pv)
            }
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeLists(base: List<*>, patch: List<*>, path: String, fail: Array<MergeFailure?>): List<Any?> {
        val keyed = patch.any { it is Map<*, *> } || base.any { it is Map<*, *> }
        if (!keyed) return patch.map { stripDirectives(it) }
        val out = ArrayList<Any?>()
        base.forEachIndexed { i, e ->
            if (e !is Map<*, *> || e[ID] !is String) {
                recordMissing(fail, "$path[$i]", "base list entry has no string 'id'; lists of maps merge by id")
            } else {
                out += copy(e)
            }
        }
        patch.forEachIndexed { i, e ->
            if (e !is Map<*, *> || e[ID] !is String) {
                recordMissing(fail, "$path[$i]", "patch list entry has no string 'id'; lists of maps merge by id")
                return@forEachIndexed
            }
            val id = e[ID] as String
            val at = out.indexOfFirst { (it as Map<*, *>)[ID] == id }
            when {
                e[DIRECTIVE] == "delete" ->
                    if (at >= 0) out.removeAt(at) else recordNotFound(fail, "$path[$id]", "\$patch: delete names id '$id', which the base list does not have")
                at < 0 -> out += stripDirectives(e)
                e[DIRECTIVE] == "replace" -> out[at] = copy(e.filterKeys { it != DIRECTIVE })
                else -> out[at] = mergeMaps(out[at] as Map<String, Any?>, e as Map<String, Any?>, "$path[$id]", fail)
            }
        }
        return out
    }

    private fun recordMissing(fail: Array<MergeFailure?>, path: String, reason: String) {
        if (fail[0] == null) fail[0] = MergeFailure { file -> ComposeError.PatchError.MissingId(file, path, reason) }
    }

    private fun recordNotFound(fail: Array<MergeFailure?>, path: String, reason: String) {
        if (fail[0] == null) fail[0] = MergeFailure { file -> ComposeError.PatchError.TargetNotFound(file, path, reason) }
    }

    fun copy(v: Any?): Any? = when (v) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> v.forEach { (k, x) -> m[k as String] = copy(x) } }
        is List<*> -> v.map { copy(it) }
        else -> v
    }

    private fun stripDirectives(v: Any?): Any? = when (v) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> v.forEach { (k, x) -> if (k != DIRECTIVE) m[k as String] = stripDirectives(x) } }
        is List<*> -> v.map { stripDirectives(it) }
        else -> v
    }
}
