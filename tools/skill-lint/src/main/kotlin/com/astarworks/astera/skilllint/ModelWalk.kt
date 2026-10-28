package com.astarworks.astera.skilllint

import com.astarworks.astera.core.domain.skillengine.model.PresentationSection
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.model.TimelineAt
import com.astarworks.astera.core.domain.skillengine.model.TimelineEntry
import com.astarworks.astera.core.domain.skillengine.op.ControlOp
import com.astarworks.astera.core.domain.skillengine.op.GameplayOp
import com.astarworks.astera.core.domain.skillengine.op.MovementOp
import com.astarworks.astera.core.domain.skillengine.op.PresentationOp
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.SpawnOp
import java.lang.reflect.Modifier

/** Where an op sits. [hitScope]: a contact (`victim`, `contact.*`) is in scope. */
data class OpSite<out O : SkillOp>(
    val op: O,
    val path: String,
    val hitScope: Boolean,
    /** Event name for `on:` handlers, null for timeline rows. */
    val event: String?,
    val entry: TimelineEntry<*>?,
    val entryIndex: Int?,
)

object ModelWalk {
    /** Every gameplay op including nested bodies (if / loop / for_each / dash on_hit / projectile / aoe). */
    fun gameplayOps(def: SkillDef): List<OpSite<GameplayOp>> {
        val out = ArrayList<OpSite<GameplayOp>>()
        def.gameplay.on.forEach { (event, ops) ->
            ops.forEach { descend(it, "gameplay.on.$event", event == "hit", event, null, null, out) }
        }
        def.gameplay.timeline.forEachIndexed { i, entry ->
            entry.ops.forEach { descend(it, "gameplay.timeline[$i].do", false, null, entry, i, out) }
        }
        return out
    }

    fun presentationOps(p: PresentationSection): List<OpSite<PresentationOp>> {
        val out = ArrayList<OpSite<PresentationOp>>()
        p.on.forEach { (event, ops) ->
            ops.forEach { out += OpSite(it, "presentation.on.$event[${it.id}]", event == "hit", event, null, null) }
        }
        p.timeline.forEachIndexed { i, entry ->
            entry.ops.forEach { out += OpSite(it, "presentation.timeline[$i].do[${it.id}]", false, null, entry, i) }
        }
        return out
    }

    private fun descend(
        op: GameplayOp,
        base: String,
        hit: Boolean,
        event: String?,
        entry: TimelineEntry<*>?,
        index: Int?,
        out: MutableList<OpSite<GameplayOp>>,
    ) {
        val here = "$base[${op.id}]"
        out += OpSite(op, here, hit, event, entry, index)
        fun kids(list: List<GameplayOp>, sub: String, h: Boolean) =
            list.forEach { descend(it, "$here.$sub", h, event, entry, index, out) }
        when (op) {
            is ControlOp.If -> { kids(op.then, "then", hit); kids(op.otherwise, "otherwise", hit) }
            is ControlOp.Loop -> kids(op.body, "body", hit)
            is ControlOp.Delay -> kids(op.body, "body", hit)
            is ControlOp.Window -> kids(op.body, "do", true)
            is ControlOp.Choose -> {
                op.cases.forEachIndexed { i, c -> kids(c.second, "cases[$i]", hit) }
                kids(op.otherwise, "otherwise", hit)
            }
            is ControlOp.WeightedBranch -> op.branches.forEachIndexed { i, b -> kids(b.second, "branches[$i]", hit) }
            is ControlOp.ForEach -> kids(op.body, "body", true)
            is MovementOp.DashStrike -> kids(op.onHit, "on_hit", true)
            is SpawnOp.Projectile -> {
                kids(op.onHit, "on_hit", true)
                kids(op.onBlock, "on_block", false)
                kids(op.onExpire, "on_expire", false)
            }
            is SpawnOp.Aoe -> kids(op.onHit, "on_hit", true)
            else -> Unit
        }
    }

    /** Absolute tick of a timeline row, or null for event-relative rows. */
    fun absoluteTick(entry: TimelineEntry<*>): Int? = (entry.at as? TimelineAt.Tick)?.tick

    /**
     * Visits [root] and every nested model object (data classes, lists, maps, pairs) by field reflection, but does
     * NOT enter other [SkillOp]s (they are separate sites). [visit] gets the node and its field path.
     */
    fun visitFields(root: Any, path: String, visit: (Any, String) -> Unit) {
        fun rec(node: Any?, p: String, top: Boolean) {
            when (node) {
                null, is String, is Number, is Boolean, is Enum<*>, is Char -> return
                is Iterable<*> -> node.forEachIndexed { i, e -> rec(e, "$p[$i]", false) }
                is Map<*, *> -> node.forEach { (k, v) -> rec(v, "$p.$k", false) }
                is Pair<*, *> -> { rec(node.first, "$p.first", false); rec(node.second, "$p.second", false) }
                else -> {
                    if (node is SkillOp && !top) return
                    if (!node.javaClass.name.startsWith("com.astarworks.astera.core.domain")) return
                    visit(node, p)
                    for (f in node.javaClass.declaredFields) {
                        if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                        f.isAccessible = true
                        rec(f.get(node), "$p.${f.name}", false)
                    }
                }
            }
        }
        rec(root, path, true)
    }
}
