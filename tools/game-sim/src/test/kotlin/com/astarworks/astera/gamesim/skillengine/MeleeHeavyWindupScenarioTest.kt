package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.PatchMeta
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Heavy melee wind-up (melee-windup-table): every shipped heavy skill winds up for W = round_half_up(5 / attack_speed) ticks, the hit lands
 * exactly at tick W, the cast ends right after the hit (cooldown / next input unchanged), and the swing trail + impact are detached so they
 * keep playing past the cast end (machine.tail).
 */
class MeleeHeavyWindupScenarioTest : SkillEngineScenario() {
    /** skill id -> (W, strike ticks = cast end - W). */
    private val heavy = linkedMapOf(
        "scythe-reap" to (4 to 1), "scythe-pull" to (4 to 1), "shield-bash" to (4 to 1),
        "club-bash" to (5 to 1), "club-slam" to (5 to 1),
        "halberd-sweep" to (5 to 1), "halberd-hook" to (5 to 1),
        "war-axe-chop" to (5 to 1), "war-axe-whirl" to (5 to 1),
        "mace-smash" to (6 to 1), "cleaver-chop" to (6 to 1), "cleaver-rend" to (6 to 1),
        "greatsword-cleave" to (6 to 3),
    )

    private fun shipped(): Map<String, CompiledSkill> {
        val composed = (SkillComposer(contentDir.resolve("skills")).composeAll() as? Result.Success)?.value ?: throw AssertionError("compose failed")
        val ids = composed.keys.toSet()
        val defs = LinkedHashMap<String, SkillDef>()
        for ((id, c) in composed) if (c.patch == PatchMeta.BASE) defs[id] = (decodeTree(id, c.tree, ids) as? Result.Success)?.value ?: throw AssertionError("decode $id")
        return heavy.keys.associateWith { (compileAgainst(defs.getValue(it), defs) as? Result.Success)?.value ?: throw AssertionError("$it did not compile") }
    }

    @Test
    fun hitLandsAtWindupTickCastEndsRightAfterAndVisualsOutliveIt() {
        for ((id, skill) in shipped()) {
            val (w, strikeFor) = heavy.getValue(id)
            val t = skill.table
            val windup = t.states.single { it.state.value == "windup" }
            assertThat((windup.duration as Duration.Fixed).ticks).describedAs("$id windup length").isEqualTo(w)
            assertThat(windup.then?.value).describedAs("$id windup then").isEqualTo("strike")
            assertThat(windup.rows.none { it.action is Action.Hit }).describedAs("$id no hit during the wind-up").isTrue()

            val strike = t.states.single { it.state.value == "strike" }
            val hits = strike.rows.filter { it.action is Action.Hit }
            assertThat(hits).describedAs("$id has a hit in strike").isNotEmpty()
            assertThat(hits.map { it.tick }.toSet()).describedAs("$id hit tick inside strike (absolute tick = $w + this)").containsExactly(0)
            assertThat(t.states.sumOf { s -> s.rows.count { r -> r.action is Action.Hit } }).describedAs("$id hits only in strike").isEqualTo(hits.size)

            // cast end = start -> final: the wind-up, then the hit, then (greatsword) the miss cue; nothing waits for the visuals
            assertThat(t.bounds.path.max).describedAs("$id cast end").isEqualTo(w + strikeFor)

            // trail / impact: detached rows in strike run past the cast end through machine.tail
            assertThat(t.bounds.tail).describedAs("$id tail").isBetween(8, 12)
            assertThat(strike.rows.count { it.detached }).describedAs("$id detached strike rows (trail / impact)").isGreaterThan(0)
            assertPlays(skill)
        }
    }
}
