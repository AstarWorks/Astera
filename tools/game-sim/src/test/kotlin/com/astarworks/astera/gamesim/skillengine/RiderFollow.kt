package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import kotlin.math.sqrt

/**
 * Rider-follows-parent rule (se-grenade). A display whose `anchor` is a projectile body must stay at the same offset from it at every tick it lives:
 * a body that flies, bounces or reflects drags its displays along. Read from the expanded golden recording: the body's world position is the `frame` row of its op
 * (traced at the end of each tick), the display's is its last `spawn` / `write` row at or before that tick. The offset at the display's spawn tick is the
 * authored one; any later tick where it differs by more than [TOLERANCE] blocks is a problem (a display left behind at its spawn pose, or lagging a rebase).
 * The riders and their parents come from the compiled table (`Spawn.anchor`), so a skill with no rider is never judged.
 */
internal object RiderFollow {
    const val TOLERANCE = 0.02
    private val ROW = Regex("^@(\\d+) (spawn|write|remove|frame) (\\S+) op=(\\S+)")
    private val POS = Regex(" pos=(\\S+),(\\S+),(\\S+)")
    private val INDEX = Regex("\\[(\\d+)]$")

    /** (rider op, parent op) pairs of [skill]: a display spawn without motion of its own whose anchor instance is a projectile body of the same table. */
    private fun pairs(skill: CompiledSkill): List<Pair<String, String>> {
        val t = skill.table
        val rows = t.worldRows + t.states.flatMap { it.rows } + t.subTables.values.flatMap { it.rows }
        val spawns = rows.mapNotNull { r -> (r.action as? Action.Spawn)?.let { r to it } }
        val parents = spawns.filter { it.second.kind == SpawnKind.PROJECTILE_BODY }.map { it.first.instance.text }.toSet()
        // a rider with its own motion (Key rows) moves against its parent on purpose; only a rider that holds its authored offset can be judged by a constant offset
        val moving = rows.mapNotNull { (it.action as? Action.Key)?.display?.text }.toSet()
        val shown = setOf(SpawnKind.ITEM, SpawnKind.BLOCK, SpawnKind.TEXT, SpawnKind.SPRITE)
        return spawns.filter { it.second.kind in shown }.mapNotNull { (r, a) ->
            val anchor = a.anchor?.text ?: return@mapNotNull null
            if (anchor in parents && r.instance.text !in moving) r.instance.text to anchor else null
        }.distinct()
    }

    fun check(key: String, skill: CompiledSkill, lines: List<String>): List<String> {
        val pairs = pairs(skill)
        if (pairs.isEmpty()) return emptyList()
        val problems = ArrayList<String>()
        var pose = ""
        var section = ArrayList<String>()
        fun judge() {
            if (!pose.startsWith("pose")) return
            val parentAt = HashMap<String, HashMap<Int, DoubleArray>>()
            for (l in section) {
                val m = ROW.find(l) ?: continue
                if (m.groupValues[2] != "frame") continue
                val p = POS.find(l) ?: continue
                parentAt.getOrPut(m.groupValues[4]) { HashMap() }[m.groupValues[1].toInt()] = doubleArrayOf(p.groupValues[1].toDouble(), p.groupValues[2].toDouble(), p.groupValues[3].toDouble())
            }
            // per display handle: its op, the last placed position, the offset it spawned with
            class Rider(val op: String, var pos: DoubleArray? = null, var offset: DoubleArray? = null, var reported: Boolean = false)
            val riders = HashMap<String, Rider>()
            fun parentOf(op: String): String? {
                val bare = op.replace(INDEX, "")
                val idx = INDEX.find(op)?.value ?: ""
                return pairs.firstOrNull { it.first == bare }?.second?.let { it + idx }
            }
            val byTick = section.mapNotNull { l -> ROW.find(l)?.let { it.groupValues[1].toInt() to l } }.groupBy({ it.first }, { it.second })
            for (t in 0..(byTick.keys.maxOrNull() ?: -1)) {
                for (l in byTick[t].orEmpty()) {
                    val m = ROW.find(l)!!
                    val verb = m.groupValues[2]
                    val id = m.groupValues[3]
                    if (verb == "remove") { riders.remove(id); continue }
                    if (verb == "frame") continue
                    val op = m.groupValues[4]
                    if (verb == "spawn") riders[id] = Rider(op)
                    val r = riders[id] ?: continue
                    val p = POS.find(l) ?: continue
                    r.pos = doubleArrayOf(p.groupValues[1].toDouble(), p.groupValues[2].toDouble(), p.groupValues[3].toDouble())
                }
                for ((id, r) in riders) {
                    val parent = parentOf(r.op) ?: continue
                    val pp = parentAt[parent]?.get(t) ?: continue
                    val rp = r.pos ?: continue
                    val off = doubleArrayOf(rp[0] - pp[0], rp[1] - pp[1], rp[2] - pp[2])
                    val base = r.offset
                    if (base == null) { r.offset = off; continue }
                    val d = sqrt((0..2).sumOf { (off[it] - base[it]) * (off[it] - base[it]) })
                    if (d > TOLERANCE && !r.reported) {
                        r.reported = true
                        problems += "$key [$pose] @$t: display ${r.op} ($id) is ${"%.2f".format(d)} blocks off its anchor $parent (offset ${base.joinToString(",") { "%.2f".format(it) }} at spawn, ${off.joinToString(",") { "%.2f".format(it) }} now): it does not follow"
                    }
                }
            }
        }
        for (l in lines) {
            if (l.startsWith("## ")) { judge(); pose = l.removePrefix("## "); section = ArrayList() } else section += l
        }
        judge()
        return problems
    }
}
