package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.SkillDef

/**
 * The expression environment of one skill, shared by the compiler (load time) and the cast runner (run time) so
 * both type-check against exactly the same declarations. Variables: params after `${}`, exposed names, `tick`,
 * `event.name|source`, `contact.point|normal|rel_velocity|block`, `world.up`; frames: the declared ones plus
 * `world caster enemy` and `victim` (the body centre of the current contact).
 *
 * `<frame>.<point>` in expression text (e.g. `speed(shears.tip)`) is sugar for `point(<frame>,"<point>")`; CEL
 * would read it as field selection. Dotted names that are declared variables (`world.up`, `contact.point`) stay.
 */
public class SkillExprSpec private constructor(public val spec: ExprEnvSpec, private val pointFrames: Set<String>) {

    /** `shears.tip` -> `point(shears,"tip")`; string literals and declared dotted variables are left alone. */
    public fun rewrite(source: String): String {
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
                sb.append(DOTTED.replace(source.substring(i, j)) { m ->
                    val frame = m.groupValues[1]
                    val field = m.groupValues[2]
                    if ("$frame.$field" in spec.variables || frame !in pointFrames) m.value else "point($frame,\"$field\")"
                })
                i = j
            }
        }
        return sb.toString()
    }

    public companion object {
        private val DOTTED = Regex("""(?<![\w.])([A-Za-z_]\w*)\.([A-Za-z_]\w*)(?!\w|\s*\()""")

        public fun of(def: SkillDef): SkillExprSpec {
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
            vars["i"] = ExprType.NUM
            vars["n"] = ExprType.NUM
            vars["event.name"] = ExprType.STR
            vars["event.source"] = ExprType.STR
            vars["contact.point"] = ExprType.VEC3
            vars["contact.normal"] = ExprType.VEC3
            vars["contact.rel_velocity"] = ExprType.VEC3
            vars["contact.block"] = ExprType.STR
            vars["world.up"] = ExprType.VEC3
            vars["incoming.amount"] = ExprType.NUM
            val frameNames = def.frames.mapTo(LinkedHashSet()) { it.id } + SectionRules.BUILTIN_FRAMES
            val frames = frameNames - "self" - "line" - "contact" + "victim"
            return SkillExprSpec(ExprEnvSpec(vars, frames), frameNames - "self" - "line" - "contact")
        }
    }
}
