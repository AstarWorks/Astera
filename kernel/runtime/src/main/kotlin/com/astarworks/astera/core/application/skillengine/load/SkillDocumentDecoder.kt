package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.application.skillengine.load.grammar.ExprFrames
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.loadTimeDefaults
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpecRegistry
import com.astarworks.astera.core.application.skillengine.part.PartParser
import com.astarworks.astera.core.application.skillengine.part.PatchApplier
import com.astarworks.astera.core.application.skillengine.part.PatchDoc
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.model.AnchorDecl
import com.astarworks.astera.core.domain.skillengine.model.AnchorMode
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.model.SkillMeta
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.part.OpMap
import com.astarworks.astera.core.domain.skillengine.part.ParamSpec
import com.astarworks.astera.core.domain.skillengine.part.ParamType

/** A node of the generic YAML tree (snakeyaml output: maps, lists, scalars). */
public typealias YamlNode = Any?

/**
 * What a document decode needs besides the file: the part/skill catalogs it may name, and the identifiers a CEL expression may use
 * ([exprEnv], the V3 bindings by default).
 */
public class GrammarEnv(
    public val knownParts: Set<String> = emptySet(),
    public val knownSkills: Set<String> = emptySet(),
    public val exprEnv: ExprEnvSpec = V3Bindings.spec(null),
)

/** One multi-document file: skills decoded, parts kept as raw trees for the part catalog, patches decoded to op lists for the composer. */
public data class DocumentSet(
    val skills: List<SkillDef>,
    val parts: List<Map<String, Any?>>,
    val patches: List<PatchDoc>,
)

/**
 * Replaces `YamlSkillDecoder` (ADR-0303 sections 0, 2.0, 9, 10). Reads the top-level fields of a `kind: Skill` document with strict
 * unknown keys at every depth, validates every op list of the machine through [OpListDecoder] (so a bad op, timing block or `when` is
 * a named error with its YAML path), and returns the [SkillDef]. The machine keeps its raw op maps (`MachineState.enter/do/exit`):
 * the table compiler decodes them again with the same [OpListDecoder].
 */
public class SkillDocumentDecoder(private val specs: OpSpecRegistry, private val exprs: ExprCompiler) {
    private val ops = OpListDecoder(specs)

    public fun decode(file: String, root: YamlNode, env: GrammarEnv): Result<SkillDef, SkillDecodeError> =
        Grammar.guard { skill(file, root, env) }

    /** Splits [text] into documents and decodes each by `kind` (`Skill` decoded, `Part` kept raw after a header check, `Patch` decoded to a [PatchDoc] by [PatchApplier]). */
    public fun decodeDocuments(file: String, text: String, env: GrammarEnv): Result<DocumentSet, SkillDecodeError> {
        val docs = when (val r = PartParser.documents(file, text)) {
            is Result.Success -> r.value
            is Result.Failure -> return Result.Failure(SkillDecodeError.Invalid(file, r.error.path, r.error.message))
        }
        val skills = ArrayList<SkillDef>()
        val parts = ArrayList<Map<String, Any?>>()
        val patches = ArrayList<PatchDoc>()
        for ((i, doc) in docs.withIndex()) {
            val where = if (docs.size == 1) file else "$file#${i + 1}"
            when (val kind = doc["kind"]) {
                "Skill" -> when (val r = decode(where, doc, env)) {
                    is Result.Success -> skills += r.value
                    is Result.Failure -> return r
                }
                "Part" -> {
                    val header = Grammar.guard { header(where, doc) }
                    if (header is Result.Failure) return header
                    parts += doc
                }
                "Patch" -> when (val r = PatchApplier.decode(doc, where)) {
                    is Result.Success -> patches += r.value
                    is Result.Failure -> return Result.Failure(SkillDecodeError.Invalid(where, r.error.path, r.error.message))
                }
                null -> return Result.Failure(SkillDecodeError.MissingField(where, "", "kind"))
                else -> return Result.Failure(SkillDecodeError.Invalid(where, "kind", "kind must be Skill, Part or Patch, was '$kind'"))
            }
        }
        return Result.Success(DocumentSet(skills, parts, patches))
    }

    private fun header(file: String, doc: Map<String, Any?>) {
        val ctx = GrammarContext(file)
        if (doc["apiVersion"] != PartParser.API_VERSION) ctx.fail("apiVersion", "apiVersion must be ${PartParser.API_VERSION}, was '${doc["apiVersion"]}'")
        if (doc["id"] !is String) ctx.abortWith(SkillDecodeError.MissingField(file, "", "id"))
    }

