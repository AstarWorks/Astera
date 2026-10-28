package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.load.grammar.Field
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
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath

/** A decoded op plus the YAML path it came from (`Row.src` and error messages, ADR-0303 section 12). */
public data class LocatedOp(val node: OpNode, val src: SrcPath)

/**
 * Decodes an op list of one section (ADR-0303 2.0, 10.1). Per element:
 *  - `op: <name>` picks the spec; `use:` without `op` is `use`, `do:` without `op` is `group`, `emit: <event>` is `emit` with `name`.
 *  - `id` (optional, else `<op>_<index>`) and `when` (`cond:<name>` or `= expr`) are read here; the rest goes through the spec's
 *    [com.astarworks.astera.core.application.skillengine.load.grammar.Fields] plus the shared timing block when the op is timed, so an unknown key at any
 *    depth is an [SkillDecodeError.UnknownKey].
 * A GAMEPLAY / PRESENTATION list also accepts CONTROL ops; a CONTROL list (machine enter/do/exit) accepts every section.
 */
public class OpListDecoder(private val specs: OpSpecRegistry) {
    public fun decode(list: YamlNode, section: OpSection, ctx: GrammarContext, path: String): Result<List<OpNode>, SkillDecodeError> =
        decodeLocated(list, section, ctx, path).map { l -> l.map { it.node } }

    public fun decodeLocated(list: YamlNode, section: OpSection, ctx: GrammarContext, path: String): Result<List<LocatedOp>, SkillDecodeError> =
        Grammar.guard { scoped { read(list, section, ctx, path) } }

    /** Throwing form used by nested-list field types ([OpLists]); call under [Grammar.guard] inside [scoped]. */
    internal fun read(list: YamlNode, section: OpSection, ctx: GrammarContext, path: String): List<LocatedOp> {
        if (list == null) return emptyList()
        val items = list as? List<*> ?: ctx.mismatch(path, "list of ops", list)
        val out = ArrayList<LocatedOp>(items.size)
        val seen = HashSet<String>()
        for ((i, el) in items.withIndex()) {
            val p = "$path[$i]"
            val located = element(el, i, section, ctx, p)
            if (!seen.add(located.node.id.value)) ctx.abortWith(SkillDecodeError.DuplicateId(ctx.file, p, located.node.id.value))
            out += located
        }
        return out
    }

    private fun <T> scoped(block: () -> T): T {
        val prev = OpLists.active.get()
        OpLists.active.set(this)
        try {
            return block()
        } finally {
            OpLists.active.set(prev)
        }
    }

    private fun element(el: Any?, index: Int, section: OpSection, ctx: GrammarContext, p: String): LocatedOp {
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
        val decoded = (spec as OpSpec<OpNode>).decode(OpId(id), whenRef, timing, values, ctx)
        val node = when (decoded) {
            is Result.Success -> decoded.value
            is Result.Failure -> ctx.abortWith(decoded.error)
        }
        return LocatedOp(node, SrcPath(p))
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

/**
 * Field types for nested op lists (a group's `do:`, a projectile's `on:`). They resolve the [OpListDecoder] that is decoding the
 * enclosing list, because [GrammarContext] carries no registry.
 */
public object OpLists {
    internal val active: ThreadLocal<OpListDecoder?> = ThreadLocal()

    /** An op list of [section] (`null` and `[]` read as empty). */
    public fun type(section: OpSection): FieldType<List<OpNode>> = object : FieldType<List<OpNode>> {
        override val label: String = "list of ${section.name.lowercase()} ops"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): List<OpNode> {
            val d = active.get() ?: ctx.fail(path, "a nested op list can only be decoded inside OpListDecoder")
            return d.read(raw, section, ctx, path).map { it.node }
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "array", "items" to linkedMapOf("type" to "object"))
    }

    public fun field(name: String, section: OpSection, doc: String? = null): Field<List<OpNode>> =
        Field.optional(name, type(section), emptyList(), doc)
}

private fun GrammarContext.abortWith(error: SkillDecodeError): Nothing = throw DecodeAbort(error)
