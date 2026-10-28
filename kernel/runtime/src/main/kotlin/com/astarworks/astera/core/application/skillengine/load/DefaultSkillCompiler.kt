package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.skillengine.op.VisualOp
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.motion.AlignSpec
import com.astarworks.astera.core.domain.skillengine.model.FrameDecl
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

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
        /** A mesh or plate is a node of the frame tree under its op id (ADR-0303), so its id is a valid frame reference. */
        val displayNodes: Set<String> = LinkedHashSet<String>().also {
            scanDisplayNodes(def.presentation.timeline, it)
            scanDisplayNodes(def.presentation.on, it)
        }
        val frameNames: Set<String> = frameDecls.keys + SectionRules.BUILTIN_FRAMES + displayNodes
        val paramNames: Set<String> =
            def.gameplay.params.keys + def.presentation.params.keys + def.gameplay.expose.map { it.name }
        val exprSpec: SkillExprSpec = SkillExprSpec.of(def)
        val spec: ExprEnvSpec = exprSpec.spec
        val compiled = LinkedHashMap<String, CompiledExpr>()
        val cache = HashMap<String, CompiledExpr>()

        fun run(): CompiledSkill {
            for (f in def.frames) checkPoint(f.parent, "frames[${f.id}].parent")
            walk(def.gameplay.timeline, "gameplay.timeline")
            walk(def.gameplay.on, "gameplay.on")
            walk(def.presentation.timeline, "presentation.timeline")
            walk(def.presentation.on, "presentation.on")
            return CompiledSkill(def, compiled, cache.toMap())
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
            val model = decl.model ?: undefined(path, "frame '${ref.frame}' has a point '$point' but declares no model")
            val table = pointTables(model) ?: undefined(path, "model '$model' (frame '${ref.frame}') has no content/frames table")
            val part = table.partFor(if (decl.kind == FrameKind.PART) decl.part else null)
                ?: undefined(path, "part '${decl.part}' of model '$model' (frame '${ref.frame}')")
            if (point !in part.points && point !in part.axes && point != "axis") undefined(path, ref.toString())
        }

        fun checkExpr(source: String, path: String, expected: ExprType?) {
            val key = "$expected|$source"
            val c = cache[key] ?: when (val r = exprs.compile(exprSpec.rewrite(source), spec, expected)) {
                is Result.Success -> r.value.also { cache[key] = it }
                is Result.Failure -> throw DecodeAbort(SkillDecodeError.BadExpr(def.id, path, r.error))
            }
            compiled[path] = c
        }

        /** [type] is the declared (generic) type of the slot [node] sits in; it tells a `Value.Expr` what result it must produce. */
        fun walk(node: Any?, path: String, type: Type? = null) {
            when (node) {
                null, is String, is Number, is Boolean, is Enum<*> -> Unit
                is Value.Expr -> checkExpr(node.source, path, expectedOf(type))
                is Value.ParamRef -> if (node.name !in paramNames) undefined(path, "\${${node.name}}")
                is Value.Literal<*> -> Unit
                is PointRef -> checkPoint(node, path)
                is List<*> -> node.forEachIndexed { i, e ->
                    val seg = if (e is SkillOp) "[${e.id}]" else "[$i]"
                    walk(e, path + seg, typeArg(type, 0))
                }
                is Map<*, *> -> node.forEach { (k, v) -> walk(v, "$path.$k", typeArg(type, 1)) }
                is Pair<*, *> -> {
                    walk(node.first, "$path.first", typeArg(type, 0))
                    walk(node.second, "$path.second", typeArg(type, 1))
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
                    // AlignSpec.to is an axis name (world.up, velocity, shears.axis), not a frame.
                    if (f.name in FRAME_NAME_FIELDS && node !is AlignSpec) checkFrameNames(v, p)
                    walk(v, p, f.genericType)
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

    private fun upper(t: Type?): Type? = if (t is WildcardType) t.upperBounds.firstOrNull() else t

    private fun typeArg(t: Type?, i: Int): Type? = (upper(t) as? ParameterizedType)?.actualTypeArguments?.getOrNull(i)

    /** `Value<Double>` -> NUM, `Value<Boolean>` -> BOOL, `Value<String>` -> STR, `Value<Vec3>` -> VEC3, `Value<Quaternion>` -> QUAT; anything else: no expectation. */
    private fun expectedOf(t: Type?): ExprType? {
        val p = upper(t) as? ParameterizedType ?: return null
        if (p.rawType != Value::class.java) return null
        return when ((upper(p.actualTypeArguments.firstOrNull()) as? Class<*>)?.name) {
            "java.lang.Double", "java.lang.Float", "java.lang.Integer", "java.lang.Long", "java.lang.Number" -> ExprType.NUM
            "java.lang.Boolean" -> ExprType.BOOL
            "java.lang.String" -> ExprType.STR
            "com.astarworks.astera.core.domain.geometry.Vec3" -> ExprType.VEC3
            "com.astarworks.astera.core.domain.geometry.math.Quaternion" -> ExprType.QUAT
            else -> null
        }
    }

    private fun scanDisplayNodes(node: Any?, out: MutableSet<String>) {
        when (node) {
            null, is String, is Number, is Boolean, is Enum<*> -> Unit
            is VisualOp.Mesh -> out += node.id
            is VisualOp.Plate -> out += node.id
            is VisualOp.Block -> out += node.id
            is VisualOp.Quad -> out += node.id
            is List<*> -> node.forEach { scanDisplayNodes(it, out) }
            is Map<*, *> -> node.values.forEach { scanDisplayNodes(it, out) }
            else -> {
                var cls: Class<*>? = node::class.java
                while (cls != null && cls.name.startsWith(ENGINE_PACKAGE)) {
                    for (f in cls.declaredFields) {
                        if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                        f.isAccessible = true
                        scanDisplayNodes(f.get(node), out)
                    }
                    cls = cls.superclass
                }
            }
        }
    }

    private companion object {
        const val ENGINE_PACKAGE = "com.astarworks.astera.core.domain.skillengine"
        val FRAME_NAME_FIELDS = setOf("frame", "frames", "parent", "like", "to", "scope")
    }
}
