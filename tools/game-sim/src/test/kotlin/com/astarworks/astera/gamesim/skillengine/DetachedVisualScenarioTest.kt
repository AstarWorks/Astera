package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Visuals that must play inside their state (design-tier-and-detach 2.2 / 2.4): a state with `for: N` owns the ticks [0, N), so a row
 * at tick N is a load error instead of a silent skip; a `rig` spawns with the frames, so its points exist on its spawn tick; a rig with
 * `anim:` lasts exactly its clip, checked at load.
 */
class DetachedVisualScenarioTest : SkillEngineScenario() {
    private fun skill(stateFor: Int, vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: detached-probe", "item: couture-rapier",
            "machine:", "  start: s", "  states:", "    s:", "      for: $stateFor", "      enter:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> throw AssertionError("expected a load error but it compiled")
        is Result.Failure -> r.error.message
    }

    private fun ok(yaml: String): CompiledSkill = when (val r = loadInline(yaml)) {
        is Result.Success -> r.value
        is Result.Failure -> throw AssertionError("did not load: ${r.error}")
    }

    private fun puff(id: String, at: Int) = "{id: $id, op: particle, particle: flame, anchor: caster, at: $at, count: 1}"

    @Test
    fun rowAtExitTickIsRefusedAtLoadNotSkippedAtRuntime() {
        val msg = failure(skill(3, puff("p0", 0), puff("p1", 1), puff("p2", 2), puff("p3", 3)))
        assertThat(msg).contains("reaches tick 3").contains("lasts 3")
        // with the last row inside the state every call of the table is played
        assertPlays(ok(skill(3, puff("p0", 0), puff("p1", 1), puff("p2", 2))))
    }

    @Test
    fun anInstancesOwnEndRowMayStandAtTheExitTick() {
        // the rig's end row is at tick 3 == the state's `for`: the exit would end it anyway
        ok(skill(3, "{id: r, op: rig, parent: caster, part: held_hand, for: 3}"))
        assertThat(failure(skill(3, "{id: r, op: rig, parent: caster, part: held_hand, for: 4}"))).contains("lasts 3")
    }

    @Test
    fun rigPointsResolveOnSpawnTick() {
        val s = ok(
            skill(
                8,
                "{id: rig, op: rig, parent: caster, part: held_hand, for: 6}",
                "{id: edge, op: particle, particle: flame, anchor: {of: rig.grip, to: rig.tip, samples: 6}, at: 0, count: 1}",
            ),
        )
        val rows = s.table.states.single().rows.filter { it.tick == 0 }
        val rigAt = rows.indexOfFirst { it.instance.text.endsWith("rig") }
        val edgeAt = rows.indexOfFirst { it.instance.text.endsWith("edge") }
        assertThat(rigAt).describedAs("the rig spawns before the particle of the same tick").isBetween(0, edgeAt - 1)
        val rec = GatewayRecorder()
        play(s, 7L, emptyList(), 8, rec)
        val tick0 = rec.emittedAt.filter { it.first == 0 }
        assertThat(tick0).describedAs("edge particles on the rig's spawn tick").isNotEmpty()
        assertThat(tick0.any { it.third.x != 0.0 || it.third.y != 0.0 || it.third.z != 0.0 }).describedAs("resolved to the rig's points, not the origin").isTrue()
    }

    @Test
    fun rigClipLengthMismatchIsALoadError() {
        val msg = failure(skill(12, "{id: rig, op: rig, parent: caster, part: held_hand, anim: sword-swing, for: 2}"))
        assertThat(msg).contains("clip 'sword-swing' is 12 ticks but for: 2").contains("lasts exactly its clip")
        // the exact length is fine; a rig without anim keeps `for` free
        ok(skill(12, "{id: rig, op: rig, parent: caster, part: held_hand, anim: sword-swing, for: 12}"))
        ok(skill(12, "{id: rig, op: rig, parent: caster, part: held_hand, for: 2}"))
    }
}
