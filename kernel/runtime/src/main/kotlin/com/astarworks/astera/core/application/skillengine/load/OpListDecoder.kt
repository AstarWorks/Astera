package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingSpec
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpecRegistry
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.part.OpMap
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath

/**
 * Decodes an op list of one section (ADR-0303 2.0, 10.1). Per element:
 *  - `op: <name>` picks the spec; `use:` without `op` is `use`, `do:` without `op` is `group`, `emit: <event>` is `emit` with `name`.
 *  - `id` (optional, else `<op>_<index>`) and `when` (`cond:<name>` or `= expr`) are read here; the rest goes through the spec's
 *    [com.astarworks.astera.core.application.skillengine.load.grammar.Fields] plus the shared timing block when the op is timed, so an unknown key at any
 *    depth is an [SkillDecodeError.UnknownKey].
 * A GAMEPLAY / PRESENTATION list also accepts CONTROL ops; a CONTROL list (machine enter/do/exit) accepts every section.
 */
public class OpListDecoder(private val specs: OpSpecRegistry) {
    /** Decodes a top-level op list (a machine's `enter` / `do` / `exit` / `on[].do`; CONTROL accepts every section). */
    public fun decode(ops: List<OpMap>, ctx: GrammarContext, path: String): Result<List<OpNode>, SkillDecodeError> =
        Grammar.guard { read(ops, OpSection.CONTROL, ctx.withOpLists(this), path) }

    /** Throwing form used by nested-list field types ([OpLists]); call under [Grammar.guard]. */
    internal fun read(list: YamlNode, section: OpSection, ctx: GrammarContext, path: String): List<OpNode> {
        if (list == null) return emptyList()
        val items = list as? List<*> ?: ctx.mismatch(path, "list of ops", list)
        val out = ArrayList<OpNode>(items.size)
        val seen = HashSet<String>()
        for ((i, el) in items.withIndex()) {
            val p = "$path[$i]"
            val node = element(el, i, section, ctx, p)
            if (!seen.add(node.id.value)) ctx.abortWith(SkillDecodeError.DuplicateId(ctx.file, p, node.id.value))
            out += node
        }
        return out
    }

    private fun element(el: Any?, index: Int, section: OpSection, ctx: GrammarContext, p: String): OpNode {
        val m = el as? Map<*, *> ?: ctx.mismatch(p, "op map", el)
        val body = LinkedHashMap<String, Any?>()
        for ((k, v) in m) {
            if (k !is String) ctx.abortWith(SkillDecodeError.UnknownKey(ctx.file, p, k.toString()))
            body[k] = v
        }
        val name: String
        if ("op" in body) {
            name = body.remove("op") as? String ?: ctx.mismatch("$p.op", "op name", body["op"])
        } else if ("use" in body) {
            name = "use"
        } else if ("do" in body) {
            name = "group"
        } else if ("emit" in body) {
            name = "emit"
            body["name"] = body.remove("emit")
        } else {
            ctx.abortWith(SkillDecodeError.MissingField(ctx.file, p, "op"))
        }
        val spec = find(section, name) ?: ctx.abortWith(SkillDecodeError.UnknownOp(ctx.file, "$p.op", name, section.name))
        val idRaw = body.remove("id")
        val id = when (idRaw) {
            null -> "${name}_$index"
            is String -> if (idRaw.isBlank()) ctx.mismatch("$p.id", "non-empty string", idRaw) else idRaw
            else -> ctx.mismatch("$p.id", "string", idRaw)
        }
        val whenRef = body.remove("when")?.let { whenRef(it, ctx, "$p.when") }

        val fields = if (spec.timing != null) spec.fields + TimingSpec.fields else spec.fields
        expandIndexed(body, fields, ctx, p)
        val values = fields.read(body, ctx, p)
        val timing = spec.timing?.let { kind ->
            val every = values[TimingSpec.every]
            if (every != null && values[TimingSpec.forSpan] == null && values[TimingSpec.times] == null && values[TimingSpec.until] == null) {
                ctx.abortWith(SkillDecodeError.UnboundedRepeat("$p.every", ctx.file))
            }
            if (every != null && kind == com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind.EXTENDED && values[TimingSpec.lasts] == null) {
                ctx.abortWith(SkillDecodeError.MissingField(ctx.file, p, "lasts"))
            }
            TimingSpec.from(values, ctx, p, kind)
        }
        @Suppress("UNCHECKED_CAST")
        val decoded = (spec as OpSpec<OpNode>).decode(OpId(id), SrcPath(p), whenRef, timing, values, ctx)
        val node = when (decoded) {
            is Result.Success -> decoded.value
            is Result.Failure -> ctx.abortWith(decoded.error)
        }
        return node
    }

