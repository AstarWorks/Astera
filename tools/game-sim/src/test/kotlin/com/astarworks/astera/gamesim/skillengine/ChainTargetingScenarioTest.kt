package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0303 9.4 chain targeting: `chain: {hops, every, pick}` on a `use: fragment:` call in an `on.hit` list unrolls at load into
 * jump[1..n] at +k*every; who each copy hits is picked at runtime (nearest, never a repeat), when it hits is fixed at load.
 * Real loader, real player; the recorder returns every scripted victim from `nearby` (no radius filter).
 */
class ChainTargetingScenarioTest : SkillEngineScenario() {
    private fun dummy(i: Long, z: Double) =
        HitCandidate(UUID(0L, 10L + i), Vec3(0.0, 0.0, z), 0.0, HitBox(Vec3(-0.3, 0.0, z - 0.3), Vec3(0.3, 1.8, z + 0.3)), 1.62)

    private fun id(i: Long) = UUID(0L, 10L + i)

    private fun skill(hops: String = "3", every: String = "3", owner: String = "world", extra: String = "", pick: String = "{kind: enemies, around: from, radius: 4.5, nearest: 1, exclude: chain}"): String {
        val world = owner == "world"
        val pad = if (world) "    " else "        "
        val hit = listOf(
            "- id: bolt",
            "  op: hit",
            "  at: 0",
            "  anchor: caster",
            "  shape: {sphere: 30.0}",
            "  targets: enemies",
            "  max_count: 1",
            "  ignore_invulnerability: false",
            "  damage: {fire: 5}",
            "  on:",
            "    hit:",
            "      - {id: jump, op: use, use: \"fragment:arc\", chain: {hops: $hops, every: $every, pick: $pick}$extra}",
        ).joinToString("\n") { pad + it }
        return listOf(
            "apiVersion: astera/v3",
            "kind: Skill",
            "id: chain-proof",
            "fragments:",
            "  arc:",
            "    do:",
            "      - {id: dmg, op: damage, target: victim, damage: {fire: 5}}",
            "machine:",
            "  start: cast",
            if (world) "  world:\n$hit" else "",
            "  states:",
            "    cast:",
            "      for: ${if (world) 30 else 8}",
            if (world) "      do: []" else "      do:\n$hit",
        ).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun load(yaml: String): Result<CompiledSkill, *> = loadInline(yaml)

    private fun loaded(yaml: String): CompiledSkill = when (val r = loadInline(yaml)) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error")
        is Result.Failure -> r.error.message
    }

    private fun damages(skill: CompiledSkill, dummies: List<HitCandidate>): List<RecordedCall> {
        val rec = GatewayRecorder()
        rec.victims = dummies
        return play(skill, seed = 1L, events = emptyList(), ticks = 30, rec = rec).filter { it.verb == "damage" }
    }

    @Test
    fun threeDummiesEachHitOnceAtFixedTicks() {
        val d = (1L..4L).map { dummy(it, it * 3.0) }
        val hits = damages(loaded(skill()), d)
        assertThat(hits.map { it.tick }).containsExactly(0, 3, 6, 9)
        assertThat(hits.map { it.instance }).containsExactly(id(1).toString(), id(2).toString(), id(3).toString(), id(4).toString())
    }

    @Test
    fun hopSkipsWhenNoCandidateAndLaterHopsToo() {
        // only two dummies: hop 1 reaches the second, hop 2 and 3 find nobody
        val hits = damages(loaded(skill()), listOf(dummy(1, 3.0), dummy(2, 6.0)))
        assertThat(hits.map { it.tick }).containsExactly(0, 3)
    }

    @Test
    fun excludeNeverRepeatsAVictim() {
        val hits = damages(loaded(skill()), listOf(dummy(1, 3.0), dummy(2, 6.0)))
        assertThat(hits.map { it.instance }).doesNotHaveDuplicates()
        assertThat(hits).hasSize(2)
    }

    @Test
    fun chainPickIsNearest() {
        // victim 1 is struck first; the next hop goes to the nearer of the other two (z=4), not the farther one (z=9)
        val hits = damages(loaded(skill(hops = "2")), listOf(dummy(1, 3.0), dummy(3, 9.0), dummy(2, 4.0)))
        assertThat(hits.map { it.instance }).containsExactly(id(1).toString(), id(2).toString(), id(3).toString())
    }

    @Test
    fun hopsFoldAtLoad() {
        assertThat(failure(skill(hops = "\"= rand() * 4\""))).contains("chain 'jump': hops folds at load: a literal, a numeric \${param} with a default, or = CEL of constants and params (got \"= rand() * 4\")")
        assertThat(failure(skill(hops = "40"))).contains("chain 'jump': hops must be a whole number 1..16 (got 40)")
        assertThat(failure(skill(every = "0"))).contains("chain 'jump': every must be >= 1 tick (got 0)")
        assertThat(failure(skill(pick = "{kind: enemies, around: heart, radius: 4.5, nearest: 1, exclude: chain}"))).contains("pick.around must be 'from'")
        assertThat(failure(skill(pick = "{kind: enemies, around: from, radius: 4.5, nearest: 2, exclude: chain}"))).contains("pick.nearest must be 1")
    }

    @Test
    fun chainNeedsCastOwnedBody() {
        val message = failure(skill(owner = "state"))
        assertThat(message).isEqualTo("op 'cast/bolt.hit/jump[3]' reaches tick 9 but state 'cast' lasts 8")
    }

    @Test
    fun dumpShowsThePickAndTheUnrolledCopies() {
        val dump = com.astarworks.astera.core.application.skillengine.compose.ExpansionDump.render(loaded(skill()).table)
        for (k in 1..3) {
            assertThat(dump).contains("pick").contains("chain=").contains("hop=$k")
            assertThat(dump).contains("jump[$k]/dmg")
        }
        assertThat(dump).doesNotContain("jump[4]")
    }
}
