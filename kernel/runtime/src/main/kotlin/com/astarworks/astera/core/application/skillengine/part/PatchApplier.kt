package com.astarworks.astera.core.application.skillengine.part

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.part.PartError

public enum class PatchVerb(public val key: String) { DELETE("delete"), REPLACE("replace"), MERGE("merge") }

/**
 * What a patch may change (ADR-0303 R35; enforced by check 13, `PatchKindCheck`). Written on every `kind: Patch`, never defaulted.
 * [SKIN] = presentation rows, plus string params only presentation rows read; [BALANCE] = `params.<p>.default` and numeric fields of
 * gameplay rows; [MECHANIC] = anything under params / meta / anchors / conditions / machine.
 */
public enum class PatchKind(public val key: String) {
    SKIN("skin"), BALANCE("balance"), MECHANIC("mechanic");

    public companion object {
        public fun of(key: Any?): PatchKind? = entries.firstOrNull { it.key == key }
    }
}

/** One path segment: a field [name], optionally followed by a row selector `[<id>]` (the `event` name for an `on:` transition) into the list that field holds. */
public data class PatchSeg(val name: String, val id: String?)

/** `{path, $patch, value}` of a `kind: Patch` document (R35). [value] is null for [PatchVerb.DELETE]. */
public data class PatchOp(val path: String, val segs: List<PatchSeg>, val verb: PatchVerb, val value: Any?)

/** A decoded `kind: Patch` document. [variant] derives `<target>@<variant>`; no variant patches the target in place. */
public data class PatchDoc(val id: String, val target: String, val variant: String?, val patchKind: PatchKind, val ops: List<PatchOp>)

/** The first op that did not resolve; [path] is that op's path as written. */
public data class PatchFailure(val path: String, val message: String)

/**
 * ADR-0303 R35 `kind: Patch`: decoding and application, the single patch path (the composer calls [apply] and nothing else edits a
 * composed tree). Paths address rows by id (`machine.states.windup.do[trail_b]`) and fields by name (`...[trail_a].color`);
 * `$patch: delete` removes a field or a row, `replace` sets one, `merge` deep-merges a map into one. Delete and replace need the
 * target to exist (an unresolved path is a load error, never a silent no-op); merge on a final field or row that does not exist
 * yet adds it. Every segment before the last must exist. Applied to the generic composed tree before compile. Inputs are never mutated.
 */
public object PatchApplier {
    public const val DIRECTIVE: String = "\$patch"
    private val DOC_KEYS = setOf("apiVersion", "kind", "id", "target", "variant", "patchKind", "ops")
    private val OP_KEYS = setOf("path", DIRECTIVE, "value")

    /** The document roots a patch path may start at. */
    public val ROOTS: List<String> = listOf("params", "meta", "anchors", "conditions", "machine")

