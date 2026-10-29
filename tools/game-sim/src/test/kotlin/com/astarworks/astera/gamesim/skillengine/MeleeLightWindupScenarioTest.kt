package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.PatchMeta
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Light melee wind-up (founder: weapon by weapon, W = round_half_up(5 / attack_speed)): the shipped light-melee skills hit at tick W, the cast ends right after the hit
 * (the dash skills when the dash ends), and the detached rig swing and blade trail keep playing past the cast end, up to cast end + tail.
 */
class MeleeLightWindupScenarioTest : SkillEngineScenario() {
    /** skill id -> (W, ticks the strike / dash state lasts from the hit tick; sword-slash keeps its original 2-tick swing) */
    private val light: Map<String, Pair<Int, Int>> = linkedMapOf(
        "dagger-stab" to (2 to 1),
        "twin-daggers-flurry" to (2 to 1),
        "rapier-thrust" to (2 to 1),
        "katana-slash" to (2 to 1),
        "katana-iaido" to (2 to 3),
        "sword-slash" to (3 to 2),
        "sword-slash-t2" to (3 to 2),
        "sword-slash-t3" to (3 to 2),
        "sword-slash-t4" to (3 to 2),
        "iron-blade-slash" to (3 to 1),
        "iron-blade-lunge" to (3 to 4),
        "spear-thrust" to (3 to 1),
        "spear-charge" to (3 to 6),
    )

    private fun shipped(): Pair<Map<String, SkillDef>, Set<String>> {
        val composed = (SkillComposer(contentDir.resolve("skills")).composeAll() as? Result.Success)?.value ?: throw AssertionError("compose failed")
        val ids = composed.keys.toSet()
        val defs = LinkedHashMap<String, SkillDef>()
        for ((id, c) in composed) if (c.patch == PatchMeta.BASE) defs[id] = (decodeTree(id, c.tree, ids) as? Result.Success)?.value ?: throw AssertionError("decode $id")
        return defs to ids
    }

    private fun tickOf(trace: List<String>, prefix: String): Int =
        trace.firstOrNull { it.startsWith(prefix) }?.substringAfterLast('@')?.toInt() ?: throw AssertionError("no '$prefix' in trace $trace")

    @Test
    fun lightMeleeHitsAtWindupTickAndTheTrailOutlivesTheCast() {
        val (defs, _) = shipped()
        val failures = ArrayList<String>()
        for ((id, spec) in light) {
            val (w, after) = spec
            val compiled: CompiledSkill = (compileAgainst(defs.getValue(id), defs) as? Result.Success)?.value ?: throw AssertionError("$id did not compile")
            val rec = GatewayRecorder()
            rec.victims = listOf(HitCandidate(UUID(0L, 11L), Vec3(0.0, 0.0, 0.5), 0.0, HitBox(Vec3(-0.3, 0.0, 0.2), Vec3(0.3, 1.8, 0.8)), 1.62))
            val calls = play(compiled, seed = 3L, events = emptyList(), ticks = 30, rec = rec)
            val damage = calls.filter { it.verb == "damage" }.map { it.tick }
            val castEnd = tickOf(rec.trace, "enter:end@")
            val trailAfter = calls.count { it.verb == "particle" && "type=crit" in it.payload && it.tick > castEnd }
            val rigAfter = calls.count { it.verb == "write" && it.tick > castEnd }
            if (damage.firstOrNull() != w) failures += "$id: first damage at ${damage.firstOrNull()} (want W=$w), all=$damage"
            if (castEnd != w + after) failures += "$id: cast ends at $castEnd (want ${w + after})"
            if (trailAfter == 0) failures += "$id: no trail particle after the cast end ($castEnd)"
            if (rigAfter == 0) failures += "$id: the rig does not write after the cast end ($castEnd)"
            if (compiled.table.bounds.tail <= 0) failures += "$id: no tail"
        }
        assertThat(failures).describedAs("light melee wind-up problems").isEmpty()
    }
}