    private fun skill(file: String, root: YamlNode, env: GrammarEnv): SkillDef {
        val boot = GrammarContext(file)
        val tree = root as? Map<*, *> ?: boot.mismatch("", "map (a skill document)", root)
        @Suppress("UNCHECKED_CAST")
        tree as Map<String, Any?>
        checkKeys(boot, tree, "", TOP_KEYS)
        when (val kind = tree["kind"]) {
            null -> boot.abortWith(SkillDecodeError.MissingField(file, "", "kind"))
            "Skill" -> Unit
            else -> boot.fail("kind", "kind '$kind' is not a skill (Part and Patch documents go through decodeDocuments)")
        }
        if (tree["apiVersion"] != PartParser.API_VERSION) boot.fail("apiVersion", "apiVersion must be ${PartParser.API_VERSION}, was '${tree["apiVersion"]}'")
        val id = tree["id"] as? String ?: if ("id" in tree) boot.mismatch("id", "string", tree["id"]) else boot.abortWith(SkillDecodeError.MissingField(file, "", "id"))
        val item = tree["item"]?.let { it as? String ?: boot.mismatch("item", "string", it) }
        val importance = tree["importance"]?.let { intAt(boot, it, "importance") }

        val params = LinkedHashMap<String, ParamSpec>()
        for ((name, raw) in mapAt(boot, tree["params"], "params")) params[name] = param(boot, name, raw, "params.$name")
        val framesIn = stringMap(boot, tree["frames_in"], "frames_in")
        val emits = stringList(boot, tree["emits"], "emits")
        val anchors = anchors(boot, tree["anchors"])
        val docEnv = ExprEnvSpec(
            env.exprEnv.variables + params.mapValues { (_, p) ->
                when (p.type) {
                    ParamType.BOOL -> ExprType.BOOL
                    ParamType.STRING -> ExprType.STR
                    else -> ExprType.NUM
                }
            },
            (env.exprEnv.frames + anchors.map { it.id } + framesIn.keys + ExprFrames.ofRaw(tree["machine"])),
        )
        val ctx = GrammarContext(file, null, exprs, docEnv, paramDefaults = loadTimeDefaults(params))
        val conditions = LinkedHashMap<String, String>()
        for ((name, raw) in mapAt(boot, tree["conditions"], "conditions")) {
            val src = raw as? String ?: boot.mismatch("conditions.$name", "'= expr'", raw)
            if (!src.startsWith("= ")) boot.mismatch("conditions.$name", "'= expr'", src)
            val body = src.substring(2).trim()
            when (val r = exprs.compile(body, docEnv, ExprType.BOOL)) {
                is Result.Success -> Unit
                is Result.Failure -> boot.abortWith(SkillDecodeError.BadExpr(file, "conditions.$name", r.error))
            }
            conditions[name] = body
        }
        val meta = meta(boot, tree["meta"])
        val machine = machine(ctx, tree)
        return SkillDef(
            id = id, item = item, importance = importance, meta = meta,
            params = params, framesIn = framesIn, emits = emits, anchors = anchors, conditions = conditions, machine = machine,
        )
    }

    // ---- machine ---------------------------------------------------------------------------------------------------