    /** Decodes one `kind: Patch` tree (strict keys). [where] prefixes error paths. */
    public fun decode(doc: Map<String, Any?>, where: String): Result<PatchDoc, PartError> {
        fun bad(path: String, msg: String) = Result.failure(PartError.Invalid(path, msg))
        doc.keys.firstOrNull { it !in DOC_KEYS }?.let { return bad("$where.$it", "unknown key '$it' (allowed: ${DOC_KEYS.sorted().joinToString()})") }
        if (doc["apiVersion"] != PartParser.API_VERSION) return bad("$where.apiVersion", "apiVersion must be ${PartParser.API_VERSION}, was '${doc["apiVersion"]}'")
        val id = doc["id"] as? String
        if (id.isNullOrBlank()) return bad("$where.id", "a Patch needs a string 'id'")
        val target = doc["target"] as? String
        if (target.isNullOrBlank()) return bad("$where.target", "a Patch needs a string 'target'")
        val variant = doc["variant"]?.let { it as? String ?: return bad("$where.variant", "variant must be a string") }
        if (variant != null && (variant.isBlank() || '@' in variant)) return bad("$where.variant", "variant '$variant' must be non-blank and contain no '@'")
        val patchKind = PatchKind.of(doc["patchKind"])
            ?: return bad("$where.patchKind", "patchKind must be written, one of ${PatchKind.entries.joinToString { it.key }}; was '${doc["patchKind"]}'")
        val rawOps = doc["ops"] as? List<*> ?: return bad("$where.ops", "a Patch needs 'ops': a list of {path, \$patch, value}")
        if (rawOps.isEmpty()) return bad("$where.ops", "ops must not be empty")
        val ops = ArrayList<PatchOp>()
        for ((i, raw) in rawOps.withIndex()) {
            val p = "$where.ops[$i]"
            val m = raw as? Map<*, *> ?: return bad(p, "an op is a map {path, \$patch, value}")
            m.keys.firstOrNull { it !in OP_KEYS }?.let { return bad("$p.$it", "unknown key '$it' (allowed: path, \$patch, value)") }
            val path = m["path"] as? String ?: return bad("$p.path", "missing string 'path'")
            val segs = when (val r = parsePath(path)) {
                is Result.Success -> r.value
                is Result.Failure -> return bad("$p.path", r.error)
            }
            if (segs[0].name !in ROOTS) return bad("$p.path", "path root '${segs[0].name}' is not patchable (allowed: ${ROOTS.joinToString()})")
            val verb = PatchVerb.entries.firstOrNull { it.key == m[DIRECTIVE] }
                ?: return bad("$p.$DIRECTIVE", "\$patch must be delete, replace or merge, was '${m[DIRECTIVE]}'")
            when (verb) {
                PatchVerb.DELETE -> if ("value" in m) return bad("$p.value", "a delete carries no value")
                PatchVerb.REPLACE -> if ("value" !in m) return bad("$p.value", "a replace needs a value")
                PatchVerb.MERGE -> if (m["value"] !is Map<*, *>) return bad("$p.value", "a merge needs a map value")
            }
            ops += PatchOp(path, segs, verb, if (verb == PatchVerb.DELETE) null else m["value"])
        }
        return Result.success(PatchDoc(id, target, variant, patchKind, ops))
    }

    /** `a.b[id].c` -> segments. Ids may contain anything but `]`; names anything but `.`, `[` and `]`. */
    public fun parsePath(path: String): Result<List<PatchSeg>, String> {
        if (path.isEmpty()) return Result.failure("path is empty")
        val out = ArrayList<PatchSeg>()
        var i = 0
        while (i < path.length) {
            val start = i
            while (i < path.length && path[i] != '.' && path[i] != '[' && path[i] != ']') i++
            val name = path.substring(start, i)
            if (name.isEmpty()) return Result.failure("path '$path': empty segment at $start")
            var id: String? = null
            if (i < path.length && path[i] == '[') {
                val close = path.indexOf(']', i)
                if (close < 0) return Result.failure("path '$path': unclosed '['")
                id = path.substring(i + 1, close)
                if (id.isEmpty()) return Result.failure("path '$path': empty row id")
                i = close + 1
            }
            out += PatchSeg(name, id)
            if (i < path.length) {
                if (path[i] != '.') return Result.failure("path '$path': expected '.' at $i")
                i++
                if (i == path.length) return Result.failure("path '$path': trailing '.'")
            }
        }
        return Result.success(out)
    }

    /** Applies [ops] in order to [tree]; the first op that does not resolve fails the whole patch. */
    public fun apply(tree: Map<String, Any?>, ops: List<PatchOp>): Result<Map<String, Any?>, PatchFailure> {
        var cur = tree
        for (op in ops) {
            cur = try {
                inMap(cur, op.segs, 0, op)
            } catch (f: Unresolved) {
                return Result.failure(PatchFailure(op.path, f.message ?: "does not resolve"))
            }
        }
        return Result.success(cur)
    }

    private class Unresolved(message: String) : Exception(message)

    private fun fail(msg: String): Nothing = throw Unresolved(msg)

