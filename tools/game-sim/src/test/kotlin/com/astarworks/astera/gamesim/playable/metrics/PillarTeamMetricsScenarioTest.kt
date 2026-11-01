package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.port.outbound.IStructureTargets.DamageKind
import com.astarworks.astera.core.application.port.outbound.IStructureTargets.DamageSource
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.gamesim.ScenarioScope
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.gamesim.scenario
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarTargets
import com.astarworks.astera.match.application.port.outbound.IMatchPhaseClock
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * Per-team pillar metrics and the normalised end_reason of [MatchMetrics] on the real annihilation wiring: a known
 * amount of pillar damage from one team is dealt / taken / left exactly, unattributed damage (sudden-death bleed) is in
 * taken and in the separate unattributed bucket only, and end_reason follows the real MatchEnded reason.
 */
class PillarTeamMetricsScenarioTest {

    private val base = AnnihilationConfig(
        pillarHp = 1000,
        buildPhaseTicks = 200,
        engagePhaseTicks = 200,
        centerPhaseTicks = 200,
        escalationPhaseTicks = 200,
        suddenDeathTicks = 400,
        bleedIntervalTicks = 100_000,
        bleedPercent = 10,
        bleedFloorPercent = 10,
    )

    private fun ScenarioScope.pillarId(matchId: MatchId, team: String, tier: NexusPillarTier) =
        AnnihilationPillarTargets.idOf("${matchId.value}-$team-${tier.ordinalValue}")

    private fun ScenarioScope.hp(matchId: MatchId, team: String, tier: NexusPillarTier): Double =
        checkNotNull(sim.services.getOrThrow<IStructureTargets>().hitPoints(pillarId(matchId, team, tier)))

    /** One raw 100 tool-skill hit; returns the HP it removed. */
    private fun ScenarioScope.hit(matchId: MatchId, attacker: SimEntity, team: String, tier: NexusPillarTier): Double {
        val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena")))
        val p = stage.pillars.first { it.team.value == team && it.tier == tier }
        val before = hp(matchId, team, tier)
        val from = Vec3(p.centre.x, p.position.y, p.centre.z + 5.0)
        val r = sim.services.getOrThrow<IStructureTargets>()
            .damage(pillarId(matchId, team, tier), attacker.id, 100.0, DamageSource(DamageKind.TOOL_SKILL, p.affinity, from))
        check(r is Result.Success) { "hit on $team $tier was rejected: $r" }
        return before - r.value
    }

    private fun ScenarioScope.runUntilEnded(metrics: MatchMetrics, maxTicks: Int) {
        repeat(maxTicks) {
            if (eventsOf<MatchEnded>().isNotEmpty()) return
            sim.step(1)
            metrics.onTick(sim.tick)
        }
    }

    private fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.content.toDouble()