    private fun machine(ctx: GrammarContext, tree: Map<String, Any?>): com.astarworks.astera.core.domain.skillengine.part.Machine {
        val raw = tree["machine"] ?: ctx.abortWith(SkillDecodeError.MissingField(ctx.file, "", "machine"))
        val m = raw as? Map<*, *> ?: ctx.mismatch("machine", "map {start, states}", raw)
        @Suppress("UNCHECKED_CAST")
        m as Map<String, Any?>
        checkKeys(ctx, m, "machine", setOf("start", "states", "world"))
        val states = m["states"] as? Map<*, *> ?: ctx.mismatch("machine.states", "map of states", m["states"])
        val start = m["start"]
        if (start is String && '/' in start) ctx.abortWith(SkillDecodeError.StartNamesChild(ctx.file, start))
        val flat = LinkedHashMap<String, Map<String, Any?>>()
        fun walk(sid: String, sp: String, sraw: Any?) {
            val s = sraw as? Map<*, *> ?: ctx.mismatch(sp, "map", sraw)
            @Suppress("UNCHECKED_CAST")
            s as Map<String, Any?>
            checkKeys(ctx, s, sp, STATE_KEYS)
            flat[sid] = s
            val on = s["on"]
            if (on is List<*>) {
                for ((i, t) in on.withIndex()) {
                    val tm = t as? Map<*, *> ?: ctx.mismatch("$sp.on[$i]", "map", t)
                    @Suppress("UNCHECKED_CAST")
                    checkKeys(ctx, tm as Map<String, Any?>, "$sp.on[$i]", TRANSITION_KEYS)
                }
            }
            val kids = s["states"] ?: return
            kids as? Map<*, *> ?: ctx.mismatch("$sp.states", "map of states", kids)
            if (s["start"] == null) ctx.abortWith(SkillDecodeError.NestedStartMissing(ctx.file, sid))
            for ((cid, craw) in kids) walk("$sid/$cid", "$sp.states.$cid", craw)
        }
        for ((sid, sraw) in states) walk(sid as String, "machine.states.$sid", sraw)
        val machine = when (val r = PartParser.skillMachine(tree)) {
            is Result.Success -> r.value ?: ctx.abortWith(SkillDecodeError.MissingField(ctx.file, "", "machine"))
            is Result.Failure -> ctx.abortWith(SkillDecodeError.Invalid(ctx.file, r.error.path, r.error.message))
        }
        // The op lists are only validated here; the nodes are decoded again by the table compiler (MachineState keeps raw maps).
        checkOps(ctx, m["world"], "machine.world")
        for ((sid, s) in flat) {
            val sp = "machine.states." + sid.replace("/", ".states.")
            for (key in listOf("enter", "do", "exit")) checkOps(ctx, s[key], "$sp.$key")
            (s["on"] as? List<*>)?.forEachIndexed { i, t -> checkOps(ctx, (t as Map<*, *>)["do"], "$sp.on[$i].do") }
        }
        return machine
    }

    private fun checkOps(ctx: GrammarContext, list: Any?, path: String) {
        if (list == null) return
        val items = list as? List<*> ?: ctx.mismatch(path, "list of ops", list)
        @Suppress("UNCHECKED_CAST")
        val maps = items.mapIndexed { i, e -> (e as? Map<*, *> ?: ctx.mismatch("$path[$i]", "op map", e)) as OpMap }
        when (val r = ops.decode(maps, ctx, path)) {
            is Result.Success -> Unit
            is Result.Failure -> ctx.abortWith(r.error)
        }
    }

    // ---- header fields -------------------------------------------------------------------------------------------------

    private fun meta(ctx: GrammarContext, raw: Any?): SkillMeta {
        val m = mapAt(ctx, raw, "meta")
        checkKeys(ctx, m, "meta", META_KEYS)
        fun nonNeg(key: String): Int? = m[key]?.let { intAt(ctx, it, "meta.$key").also { v -> if (v < 0) ctx.mismatch("meta.$key", "integer >= 0", it) } }
        val resourceType = m["resource_type"]?.let { it as? String ?: ctx.mismatch("meta.resource_type", "ammo or energy", it) }
        if (resourceType != null && resourceType != "ammo" && resourceType != "energy") ctx.mismatch("meta.resource_type", "ammo or energy", resourceType)
        val readyCue = m["ready_cue"]?.let { it as? Boolean ?: ctx.mismatch("meta.ready_cue", "boolean", it) }
        return SkillMeta(
            name = i18n(ctx, m["name"], "meta.name"),
            description = i18n(ctx, m["description"], "meta.description"),
            cooldownTicks = nonNeg("cooldown_ticks") ?: 0,
            resourceType = resourceType,
            resourceCost = nonNeg("resource_cost"),
            egoCost = nonNeg("ego_cost") ?: 0,
            reloadTicks = nonNeg("reload_ticks") ?: 0,
            readyCue = readyCue,
            tags = stringList(ctx, m["tags"], "meta.tags"),
        )
    }

    private fun i18n(ctx: GrammarContext, raw: Any?, path: String): Map<String, String> = stringMap(ctx, raw, path)

    private fun param(ctx: GrammarContext, name: String, raw: Any?, path: String): ParamSpec {
        val m = raw as? Map<*, *> ?: ctx.mismatch(path, "{type, required|default}", raw)
        @Suppress("UNCHECKED_CAST")
        m as Map<String, Any?>
        checkKeys(ctx, m, path, setOf("type", "required", "default"))
        val typeKey = m["type"] as? String ?: ctx.abortWith(SkillDecodeError.MissingField(ctx.file, path, "type"))
        val type = ParamType.of(typeKey) ?: ctx.fail("$path.type", "unknown type '$typeKey' (${ParamType.entries.joinToString { it.key }})")
        val required = m["required"] as? Boolean
        val hasDefault = m.containsKey("default")
        if (required == true && hasDefault) ctx.fail(path, "a required param carries no default")
        if (required != true && !hasDefault) ctx.fail(path, "param '$name' needs either 'required: true' or an explicit identity 'default' (no hidden defaults)")
        if (hasDefault) {
            try {
                PartParser.typeCheck(type, m["default"], "$path.default")
            } catch (e: com.astarworks.astera.core.application.skillengine.part.PartAbort) {
                ctx.fail(e.error.path, e.error.message)
            }
        }
        return ParamSpec(name, type, required == true, m["default"])
    }

