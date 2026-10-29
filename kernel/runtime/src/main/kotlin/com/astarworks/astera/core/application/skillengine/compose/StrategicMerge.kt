package com.astarworks.astera.core.application.skillengine.compose

/**
 * Merge of the split documents of ONE skill (several `kind: Skill` documents with the same id in one file / directory) on generic
 * YAML trees (maps `Map<String, Any?>`, lists, scalars). It is not a patch mechanism: `kind: Patch` documents go through
 * `PatchApplier` only, and this merge knows no `$patch` directive.
 *
 *  - maps merge deep; scalars replace; a `null` value replaces with null;
 *  - lists containing maps merge by their `id` key (base order kept, new ids appended); lists of scalars replace;
 *    a list of maps where NO entry (base or later document) has an `id` (transitions `on:`, `motion:` laws) is replaced whole;
 *    a list mixing entries with and without `id` is a [ComposeError.PatchError.MissingId].
 *
 * Inputs are never mutated; the result shares no mutable state with them.
 */
internal object StrategicMerge {
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
            val childPath = if (path.isEmpty()) k else "$path.$k"
            val bv = out[k]
            out[k] = when {
                !out.containsKey(k) -> copy(pv)
                bv is Map<*, *> && pv is Map<*, *> -> mergeMaps(bv as Map<String, Any?>, pv as Map<String, Any?>, childPath, fail)
                bv is List<*> && pv is List<*> -> mergeLists(bv, pv, childPath, fail)
                else -> copy(pv)
            }
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeLists(base: List<*>, patch: List<*>, path: String, fail: Array<MergeFailure?>): List<Any?> {
        val maps = (base + patch).filterIsInstance<Map<*, *>>()
        if (maps.none { it[ID] is String }) return patch.map { copy(it) }
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
            if (at < 0) out += copy(e) else out[at] = mergeMaps(out[at] as Map<String, Any?>, e as Map<String, Any?>, "$path[$id]", fail)
        }
        return out
    }

    private fun recordMissing(fail: Array<MergeFailure?>, path: String, reason: String) {
        if (fail[0] == null) fail[0] = MergeFailure { file -> ComposeError.PatchError.MissingId(file, path, reason) }
    }

    fun copy(v: Any?): Any? = when (v) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> v.forEach { (k, x) -> m[k as String] = copy(x) } }
        is List<*> -> v.map { copy(it) }
        else -> v
    }
}