    /**
     * R28: on `times: n` (a literal, no `every`) a field whose own type does not take a list may carry a list of exactly n entries,
     * entry k read when `i` = k. Rewritten here, before field decoding, into `= [e0, e1, ...][int(i)]`; a wrong length is a load error.
     * A list the field's type does decode on its own (a positional `[x, y, z]` offset) stays as written.
     */
    private fun expandIndexed(body: MutableMap<String, Any?>, fields: Fields, ctx: GrammarContext, p: String) {
        val n = (body["times"] as? Number)?.toDouble()?.takeIf { it % 1.0 == 0.0 && it >= 1.0 }?.toInt() ?: return
        if ("every" in body) return
        for (f in fields.fields) {
            if (f.name in NOT_PER_INDEX) continue
            val raw = body[f.name] as? List<*> ?: continue
            val own = try {
                f.type.decode(raw, ctx, "$p.${f.name}")
                true
            } catch (_: DecodeAbort) {
                false
            }
            if (own) continue
            if (raw.size != n) ctx.abortWith(SkillDecodeError.Invalid(ctx.file, "$p.${f.name}", "per-index list has ${raw.size} entries but times is $n (one entry per copy)"))
            if (raw.isNotEmpty() && raw.all { it is Map<*, *> }) {
                body[f.name] = indexedFrameVec(raw.map { it as Map<*, *> }, ctx, "$p.${f.name}")
                continue
            }
            if (raw.isNotEmpty() && raw.all { e -> e is List<*> && e.isNotEmpty() && e.all { it is Map<*, *> } }) {
                body[f.name] = indexedTurns(raw.map { e -> (e as List<*>).map { it as Map<*, *> } }, ctx, "$p.${f.name}")
                continue
            }
            body[f.name] = "= [" + raw.mapIndexed { k, e -> indexedEntry(e, ctx, "$p.${f.name}[$k]") }.joinToString(", ") + "][int(i)]"
        }
    }

    /** A per-index list of frame-vec maps `{left, up, forward}`: one `= [..][int(i)]` per axis (a missing axis is 0), so the field keeps its frame-vec shape. */
    private fun indexedFrameVec(entries: List<Map<*, *>>, ctx: GrammarContext, path: String): Map<String, Any?> {
        val axes = listOf("left", "up", "forward")
        entries.forEachIndexed { k, m ->
            m.keys.firstOrNull { it !in axes }?.let { ctx.fail("$path[$k]", "unknown frame-vector axis '$it' (expected left, up, forward)") }
        }
        val out = linkedMapOf<String, Any?>()
        for (a in axes) {
            if (entries.none { a in it }) continue
            out[a] = "= [" + entries.mapIndexed { k, m -> indexedEntry(m[a] ?: 0.0, ctx, "$path[$k].$a") }.joinToString(", ") + "][int(i)]"
        }
        return out
    }