    private fun anchors(ctx: GrammarContext, raw: Any?): List<AnchorDecl> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: ctx.mismatch("anchors", "list", raw)
        val seen = HashSet<String>()
        return list.mapIndexed { i, e ->
            val p = "anchors[$i]"
            val m = e as? Map<*, *> ?: ctx.mismatch(p, "map {id, anchor, offset, mount, mode}", e)
            @Suppress("UNCHECKED_CAST")
            m as Map<String, Any?>
            checkKeys(ctx, m, p, setOf("id", "anchor", "offset", "mount", "mode"))
            val id = m["id"] as? String ?: ctx.abortWith(SkillDecodeError.MissingField(ctx.file, p, "id"))
            if (!seen.add(id)) ctx.abortWith(SkillDecodeError.DuplicateId(ctx.file, p, id))
            val anchor = m["anchor"] as? String ?: ctx.abortWith(SkillDecodeError.MissingField(ctx.file, p, "anchor"))
            if (anchor.isBlank() || anchor.startsWith('.') || anchor.endsWith('.')) ctx.mismatch("$p.anchor", "point reference (frame.point)", anchor)
            com.astarworks.astera.core.application.skillengine.compose.AnchorNames.removedCasterPoint(anchor)?.let { ctx.mismatch("$p.anchor", "a live point", it) }
            val mount = m["mount"]?.let { it as? String ?: ctx.mismatch("$p.mount", "'caster' or an anchor id", it) }
            val mode = when (val raw = m["mode"]) {
                null, "follow" -> AnchorMode.FOLLOW
                "snapshot" -> AnchorMode.SNAPSHOT
                else -> ctx.mismatch("$p.mode", "'follow' or 'snapshot'", raw)
            }
            val offset = LinkedHashMap<String, Double>()
            for ((axis, v) in mapAt(ctx, m["offset"], "$p.offset")) {
                offset[axis] = (v as? Number)?.toDouble() ?: ctx.mismatch("$p.offset.$axis", "number", v)
            }
            AnchorDecl(id, PointRef.parse(anchor), offset, mount, mode)
        }
    }

    // ---- small strict readers -----------------------------------------------------------------------------------------

    private fun checkKeys(ctx: GrammarContext, m: Map<*, *>, path: String, allowed: Set<String>) {
        for (k in m.keys) if (k !is String || k !in allowed) ctx.abortWith(SkillDecodeError.UnknownKey(ctx.file, path, k.toString()))
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapAt(ctx: GrammarContext, raw: Any?, path: String): Map<String, Any?> =
        if (raw == null) emptyMap() else raw as? Map<String, Any?> ?: ctx.mismatch(path, "map", raw)

    private fun stringMap(ctx: GrammarContext, raw: Any?, path: String): Map<String, String> =
        mapAt(ctx, raw, path).mapValues { (k, v) -> v as? String ?: ctx.mismatch("$path.$k", "string", v) }

    private fun stringList(ctx: GrammarContext, raw: Any?, path: String): List<String> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: ctx.mismatch(path, "list of strings", raw)
        return list.mapIndexed { i, e -> e as? String ?: ctx.mismatch("$path[$i]", "string", e) }
    }

    private fun intAt(ctx: GrammarContext, raw: Any, path: String): Int {
        val n = raw as? Number ?: ctx.mismatch(path, "integer", raw)
        if (n.toDouble() != Math.floor(n.toDouble())) ctx.mismatch(path, "integer", raw)
        return n.toInt()
    }

    private fun GrammarContext.abortWith(error: SkillDecodeError): Nothing = throw DecodeAbort(error)

    private companion object {
        val TOP_KEYS = setOf(
            "apiVersion", "kind", "id", "item", "importance", "meta", "params", "frames_in", "emits", "anchors", "conditions", "machine",
        )
        val META_KEYS = setOf("name", "description", "cooldown_ticks", "resource_type", "resource_cost", "ego_cost", "reload_ticks", "ready_cue", "tags")
        val STATE_KEYS = setOf("for", "then", "final", "enter", "do", "exit", "on", "use", "with", "frames", "start", "states", "parallel")
        val TRANSITION_KEYS = setOf("event", "after", "when", "to", "do")
    }
}
