package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.part.PatchApplier
import com.astarworks.astera.core.application.skillengine.part.PatchKind
import com.astarworks.astera.core.application.skillengine.part.PatchOp
import com.astarworks.astera.core.application.skillengine.part.PatchSeg
import com.astarworks.astera.core.application.skillengine.part.PatchVerb

/**
 * Check 13 (ADR-0303 R35): a patch changes only what its `patchKind` allows; the composer asks this before it applies an op (on the
 * tree as it is) and, for a skin, after (on the row as it became). It runs on the generic tree because a patch is applied before
 * the tree is compiled; the compiled table no longer has paths.
 *
 * - [PatchKind.SKIN]: presentation rows only. Inside `machine` a path `machine.states.<s>.{enter,do,exit}[<id>]...`, `machine.world[<id>]...`
 *   or `machine.states.<s>.on[<event>].do[<id>]...` whose deepest `[id]` row is a presentation op (or, for a whole-row add / replace /
 *   delete, a group of them). Inside such a row only the row's own fields change: a group takes whole-child operations only
 *   (`do[g].do[c]`), and an op's `on.<event>` lists are reached through their rows (`do[shot].on.hit[boom]`), never as a whole. In
 *   `params` only `params.<p>.default` of a `string` param, and only when every cell that reads `<p>` sits in a presentation row.
 * - [PatchKind.BALANCE]: `params.<p>.default` of a non-string param, and a numeric field of a gameplay-op row (`replace` with a number).
 * - [PatchKind.MECHANIC]: no restriction (the roots are already limited by `PatchApplier.decode`).
 */
internal object PatchKindCheck {
    /** [path] is the first offending leaf path as written; [why] says which rule it broke. */
    data class Violation(val path: String, val why: String)

    private val OP_LISTS = setOf("enter", "do", "exit")

    fun before(kind: PatchKind, op: PatchOp, base: Map<String, Any?>, presentationOps: Set<String>, gameplayOps: Set<String>): Violation? = when (kind) {
        PatchKind.SKIN -> skinBefore(op, base, presentationOps)
        PatchKind.BALANCE -> balanceBefore(op, base, gameplayOps)
        PatchKind.MECHANIC -> null
    }

    fun after(kind: PatchKind, op: PatchOp, merged: Map<String, Any?>, presentationOps: Set<String>): Violation? {
        if (kind != PatchKind.SKIN || op.segs[0].name != "machine") return null
        val deepest = op.segs.indices.last { op.segs[it].id != null }
        if (op.verb == PatchVerb.DELETE && deepest == op.segs.lastIndex) return null
        val row = rowAt(merged, op.segs, deepest) ?: return null
        return if (isPresentation(row, presentationOps)) null else Violation(op.path, "the row became a non-presentation row")
    }

    // ---- skin ----

    private fun skinBefore(op: PatchOp, base: Map<String, Any?>, presentationOps: Set<String>): Violation? {
        val segs = op.segs
        return when (segs[0].name) {
            "params" -> skinParam(op, base, presentationOps)
            "machine" -> skinMachine(op, base, presentationOps)
            else -> Violation(op.path, "a skin patch cannot touch '${segs[0].name}' (presentation rows and string params read by them only)")
        }
    }

    private fun skinParam(op: PatchOp, base: Map<String, Any?>, presentationOps: Set<String>): Violation? {
        val segs = op.segs
        if (segs.size != 3 || segs.any { it.id != null } || segs[2].name != "default" || op.verb != PatchVerb.REPLACE) {
            return Violation(op.path, "a skin patch may only replace params.<p>.default")
        }
        val name = segs[1].name
        val param = (base["params"] as? Map<*, *>)?.get(name) as? Map<*, *> ?: return null // unresolved: the applier reports it
        if (param["type"] != "string") return Violation(op.path, "param '$name' is a ${param["type"]} param; a skin patch may only change string params (colour, particle id)")
        val reader = firstReaderOutsidePresentation(base, name, presentationOps)
        return reader?.let { Violation(op.path, "param '$name' is also read by '$it', which is not a presentation row") }
    }

    private fun skinMachine(op: PatchOp, base: Map<String, Any?>, presentationOps: Set<String>): Violation? {
        val segs = op.segs
        val head = segs[0]
        val states = segs.getOrNull(1)
        val state = segs.getOrNull(2)
        val section = segs.getOrNull(3)
        // `machine.world[<id>]...` is the machine-level op list (ADR-0303 10.1): the same rule as a state's `enter` / `do` / `exit`
        val inWorld = head.id == null && states?.name == "world" && states.id != null
        val ok = inWorld || (head.id == null && states?.name == "states" && states.id == null &&
            state != null && state.id == null && section != null && section.id != null &&
            (section.name in OP_LISTS || (section.name == "on" && segs.getOrNull(4).let { it?.name == "do" && it.id != null })))
        if (!ok) return Violation(op.path, "a skin patch may only reach rows of machine.states.<s>.{enter,do,exit}, machine.world, or a transition's do list")
        val deepest = segs.indices.last { segs[it].id != null }
        val row = rowAt(base, segs, deepest)
        val rowPath = segs.take(deepest + 1).joinToString(".") { it.name + (it.id?.let { id -> "[$id]" } ?: "") }
        if (deepest == segs.lastIndex) return if (row == null || isPresentation(row, presentationOps)) null else Violation(op.path, "'$rowPath' is not a presentation row")
        if (row == null) return null // unresolved: the applier reports it
        if (isGroup(row)) return Violation(op.path, "'$rowPath' is a group; a skin patch addresses its children (do[g].do[c]) or replaces the whole row")
        if (!isPresentation(row, presentationOps)) return Violation(op.path, "'$rowPath' is not a presentation row")
        if (segs[deepest + 1].name == "on") return Violation(op.path, "an op's on.<event> lists are reached through their rows (on.<event>[<id>]), not as a whole")
        return null
    }