    /**
     * A per-index list of rotations (each a turn list): the turns must name the same axes in the same order for every copy
     * (only the angles differ), so each turn keeps its axis and gets `deg: = [..][int(i)]`.
     */
    private fun indexedTurns(entries: List<List<Map<*, *>>>, ctx: GrammarContext, path: String): List<Map<String, Any?>> {
        val first = entries[0]
        entries.forEachIndexed { k, turns ->
            if (turns.size != first.size) ctx.fail("$path[$k]", "per-index rotation has ${turns.size} turns but entry 0 has ${first.size}; every copy turns the same axes")
            turns.forEachIndexed { t, m ->
                if (m["axis"] != first[t]["axis"] || m["frame"] != first[t]["frame"]) {
                    ctx.fail("$path[$k][$t]", "per-index rotation changes axis/frame between copies; only the angle may vary per copy")
                }
            }
        }
        return first.indices.map { t ->
            val turn = linkedMapOf<String, Any?>()
            first[t].forEach { (key, v) -> if (key != "deg") turn[key.toString()] = v }
            turn["deg"] = "= [" + entries.mapIndexed { k, turns -> indexedEntry(turns[t]["deg"], ctx, "$path[$k][$t].deg") }.joinToString(", ") + "][int(i)]"
            turn
        }
    }

    private fun indexedEntry(e: Any?, ctx: GrammarContext, path: String): String = when {
        e is Number -> e.toDouble().toString()
        e is String && e.startsWith("= ") -> "(" + e.substring(2).trim() + ")"
        e is List<*> && e.size == 3 -> "vec(" + e.mapIndexed { k, c -> indexedEntry(c, ctx, "$path[$k]") }.joinToString(", ") + ")"
        else -> ctx.mismatch(path, "number, '= expr' or [x, y, z] triple", e)
    }

    private fun find(section: OpSection, name: String): OpSpec<*>? {
        specs.find(section, name)?.let { return it }
        if (section != OpSection.CONTROL) specs.find(OpSection.CONTROL, name)?.let { return it }
        if (section == OpSection.CONTROL) {
            for (s in listOf(OpSection.GAMEPLAY, OpSection.PRESENTATION)) specs.find(s, name)?.let { return it }
        }
        return null
    }

    private fun whenRef(raw: Any?, ctx: GrammarContext, path: String): WhenRef {
        val s = raw as? String ?: ctx.mismatch(path, "'cond:<name>' or '= expr'", raw)
        if (s.startsWith("cond:")) {
            val cond = s.removePrefix("cond:").trim()
            if (cond.isEmpty()) ctx.fail(path, "'cond:' needs a condition name")
            return WhenRef.Named(cond)
        }
        if (s.startsWith("= ")) {
            val compiler = ctx.exprs ?: ctx.fail(path, "cannot compile '$s': this context has no expression compiler")
            return when (val r = compiler.compile(s.substring(2).trim(), ctx.env, ExprType.BOOL)) {
                is Result.Success -> WhenRef.Expr(r.value)
                is Result.Failure -> ctx.abortWith(SkillDecodeError.BadExpr(ctx.file, path, r.error))
            }
        }
        ctx.mismatch(path, "'cond:<name>' or '= expr'", s)
    }
}

/** Keys that never take a per-index list: the timing counters themselves and nested op lists. */
private val NOT_PER_INDEX = setOf("times", "every", "for", "until", "lasts", "do", "on")

/**
 * Field types for nested op lists (a group's `do:`, a projectile's `on:`). They resolve the [OpListDecoder] that is decoding the
 * enclosing list, because [GrammarContext] carries no registry.
 */
public object OpLists {
    /** An op list of [section] (`null` and `[]` read as empty). */
    public fun type(section: OpSection): FieldType<List<OpNode>> = object : FieldType<List<OpNode>> {
        override val label: String = "list of ${section.name.lowercase()} ops"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): List<OpNode> {
            val d = ctx.opLists ?: ctx.fail(path, "a nested op list can only be decoded inside OpListDecoder")
            return d.read(raw, section, ctx, path)
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "array", "items" to linkedMapOf("type" to "object"))
    }

    public fun field(name: String, section: OpSection, doc: String? = null): Field<List<OpNode>> =
        Field.optional(name, type(section), emptyList(), doc)
}

private fun GrammarContext.abortWith(error: SkillDecodeError): Nothing = throw DecodeAbort(error)
