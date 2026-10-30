package com.astarworks.astera.gamesim.skillengine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt

/**
 * Caster-view rule for held rig meshes (se-near-eye-rig), the sibling of [CasterEyeClutter]. A `rig` op without `of:` draws the caster's weapon as an item
 * display on the caster's own arm, which is about a block from the caster's eye: shown to the caster it swings its head across the first-person screen
 * (club-slam / mace-smash covered a quarter of the frame). So at no tick of any recorded pose may such a mesh that is visible to the caster (no
 * `viewers=owner-hidden` on its spawn row, and no explicit `viewers: all` on a rig row of the skill yaml) stand within [NEAR_EYE_RADIUS] blocks of the eye. Read from the committed goldens (`skill-oracle/<skill>/skill.txt`), which
 * [ShippedV3ContentScenarioTest] keeps equal to the engine's output: `@t spawn #id op=<rig> kind=item item=main_hand ...` and `@t frame <rig> ... pos=x,y,z`
 * (feet at the origin, eye at y = [CasterEyeClutter.EYE_Y]).
 */
class CasterRigNearEyeScenarioTest : SkillEngineScenario() {

    @Test
    fun noCasterVisibleHeldRigMeshNearTheEye() {
        val goldens = repoRoot.resolve("tools/game-sim/src/test/resources/skill-oracle")
        val problems = Files.walk(goldens).use { s ->
            s.filter { it.toString().endsWith(".txt") }.sorted().toList().flatMap { check(goldens.relativize(it).toString().removeSuffix(".txt"), Files.readAllLines(it)) }
        }
        assertThat(problems).`as`("caster-visible held rig meshes within $NEAR_EYE_RADIUS blocks of the eye (leave the rig's viewers unset = owner-hidden)").isEmpty()
    }

    private fun check(key: String, lines: List<String>): List<String> {
        if (explicitlyAll(key)) return emptyList()
        val problems = ArrayList<String>()
        var pose = ""
        val visible = HashMap<String, String>() // rig op path -> spawn row, only for meshes the caster sees
        val nearest = HashMap<String, Pair<Int, Double>>()
        fun flush() {
            for ((op, row) in visible) {
                val n = nearest[op] ?: continue
                if (n.second <= NEAR_EYE_RADIUS) problems += "$key [$pose] $op: the held rig mesh is visible to the caster and stands ${"%.2f".format(n.second)} blocks from the eye @${n.first} (${row.take(60)})"
            }
            visible.clear(); nearest.clear()
        }
        for (l in lines) {
            if (l.startsWith("## ")) { flush(); pose = l.removePrefix("## "); continue }
            SPAWN.find(l)?.let { m -> if (" viewers=owner-hidden" !in l) visible[m.groupValues[2]] = l; return@let }
            val f = FRAME.find(l) ?: continue
            val op = f.groupValues[2]
            if (op !in visible) continue
            val d = sqrt(sq(f.groupValues[3].toDouble()) + sq(f.groupValues[4].toDouble() - CasterEyeClutter.EYE_Y) + sq(f.groupValues[5].toDouble()))
            val cur = nearest[op]
            if (cur == null || d < cur.second) nearest[op] = f.groupValues[1].toInt() to d
        }
        flush()
        return problems
    }

    /** The one opt-out: the skill's yaml writes `viewers: all` on a rig row (an explicit, reviewed choice; the engine default is owner-hidden). */
    private fun explicitlyAll(key: String): Boolean {
        val yaml = repoRoot.resolve("content/skills/${key.substringBefore('/')}/skill.yaml")
        if (!Files.exists(yaml)) return false
        val rows = Files.readString(yaml).split(Regex("(?m)^\\s*- (?=id: |\\{id: )"))
        return rows.any { "op: rig" in it && Regex("viewers:\\s*all\\b").containsMatchIn(it) }
    }

    private fun sq(x: Double) = x * x

    private companion object {
        const val NEAR_EYE_RADIUS = 1.5
        val SPAWN = Regex("^@(\\d+) spawn #\\d+ op=(\\S+) kind=item item=main_hand ")
        val FRAME = Regex("^@(\\d+) frame \\S+ op=(\\S+) pos=(\\S+),(\\S+),(\\S+) ")

    }
}
