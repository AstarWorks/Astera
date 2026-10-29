package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.domain.skillengine.part.Machine

/**
 * The frame names an expression may use besides the anchors (ADR-0303 3.5: a frame function takes "an anchor", and a frame / projectile op IS an anchor, 1.1):
 * the `id` of every `frame` and `projectile` op of the skill, and `<id>.<point>` for a frame op that has a `part` (the part's model points, `blade[1].tip` in 1.1).
 * A name is resolved to the live instance when the row plays; a family member (`blade[2]`) is not an identifier and is not named here.
 */
public object ExprFrames {
    /** The model points a part can name in an expression (`content/frames/<item>.yaml` `points:`). */
    public val POINTS: List<String> = listOf("tip", "root", "pivot", "grip")

    public fun ofMachine(machine: Machine): Set<String> {
        val out = LinkedHashSet<String>()
        fun machine(m: Machine) {
            for (s in m.states.values) {
                walk(s.enter, out)
                walk(s.doOps, out)
                walk(s.exit, out)
                for (t in s.on) walk(t.effects, out)
                s.parallel.forEach(::machine)
            }
        }
        machine(machine)
        for (op in machine.world) walk(op, out)
        return out
    }

    /** `id -> part` of every `frame` / `projectile` op of the skill (part null = the op has none): the frames whose model constants an expression may read. */
    public fun partsOf(machine: Machine): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        fun machine(m: Machine) {
            for (s in m.states.values) {
                parts(s.enter, out); parts(s.doOps, out); parts(s.exit, out)
                for (t in s.on) parts(t.effects, out)
                s.parallel.forEach(::machine)
            }
        }
        machine(machine)
        for (op in machine.world) parts(op, out)
        return out
    }

    /** [partsOf] from an undecoded `machine:` tree. */
    public fun partsOfRaw(machine: Any?): Map<String, String?> = LinkedHashMap<String, String?>().also { parts(machine, it) }

    private fun parts(node: Any?, out: MutableMap<String, String?>) {
        when (node) {
            is Map<*, *> -> {
                val id = (node["id"] as? String)?.substringAfterLast('/')
                val op = node["op"] as? String
                if (id != null && (op == "frame" || op == "projectile") && id.all { it.isLetterOrDigit() || it == '_' }) out[id] = node["part"] as? String
                for (v in node.values) parts(v, out)
            }
            is Iterable<*> -> for (v in node) parts(v, out)
            else -> Unit
        }
    }

    /** The same names from an undecoded `machine:` tree. */
    public fun ofRaw(machine: Any?): Set<String> = LinkedHashSet<String>().also { walk(machine, it) }

    private fun walk(node: Any?, out: MutableSet<String>) {
        when (node) {
            is Map<*, *> -> {
                val id = (node["id"] as? String)?.substringAfterLast('/')
                val op = node["op"] as? String
                if (id != null && (op == "frame" || op == "projectile" || op == "body" || op == "rig") && id.all { it.isLetterOrDigit() || it == '_' }) {
                    out += id
                    if ((op == "frame" && node["part"] != null) || op == "rig") for (p in POINTS) out += "$id.$p"
                }
                for (v in node.values) walk(v, out)
            }
            is Iterable<*> -> for (v in node) walk(v, out)
            else -> Unit
        }
    }
}