    @Suppress("UNCHECKED_CAST")
    private fun inMap(node: Map<String, Any?>, segs: List<PatchSeg>, i: Int, op: PatchOp): Map<String, Any?> {
        val seg = segs[i]
        val last = i == segs.lastIndex
        val has = node.containsKey(seg.name)
        val child = node[seg.name]
        val out = LinkedHashMap(node)
        if (seg.id == null) {
            if (last) {
                when (op.verb) {
                    PatchVerb.DELETE -> { if (!has) fail("no field '${seg.name}' to delete"); out.remove(seg.name) }
                    PatchVerb.REPLACE -> { if (!has) fail("no field '${seg.name}' to replace"); out[seg.name] = copy(op.value) }
                    PatchVerb.MERGE -> out[seg.name] = if (has) mergeValue(child, op.value, seg.name) else copy(op.value)
                }
            } else {
                if (!has) fail("no field '${seg.name}'")
                val m = child as? Map<*, *> ?: fail("field '${seg.name}' is not a map")
                out[seg.name] = inMap(m as Map<String, Any?>, segs, i + 1, op)
            }
            return out
        }
        val list = child as? List<*>
        if (list == null && !(last && op.verb == PatchVerb.MERGE && !has)) fail("no list '${seg.name}' to select '${seg.id}' from")
        val rows = ArrayList<Any?>(list.orEmpty())
        val hits = rows.indices.filter { matches(rows[it], seg.id) }
        if (hits.size > 1) fail("row selector '${seg.id}' in '${seg.name}' matches ${hits.size} rows; make it unique")
        val at = hits.firstOrNull() ?: -1
        if (last) {
            when (op.verb) {
                PatchVerb.DELETE -> { if (at < 0) fail("no row '${seg.id}' in '${seg.name}' to delete"); rows.removeAt(at) }
                PatchVerb.REPLACE -> { if (at < 0) fail("no row '${seg.id}' in '${seg.name}' to replace"); rows[at] = rowOf(seg.id, op.value) }
                PatchVerb.MERGE -> if (at >= 0) rows[at] = mergeValue(rows[at], rowOf(seg.id, op.value), "${seg.name}[${seg.id}]") else rows += rowOf(seg.id, op.value)
            }
        } else {
            if (at < 0) fail("no row '${seg.id}' in '${seg.name}'")
            rows[at] = inMap(rows[at] as Map<String, Any?>, segs, i + 1, op)
        }
        out[seg.name] = rows
        return out
    }

    /**
     * Does [row] answer to selector [sel]? Rows carry an `id`; a state's `on:` transitions carry none, so they are selected by their
     * `event` (`on[reel_hit.hit]`). A row with an `id` is never matched by its event.
     */
    public fun matches(row: Any?, sel: String): Boolean {
        val m = row as? Map<*, *> ?: return false
        val id = m["id"]
        return if (id != null) id == sel else m["event"] == sel
    }

    /** A row for the list: [value] must be a map; its own `id`, if written, must equal the selector. `id` comes first. */
    private fun rowOf(id: String, value: Any?): Map<String, Any?> {
        val m = value as? Map<*, *> ?: fail("a row value must be a map")
        val own = m["id"]
        if (own != null && own != id) fail("row value carries id '$own' but the path selects '$id'")
        val out = LinkedHashMap<String, Any?>()
        out["id"] = id
        m.forEach { (k, v) -> if (k != "id") out[k as String] = copy(v) }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeValue(base: Any?, patch: Any?, what: String): Any? {
        if (base !is Map<*, *> || patch !is Map<*, *>) fail("merge into '$what' needs a map on both sides (use replace for a scalar or a list)")
        val out = LinkedHashMap(base as Map<String, Any?>)
        for ((k, v) in patch) {
            k as String
            val b = out[k]
            out[k] = if (b is Map<*, *> && v is Map<*, *>) mergeValue(b, v, "$what.$k") else copy(v)
        }
        return out
    }

    private fun copy(v: Any?): Any? = when (v) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> v.forEach { (k, x) -> m[k as String] = copy(x) } }
        is List<*> -> v.map { copy(it) }
        else -> v
    }
}
