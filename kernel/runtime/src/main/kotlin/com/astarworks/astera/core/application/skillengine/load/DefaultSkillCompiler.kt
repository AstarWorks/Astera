package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.model.FrameDecl
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import java.lang.reflect.Modifier

/**
 * Compiles every `Value.Expr` once and checks `${param}`, frame and point references.
 *
 * The def is walked by reflection over the skill-engine data classes, so a new op family needs no change here (no
 * central `when`). Paths read `gameplay.on.release[fly].velocity` (ops by id, timeline rows by index).
 * Frame-name fields are recognised by name: `frame`, `frames`, `parent`, `like`, `to`, `scope` (String / List<String>)
 * inside any skill-engine class; every [PointRef] is checked too.
 *
 * Expression sugar: `<frame>.<point>` in an expression (e.g. `speed(shears.tip)`) is rewritten to
 * `point(<frame>,"<point>")` before CEL compiles it, because CEL reads the dotted form as field selection.
 *
 * [pointTables] maps a model id (`FrameDecl.model`, F-3) to its [ItemFrames]; frames whose table is unknown skip the
 * point-name check (only the frame name is verified).
 */
public class DefaultSkillCompiler(
    private val exprs: ExprCompiler,
    private val pointTables: (String) -> ItemFrames? = { null },
) : SkillCompiler {

    override fun compile(def: SkillDef): Result<CompiledSkill, SkillDecodeError> =
        try {
            Result.success(Run(def).run())
        } catch (e: DecodeAbort) {
            Result.failure(e.error)
        }

    private inner class Run(val def: SkillDef) {
        val frameDecls: Map<String, FrameDecl> = def.frames.associateBy { it.id }
        val frameNames: Set<String> = frameDecls.keys + SectionRules.BUILTIN_FRAMES
        val paramNames: Set<String> =
            def.gameplay.params.keys + def.presentation.params.keys + def.gameplay.expose.map { it.name }
        val spec: ExprEnvSpec = envSpec()
        val compiled = LinkedHashMap<String, CompiledExpr>()
        val cache = HashMap<String, CompiledExpr>()

        fun envSpec(): ExprEnvSpec {
            val vars = LinkedHashMap<String, ExprType>()
            (def.gameplay.params + def.presentation.params).forEach { (k, v) ->
                vars[k] = when (v) {
                    is ParamValue.Num -> ExprType.NUM
                    is ParamValue.Text -> ExprType.STR
                    is ParamValue.Flag -> ExprType.BOOL
                }
            }
            def.gameplay.expose.forEach { vars.putIfAbsent(it.name, ExprType.NUM) }
            vars["tick"] = ExprType.NUM
            vars["event.source"] = ExprType.STR
            vars["contact.point"] = ExprType.VEC3
            vars["contact.normal"] = ExprType.VEC3
            vars["contact.rel_velocity"] = ExprType.VEC3
            vars["contact.block"] = ExprType.BOOL
            val frames = frameNames - "self" - "line" - "contact" + "victim"
            return ExprEnvSpec(vars, frames)
        }

        fun run(): CompiledSkill {
            for (f in def.frames) checkPoint(f.parent, "frames[${f.id}].parent")
            walk(def.gameplay.timeline, "gameplay.timeline")
            walk(def.gameplay.on, "gameplay.on")
            walk(def.presentation.timeline, "presentation.timeline")
            walk(def.presentation.on, "presentation.on")
            return CompiledSkill(def, compiled)
        }

        fun undefined(path: String, ref: String): Nothing =
            throw DecodeAbort(SkillDecodeError.UndefinedReference(def.id, path, ref))

        fun checkFrame(name: String, path: String) {
            if (name !in frameNames) undefined(path, name)
        }

        fun checkPoint(ref: PointRef, path: String) {
            checkFrame(ref.frame, path)
            val point = ref.point ?: return
            val decl = frameDecls[ref.frame] ?: return
            if (decl.kind != FrameKind.PART && decl.kind != FrameKind.ITEM) return
            val table = decl.model?.let(pointTables) ?: return
            val part = table.partFor(if (decl.kind == FrameKind.PART) decl.part else null) ?: return
            if (point !in part.points && point !in part.axes && point != "axis") undefined(path, ref.toString())
        }

        val pointFrames: Set<String> = frameNames - "self" - "line" - "contact"
        val dotted = Regex("""(?<![\w.])([A-Za-z_]\w*)\.([A-Za-z_]\w*)(?!\w|\s*\()""")

        /** `shears.tip` (frame point sugar) -> `point(shears,"tip")`; string literals and `contact.*`/`event.*` are left alone. */
        fun rewritePoints(source: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < source.length) {
                val ch = source[i]
                if (ch == '\'' || ch == '"') {
                    var j = i + 1
                    while (j < source.length && source[j] != ch) j += if (source[j] == '\\') 2 else 1
                    val end = minOf(j + 1, source.length)
                    sb.append(source, i, end)
                    i = end
                } else {
                    var j = i
                    while (j < source.length && source[j] != '\'' && source[j] != '"') j++
                    sb.append(dotted.replace(source.substring(i, j)) { m ->
                        if (m.groupValues[1] in pointFrames) "point(${m.groupValues[1]},\"${m.groupValues[2]}\")" else m.value
                    })
                    i = j
                }
            }
            return sb.toString()
        }

        fun checkExpr(source: String, path: String) {
            val c = cache[source] ?: when (val r = exprs.compile(rewritePoints(source), spec)) {
                is Result.Success -> r.value.also { cache[source] = it }
                is Result.Failure -> throw DecodeAbort(SkillDecodeError.BadExpr(def.id, path, r.error))
            }
            compiled[path] = c
        }

        fun walk(node: Any?, path: String) {
            when (node) {
                null, is String, is Number, is Boolean, is Enum<*> -> Unit
                is Value.Expr -> checkExpr(node.source, path)
                is Value.ParamRef -> if (node.name !in paramNames) undefined(path, "\${${node.name}}")
                is Value.Literal<*> -> Unit
                is PointRef -> checkPoint(node, path)
                is List<*> -> node.forEachIndexed { i, e ->
                    val seg = if (e is SkillOp) "[${e.id}]" else "[$i]"
                    walk(e, path + seg)
                }
                is Map<*, *> -> node.forEach { (k, v) -> walk(v, "$path.$k") }
                is Pair<*, *> -> {
                    walk(node.first, "$path.first")
                    walk(node.second, "$path.second")
                }
                else -> if (node::class.java.name.startsWith(ENGINE_PACKAGE)) walkFields(node, path)
            }
        }

        fun walkFields(node: Any, path: String) {
            var cls: Class<*>? = node::class.java
            while (cls != null && cls.name.startsWith(ENGINE_PACKAGE)) {
                for (f in cls.declaredFields) {
                    if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                    f.isAccessible = true
                    val v = f.get(node)
                    val p = "$path.${f.name}"
                    if (f.name in FRAME_NAME_FIELDS) checkFrameNames(v, p)
                    walk(v, p)
                }
                cls = cls.superclass
            }
        }

        fun checkFrameNames(v: Any?, path: String) {
            when (v) {
                is String -> checkFrame(v, path)
                is List<*> -> v.forEachIndexed { i, e -> if (e is String) checkFrame(e, "$path[$i]") }
            }
        }
    }

    private companion object {
        const val ENGINE_PACKAGE = "com.astarworks.astera.core.domain.skillengine"
        val FRAME_NAME_FIELDS = setOf("frame", "frames", "parent", "like", "to", "scope")
    }
}