    @Test
    fun `a known pillar hit is dealt by the attacker team, taken by the owner, left as the rest, and the HP tiebreak is hp_decision`() {
        scenario("pillar-team-metrics-attributed", base) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val metrics = MatchMetrics(sim)
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            repeat(300) {
                if (clock.current(matchId)?.phase == NexusPhase.Phase2Engage) return@repeat
                sim.step(1); metrics.onTick(sim.tick)
            }
            assertThat(clock.current(matchId)?.phase).isEqualTo(NexusPhase.Phase2Engage)
            val startHp = hp(matchId, "red", NexusPillarTier.TIER_1)

            // Blue is the only attacker: once on red, twice on yellow. One tick between hits so each lands in its own sample.
            val onRed = hit(matchId, blue, "red", NexusPillarTier.TIER_1)
            sim.step(1); metrics.onTick(sim.tick)
            val onYellow1 = hit(matchId, blue, "yellow", NexusPillarTier.TIER_1)
            sim.step(1); metrics.onTick(sim.tick)
            val onYellow2 = hit(matchId, blue, "yellow", NexusPillarTier.TIER_1)
            sim.step(1); metrics.onTick(sim.tick)
            assertThat(onRed).isGreaterThan(0.0)

            val redLeftAtHit = hp(matchId, "red", NexusPillarTier.TIER_1)
            val yellowT1LeftAtHit = hp(matchId, "yellow", NexusPillarTier.TIER_1)
            runUntilEnded(metrics, 1_500)
            val ended = eventsOf<MatchEnded>().single()
            assertThat(ended.reason).isEqualTo(EndReason.PillarHpDecision(com.astarworks.astera.core.domain.match.TeamId("blue")))

            val json = metrics.finish().json
            assertThat(json["end_reason"]!!.jsonPrimitive.content).isEqualTo("hp_decision")
            assertThat(json["end_decided_by"]!!.jsonPrimitive.content).isEqualTo("PILLAR_HP")
            val teams = json["teams"]!!.jsonObject
            val tol = within(1e-6)
            assertThat(teams["blue"]!!.jsonObject.num("pillar_damage_dealt")).isEqualTo(onRed + onYellow1 + onYellow2, tol)
            assertThat(teams["red"]!!.jsonObject.num("pillar_damage_dealt")).isEqualTo(0.0, tol)
            assertThat(teams["yellow"]!!.jsonObject.num("pillar_damage_dealt")).isEqualTo(0.0, tol)
            assertThat(teams["red"]!!.jsonObject.num("pillar_damage_taken")).isEqualTo(onRed, tol)
            assertThat(teams["yellow"]!!.jsonObject.num("pillar_damage_taken")).isEqualTo(onYellow1 + onYellow2, tol)
            assertThat(teams["blue"]!!.jsonObject.num("pillar_damage_taken")).isEqualTo(0.0, tol)
            assertThat(json.num("pillar_damage_unattributed")).isEqualTo(0.0, tol)

            val redLeft = teams["red"]!!.jsonObject["pillar_hp_left"]!!.jsonObject
            assertThat(startHp - onRed).isEqualTo(redLeftAtHit, tol)
            assertThat(redLeft["by_tier"]!!.jsonObject.getValue("tier1").jsonPrimitive.content.toDouble()).isEqualTo(redLeftAtHit, tol)
            assertThat(yellowT1LeftAtHit).isLessThan(startHp)
            val blueLeft = teams["blue"]!!.jsonObject["pillar_hp_left"]!!.jsonObject
            assertThat(blueLeft.num("sum")).isGreaterThan(redLeft.num("sum"))
            // The sum is the per-tier breakdown.
            assertThat(redLeft["by_tier"]!!.jsonObject.values.sumOf { it.jsonPrimitive.content.toDouble() }).isEqualTo(redLeft.num("sum"), tol)
        }
    }

    @Test
    fun `sudden-death bleed has no attacker, so it is taken and unattributed but dealt by nobody, and an equal result is a draw`() {
        scenario("pillar-team-metrics-unattributed", base.copy(bleedIntervalTicks = 20)) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val metrics = MatchMetrics(sim)
            runUntilEnded(metrics, 3_000)
            val ended = eventsOf<MatchEnded>().single()
            val json = metrics.finish().json
            val expected = when (val r = ended.reason) {
                is EndReason.PillarHpDecision -> if (r.winner == null) "draw" else "hp_decision"
                is EndReason.TeamVictory -> if (r.winner == null) "draw" else "elimination"
                else -> error("unexpected end reason $r")
            }
            assertThat(json["end_reason"]!!.jsonPrimitive.content).isEqualTo(expected)
            assertThat(expected).isEqualTo("draw")
            // A tie is still decided by the HP path, so the criterion is the one the real event carries (not null).
            assertThat(json["end_decided_by"]!!.jsonPrimitive.content).isEqualTo((ended.reason as EndReason.PillarHpDecision).decidedBy.name)

            val teams = json["teams"]!!.jsonObject
            val unattributed = json.num("pillar_damage_unattributed")
            assertThat(unattributed).`as`("bleed happened").isGreaterThan(0.0)
            val takenSum = teams.values.sumOf { it.jsonObject.num("pillar_damage_taken") }
            assertThat(takenSum).isEqualTo(unattributed, within(1e-6))
            for ((team, v) in teams) {
                assertThat(v.jsonObject.num("pillar_damage_dealt")).`as`("dealt by $team").isEqualTo(0.0)
                assertThat(v.jsonObject.num("pillar_damage_taken_unattributed")).isEqualTo(v.jsonObject.num("pillar_damage_taken"), within(1e-6))
            }

            // The unattributed bucket is split by real source: all of it is bleed here (no player hit anything), none unknown.
            assertThat(json.num("pillar_damage_bleed")).isEqualTo(unattributed, within(1e-6))
            assertThat(json.num("pillar_damage_unknown")).isEqualTo(0.0, within(1e-6))
            assertThat(json.num("bleed_applications")).isGreaterThan(0.0)

            // Batch aggregate over two identical runs: sums double, shares stay, draws are separate from wins.
            val result = metrics.finish()
            val runs = listOf(MassPlay.RunResult("seed-1", 1, result, "draw", listOf("a@red")), MassPlay.RunResult("seed-2", 2, result, "draw", listOf("b@red")))
            val summary = MassPlay.summary(runs)
            assertThat(summary).contains("## Batch aggregate (2 runs)").contains("Draws: 2 of 2")
            val taken2 = 2 * takenSum
            assertThat(summary).contains("Player share of pillar damage (sum dealt / sum taken): 0 / ${if (taken2 == Math.floor(taken2)) taken2.toLong().toString() else "%.2f".format(taken2)} = 0.0%")
            val redRow = summary.lines().first { it.startsWith("| red | 0 | 2 | 0.0% |") }
            assertThat(redRow).contains("(100.0%)")
            assertThat(summary).contains("| seed-1 | 1 | a@red |")
        }
    }
}