    // ---- balance ----

    private fun balanceBefore(op: PatchOp, base: Map<String, Any?>, gameplayOps: Set<String>): Violation? {
        val segs = op.segs
        return when (segs[0].name) {
            "params" -> {
                if (segs.size != 3 || segs.any { it.id != null } || segs[2].name != "default" || op.verb != PatchVerb.REPLACE) {
                    return Violation(op.path, "a balance patch may only replace params.<p>.default")
                }
                val param = (base["params"] as? Map<*, *>)?.get(segs[1].name) as? Map<*, *> ?: return null
                if (param["type"] == "string") Violation(op.path, "param '${segs[1].name}' is a string param (presentation); use a skin patch")
                else null
            }
            "machine" -> {
                val deepest = segs.indices.lastOrNull { segs[it].id != null }
                    ?: return Violation(op.path, "a balance patch may only change a numeric field of a gameplay row (addressed by [id])")
                val row = rowAt(base, segs, deepest) ?: return null
                val rowPath = segs.take(deepest + 1).joinToString(".") { it.name + (it.id?.let { id -> "[$id]" } ?: "") }
                val rowOp = row["op"] as? String
                if (rowOp == null || rowOp !in gameplayOps) return Violation(op.path, "'$rowPath' is not a gameplay op row")
                if (deepest == segs.lastIndex) return Violation(op.path, "a balance patch changes a field of '$rowPath', not the whole row")
                if (segs[deepest + 1].name in setOf("id", "op", "on")) return Violation(op.path, "'${segs[deepest + 1].name}' is structure, not a number to tune")
                if (op.verb != PatchVerb.REPLACE || op.value !is Number) return Violation(op.path, "a balance patch replaces a field with a number")
                null
            }
            else -> Violation(op.path, "a balance patch cannot touch '${segs[0].name}' (params.<p>.default and numeric fields of gameplay rows only)")
        }
    }

    // ---- shared ----

    /** The row selected by `segs[0..upTo]` in [tree], or null when any step is missing. */
    private fun rowAt(tree: Map<String, Any?>, segs: List<PatchSeg>, upTo: Int): Map<*, *>? {
        var node: Any? = tree
        for (i in 0..upTo) {
            val m = node as? Map<*, *> ?: return null
            val child = m[segs[i].name]
            node = if (segs[i].id == null) child else (child as? List<*>)?.singleOrNull { PatchApplier.matches(it, segs[i].id!!) }
        }
        return node as? Map<*, *>
    }

    private fun isGroup(node: Map<*, *>): Boolean = node["op"] == null && node["do"] is List<*>

    /** A presentation op, or a group whose children are all presentation. `use` calls are never presentation. */
    private fun isPresentation(node: Map<*, *>, presentationOps: Set<String>): Boolean {
        val op = node["op"] as? String
        if (op != null) return op in presentationOps
        val children = node["do"] as? List<*> ?: return false
        return children.all { it is Map<*, *> && isPresentation(it, presentationOps) }
    }

    /**
     * The path of the first cell outside every presentation row that reads param [name] (`${name}` anywhere, or the identifier inside a
     * `= expr` string); null when only presentation rows read it. `params` itself is not scanned.
     */
    private fun firstReaderOutsidePresentation(tree: Map<String, Any?>, name: String, presentationOps: Set<String>): String? {
        val ident = Regex("(?<![A-Za-z0-9_.])" + Regex.escape(name) + "(?![A-Za-z0-9_])")
        fun reads(s: String): Boolean = s.contains("\${$name}") || (s.startsWith("= ") && ident.containsMatchIn(s))
        fun walk(node: Any?, inPresentation: Boolean, path: String): String? = when (node) {
            is String -> if (!inPresentation && reads(node)) path else null
            is Map<*, *> -> {
                val pres = inPresentation || (node["op"] as? String)?.let { it in presentationOps } == true
                val label = (node["id"] as? String)?.let { "$path[$it]" } ?: path
                node.entries.firstNotNullOfOrNull { (k, v) -> walk(v, pres, if (label.isEmpty()) "$k" else "$label.$k") }
            }
            is List<*> -> node.withIndex().firstNotNullOfOrNull { (i, v) -> walk(v, inPresentation, "$path[$i]") }
            else -> null
        }
        return tree.entries.filter { it.key != "params" }.firstNotNullOfOrNull { (k, v) -> walk(v, false, k) }
    }
}
