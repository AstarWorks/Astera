package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineState

/**
 * The S-class CEL functions `part_length(anchor)` and `part_point(anchor, point)` (ADR-0303 3.5, G7): the size and model points of the
 * `content/frames/<item>.yaml` part a `frame` op names with `part:`. `anchor` is the id of such a frame op (a family's op id addresses every member, as its parts are one); the part is looked up in the skill's `item:` table. Resolved ONCE at expansion, before any op is decoded, by
 * replacing the call with its number (`part_length`) or `vec(x, y, z)` (`part_point`), so the expansion table (and its dump) shows plain
 * numbers and the sim and Paper players, which both run this compiler, never evaluate the call. Class S by construction.
 *
 * Named load errors: an anchor that is no frame op with a `part:`, a skill without `item:`, a missing frames file, a missing part, a missing point.
 */
internal class PartFunctionExpander(private val skillId: String, private val item: String?, private val frames: FrameTables) {
    private var error: SkillDecodeError? = null
    private var partOf: Map<String, String> = emptyMap()

    /** [machine] with every call replaced, or the first load error. */
    fun expand(machine: Machine, conditions: Map<String, String>): Result<Pair<Machine, Map<String, String>>, SkillDecodeError> {
        val parts = LinkedHashMap<String, String>()
        collect(machine, parts)
        partOf = parts
        val m = machine(machine, "machine")
        val c = conditions.mapValues { (k, v) -> str(v, "conditions.$k") }
        return error?.let { Result.Failure(it) } ?: Result.Success(m to c)
    }

    private fun collect(m: Machine, out: MutableMap<String, String>) {
        for (s in m.states.values) {
            (s.enter + s.doOps + s.exit + s.on.flatMap { it.effects }).forEach { scan(it, out) }
            s.parallel.forEach { collect(it, out) }
        }
        m.world.forEach { scan(it, out) }
    }

    private fun scan(v: Any?, out: MutableMap<String, String>) {
        when (v) {
            is Map<*, *> -> {
                val id = v["id"] as? String
                val part = v["part"] as? String
                if (v["op"] == "frame" && id != null && part != null) out[id.substringAfterLast('/')] = part
                v.values.forEach { scan(it, out) }
            }
            is List<*> -> v.forEach { scan(it, out) }
            else -> {}
        }
    }

    private fun machine(m: Machine, at: String): Machine =
        m.copy(states = m.states.mapValues { (k, s) -> state(s, "$at.states.$k") }, world = m.world.map { any(it, "$at.world") as Map<String, Any?> })

    @Suppress("UNCHECKED_CAST")
    private fun state(s: MachineState, at: String): MachineState = s.copy(
        forTicks = any(s.forTicks, "$at.for"),
        enter = s.enter.map { any(it, "$at.enter") as Map<String, Any?> },
        doOps = s.doOps.map { any(it, "$at.do") as Map<String, Any?> },
        exit = s.exit.map { any(it, "$at.exit") as Map<String, Any?> },
        on = s.on.map { t -> t.copy(whenExpr = any(t.whenExpr, "$at.on") as String?, effects = t.effects.map { any(it, "$at.on") as Map<String, Any?> }) },
        parallel = s.parallel.map { machine(it, "$at.parallel") },
    )

    private fun any(v: Any?, at: String): Any? = when (v) {
        is String -> str(v, at)
        is Map<*, *> -> LinkedHashMap<Any?, Any?>().also { out -> for ((k, x) in v) out[k] = any(x, "$at.$k") }
        is List<*> -> v.map { any(it, at) }
        else -> v
    }

    private fun str(s: String, at: String): String {
        if (error != null || (!s.contains("part_length(") && !s.contains("part_point("))) return s
        var out = LENGTH.replace(s) { m -> number(at, m.value, m.groupValues[1]) { it.length }?.let { "($it)" } ?: m.value }
        out = POINT.replace(out) { m ->
            val point = m.groupValues.drop(2).first { it.isNotEmpty() }
            number(at, m.value, m.groupValues[1]) { it.points[point] ?: run { fail<Any>(at, "${m.value}: part '${partName(m.groupValues[1])}' has no point '$point' (points: ${it.points.keys.sorted()})"); null } }
                ?.let { p -> "vec(${p.x}, ${p.y}, ${p.z})" } ?: m.value
        }
        return out
    }

    private fun partName(anchor: String): String = partOf[anchor] ?: "?"

    private fun <T> number(at: String, call: String, anchor: String, read: (com.astarworks.astera.core.application.skillengine.load.ModelPart) -> T?): T? {
        val part = partOf[anchor] ?: return fail(at, "$call: '$anchor' is not a frame op with a part: (frame ops with a part: ${partOf.keys.sorted()})")
        val it = item ?: return fail(at, "$call: needs the skill's item to find content/frames/<item>.yaml, but this skill has no item")
        val table = frames.item(it) ?: return fail(at, "$call: content/frames/$it.yaml not found")
        val model = table.partFor(part) ?: return fail(at, "$call: content/frames/$it.yaml has no part '$part'")
        return read(model)
    }

    private fun <T> fail(at: String, message: String): T? {
        if (error == null) error = SkillDecodeError.Invalid(skillId, at, message)
        return null
    }

    private companion object {
        private const val ANCHOR = """([A-Za-z_][A-Za-z0-9_]*)"""
        val LENGTH = Regex("""part_length\(\s*$ANCHOR\s*\)""")
        val POINT = Regex("""part_point\(\s*$ANCHOR\s*,\s*(?:"([A-Za-z_][A-Za-z0-9_]*)"|'([A-Za-z_][A-Za-z0-9_]*)')\s*\)""")
    }
}
