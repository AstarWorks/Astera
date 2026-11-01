package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationVisuals
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.port.outbound.IStructureTargets.DamageKind
import com.astarworks.astera.core.application.port.outbound.IStructureTargets.DamageSource
import com.astarworks.astera.core.application.service.presentation.LoggingPresentationVisuals
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.AnnihilationPhaseTransitioned
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarTargets
import com.astarworks.astera.match.application.port.outbound.IMatchPhaseClock
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.OreNodeService
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0319: the Annihilation 5-phase flow. A short clock (200 ticks a phase) drives the real wiring:
 * phase announcements (title + sound + boss bar), the phase clock query, the Mid ore gate, the damage multipliers and the
 * last-phase bleed, the HP tiebreak at the end, and the per-player "what next" navigation line.
 */
class AnnihilationPhaseFlowScenarioTest {

    private val config = AnnihilationConfig(
        pillarHp = 1000,
        buildPhaseTicks = 200,
        engagePhaseTicks = 200,
        centerPhaseTicks = 200,
        escalationPhaseTicks = 200,
        suddenDeathTicks = 400,
        bleedIntervalTicks = 20,
        bleedPercent = 10,
        bleedFloorPercent = 10,
    )

    private fun ScenarioScope.threeTeams(): Triple<SimEntity, SimEntity, SimEntity> {
        val red = player("red-1", team = "red")
        val blue = player("blue-1", team = "blue")
        val yellow = player("yellow-1", team = "yellow")
        return Triple(red, blue, yellow)
    }

    private fun ScenarioScope.pillarId(matchId: MatchId, team: String, tier: NexusPillarTier) =
        AnnihilationPillarTargets.idOf("${matchId.value}-$team-${tier.ordinalValue}")

    private fun ScenarioScope.pillarHp(matchId: MatchId, team: String, tier: NexusPillarTier): Double =
        checkNotNull(sim.services.getOrThrow<IStructureTargets>().hitPoints(pillarId(matchId, team, tier)))

    /** One raw 100 tool-skill hit on [team]'s [tier] pillar from 5 blocks; returns the HP it removed (null = rejected). */
    private fun ScenarioScope.hit(matchId: MatchId, attacker: SimEntity, team: String, tier: NexusPillarTier): Double? {
        val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena")))
        val p = stage.pillars.first { it.team.value == team && it.tier == tier }
        val before = pillarHp(matchId, team, tier)
        val from = Vec3(p.centre.x, p.position.y, p.centre.z + 5.0)
        val r = sim.services.getOrThrow<IStructureTargets>()
            .damage(pillarId(matchId, team, tier), attacker.id, 100.0, DamageSource(DamageKind.TOOL_SKILL, p.affinity, from))
        return if (r is Result.Success) before - r.value else null
    }

    private fun ScenarioScope.actionBarTexts(who: SimEntity): List<String> =
        sim.hud.changes(who.id, HudRegion.ACTION_BAR)
            .mapNotNull { (it.layout as? HudLayout.ActionBar)?.component }
            .map { SimHudRecorder.describe(it) }

    @Test
    fun `every phase is announced with a title, a boss bar and the phase clock, and opens its tiers`() {
        scenario("annihilation-phase-flow-progression", config) {
            val (red, blue, yellow) = threeTeams()
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            val visuals = sim.services.getOrThrow<IPresentationVisuals>() as LoggingPresentationVisuals
            fun titlesOf(who: SimEntity) = visuals.calls.filterIsInstance<LoggingPresentationVisuals.Call.Title>().filter { it.player == who.id }

            advance(40)
            val first = checkNotNull(clock.current(matchId))
            assertThat(first.phase).isEqualTo(NexusPhase.Phase1Build)
            assertThat(first.phaseTicks).isEqualTo(200L)
            // The time to a later phase is the rest of this one plus the phases between.
            val toCenter = checkNotNull(clock.ticksUntil(matchId, NexusPhase.Phase3Center))
            assertThat(toCenter).isEqualTo(first.remainingTicks + 200L)

            // Phase 1: announced to everyone with number + name; the boss bar names the phase and drains.
            assertThat(titlesOf(red).map { it.args["n"] }).contains("1")
            assertThat(titlesOf(blue).map { it.args["n"] }).contains("1")
            val bar1 = checkNotNull(sim.objectiveStage.bar(red.id, "objective"))
            assertThat(bar1.title).contains("Phase 1").contains("Preparation")
            assertThat(bar1.progress).isBetween(0.0, 1.0)
            val progress1 = bar1.progress
            advance(60)
            assertThat(checkNotNull(sim.objectiveStage.bar(red.id, "objective")).progress).`as`("the bar drains").isLessThan(progress1)
            // Pillars are immune in phase 1.
            assertThat(hit(matchId, red, "blue", NexusPillarTier.TIER_1)).`as`("phase 1: pillars immune").isNull()

            // Phase 2 -> 5, each announced and each opening its tiers.
            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase2Engage }).isTrue()
            advance(5)
            assertThat(titlesOf(yellow).map { it.args["n"] }).contains("2")
            assertThat(hit(matchId, red, "blue", NexusPillarTier.TIER_1)).`as`("phase 2: tier 1 takes damage").isGreaterThan(0.0)
            assertThat(hit(matchId, red, "blue", NexusPillarTier.TIER_3)).`as`("phase 2: tier 3 still closed").isNull()

            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase3Center }).isTrue()
            advance(5)
            assertThat(titlesOf(red).map { it.args["n"] }).contains("3")
            assertThat(hit(matchId, red, "blue", NexusPillarTier.TIER_3)).`as`("phase 3: tier 3 open").isGreaterThan(0.0)

            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase4Escalation }).isTrue()
            advance(5)
            assertThat(titlesOf(red).map { it.args["n"] }).contains("4")

            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase5SuddenDeath }).isTrue()
            advance(5)
            assertThat(titlesOf(red).map { it.args["n"] }).contains("5")
            assertThat(checkNotNull(clock.current(matchId)).remainingTicks).`as`("the last phase counts down to the match end").isLessThanOrEqualTo(400L)
            assertThat(clock.ticksUntil(matchId, NexusPhase.Phase2Engage)).`as`("a phase already open has 0 ticks to go").isEqualTo(0L)
            assertThat(checkNotNull(sim.objectiveStage.bar(red.id, "objective")).title).contains("Final phase")

            // The phase event is published once per change, in order.
            val transitions = eventsOf<AnnihilationPhaseTransitioned>().filter { it.matchId == matchId }.map { it.newPhase }
            assertThat(transitions).containsExactly(
                NexusPhase.Phase2Engage, NexusPhase.Phase3Center, NexusPhase.Phase4Escalation, NexusPhase.Phase5SuddenDeath,
            )
            // Each announcement came with a sound.
            assertThat(sim.playerSounds.calls).isNotEmpty()
        }
    }

    @Test
    fun `the Mid ore opens with the phases and tells the player when`() {
        scenario("annihilation-phase-flow-ore-gate", config) {
            val (red, blue, yellow) = threeTeams()
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val nodes = sim.services.getOrThrow<OreNodeService>()
            val pick = ItemId("diamond-pickaxe")
            fun cell(x: Int, y: Int, z: Int) = Vec3(origin.x + x, y.toDouble(), origin.z + z)
            val iron = cell(-33, 61, 11)
            val gold = cell(17, 56, 22)
            val emerald = cell(8, 51, -17)
            fun mine(c: Vec3): OreNodeService.Outcome { advance(7); return nodes.mine(red.id, worldId, c, pick) }
            fun locked(c: Vec3) = (mine(c) as? OreNodeService.Outcome.PhaseLocked)?.required

            advance(20)
            assertThat(locked(iron)).`as`("base ore is open in phase 1").isNull()
            assertThat(locked(gold)).`as`("gold waits for phase 2").isEqualTo(2)
            assertThat(locked(emerald)).`as`("emerald waits for phase 3").isEqualTo(3)
            assertThat(checkNotNull(sim.server.view(red.id)).actionBars.map { it.value }).contains("astera.annihilation.ore.phase-locked")

            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase2Engage }).isTrue()
            assertThat(locked(gold)).`as`("gold open in phase 2").isNull()
            assertThat(locked(emerald)).`as`("emerald still closed in phase 2").isEqualTo(3)

            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase3Center }).isTrue()
            assertThat(locked(emerald)).`as`("emerald open in phase 3").isNull()
        }
    }

    @Test
    fun `an attacker-less full length last phase bleeds at most a quarter of the total pillar HP`() {
        // Shipped bleed cadence (config defaults mirror content/game-modes/annihilation.yaml), full 5 min (6000 tick) phase 5, nobody attacks.
        val shipped = AnnihilationConfig(
            pillarHp = 1000,
            buildPhaseTicks = 200,
            engagePhaseTicks = 200,
            centerPhaseTicks = 200,
            escalationPhaseTicks = 200,
            suddenDeathTicks = 6000,
        )
        scenario("annihilation-phase-flow-bleed-budget", shipped) {
            val (red, blue, yellow) = threeTeams()
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            fun total() = listOf("red", "blue", "yellow").sumOf { t -> NexusPillarTier.ordered().sumOf { pillarHp(matchId, t, it) } }

            val before = total()
            assertThat(advanceUntil(1200) { clock.current(matchId)?.phase == NexusPhase.Phase5SuddenDeath }).isTrue()
            assertThat(total()).`as`("no drain before the last phase").isEqualTo(before)
            advance(5990)
            val lost = before - total()
            assertThat(lost).`as`("bleed pressures the pillars").isGreaterThan(0.0)
            assertThat(lost).`as`("attacker-less bleed loss <= 25% of total max pillar HP (lost=$lost of $before)").isLessThanOrEqualTo(before * 0.25)
        }
    }

    @Test
    fun `damage grows in phases 4 and 5 and every pillar bleeds down to the floor in the last phase`() {
        scenario("annihilation-phase-flow-damage-bleed", config.copy(escalationPhaseTicks = 300, suddenDeathTicks = 600)) {
            val (red, blue, yellow) = threeTeams()
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            blue.position = Vec3(0.0, groundY, 0.0)
            yellow.position = Vec3(0.0, groundY, 1.0)

            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase2Engage }).isTrue()
            val base = checkNotNull(hit(matchId, red, "blue", NexusPillarTier.TIER_1))
            assertThat(base).isGreaterThan(0.0)

            assertThat(advanceUntil(600) { clock.current(matchId)?.phase == NexusPhase.Phase4Escalation }).isTrue()
            assertThat(checkNotNull(hit(matchId, red, "blue", NexusPillarTier.TIER_1))).`as`("phase 4: x1.5").isEqualTo(base * 1.5)

            assertThat(advanceUntil(600) { clock.current(matchId)?.phase == NexusPhase.Phase5SuddenDeath }).isTrue()
            assertThat(checkNotNull(hit(matchId, red, "yellow", NexusPillarTier.TIER_1))).`as`("phase 5: x2").isEqualTo(base * 2.0)

            // 10% of max HP per 20 ticks, floor 10%: a few intervals later every standing pillar sits at the floor, none is destroyed.
            advance(300)
            for (team in listOf("red", "blue", "yellow")) for (tier in NexusPillarTier.ordered()) {
                assertThat(pillarHp(matchId, team, tier)).`as`("$team $tier bleeds to the floor").isEqualTo(100.0)
            }
            assertThat(eventsOf<MatchEnded>()).`as`("the bleed alone never ends the match before the clock").isEmpty()
        }
    }

    @Test
    fun `when the last phase runs out the pillar HP tiebreak decides`() {
        // No bleed (interval beyond the match), a short last phase.
        scenario("annihilation-phase-flow-tiebreak", config.copy(suddenDeathTicks = 100, bleedIntervalTicks = 100_000)) {
            val (red, blue, yellow) = threeTeams()
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            blue.position = Vec3(0.0, groundY, 0.0)
            yellow.position = Vec3(0.0, groundY, 1.0)

            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase2Engage }).isTrue()
            // Blue loses one hit, yellow two: red keeps the most pillar HP.
            hit(matchId, red, "blue", NexusPillarTier.TIER_1)
            hit(matchId, red, "yellow", NexusPillarTier.TIER_1)
            advance(8)
            hit(matchId, red, "yellow", NexusPillarTier.TIER_1)

            assertThat(advanceUntil(1_500) { eventsOf<MatchEnded>().isNotEmpty() }).`as`("the match ends when phase 5 expires").isTrue()
            assertThat(eventsOf<MatchEnded>().single().winningTeam).isEqualTo(TeamId("red"))
        }
    }

    @Test
    fun `the clock decision names the most pillar HP, whichever team is listed first`() {
        scenario("annihilation-phase-flow-hp-not-order", config.copy(suddenDeathTicks = 100, bleedIntervalTicks = 100_000)) {
            val (red, blue, yellow) = threeTeams()
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase2Engage }).isTrue()
            // Red (first listed) and yellow are both hit; blue keeps every point of HP.
            hit(matchId, blue, "red", NexusPillarTier.TIER_1)
            hit(matchId, blue, "yellow", NexusPillarTier.TIER_1)
            advance(8)
            hit(matchId, blue, "yellow", NexusPillarTier.TIER_1)

            assertThat(advanceUntil(1_500) { eventsOf<MatchEnded>().isNotEmpty() }).isTrue()
            val ended = eventsOf<MatchEnded>().single()
            assertThat(ended.winningTeam).isEqualTo(TeamId("blue"))
            assertThat(ended.reason).isEqualTo(EndReason.PillarHpDecision(TeamId("blue")))
        }
    }

    @Test
    fun `an exact pillar HP tie at the top is a draw, never decided by team order`() {
        scenario("annihilation-phase-flow-hp-draw", config.copy(suddenDeathTicks = 100, bleedIntervalTicks = 100_000)) {
            val (red, blue, yellow) = threeTeams()
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase2Engage }).isTrue()
            // Red and blue untouched (equal totals); yellow is the one that lost HP.
            hit(matchId, red, "yellow", NexusPillarTier.TIER_1)

            assertThat(advanceUntil(1_500) { eventsOf<MatchEnded>().isNotEmpty() }).isTrue()
            val ended = eventsOf<MatchEnded>().single()
            assertThat(ended.winningTeam).isNull()
            assertThat(ended.reason).isEqualTo(EndReason.PillarHpDecision(null))
        }
    }

    @Test
    fun `each player gets a what-next line - mine while low, defend a hit pillar`() {
        scenario("annihilation-phase-flow-navigation", config.copy(buildPhaseTicks = 400, engagePhaseTicks = 2_000)) {
            val (red, blue, yellow) = threeTeams()
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            blue.position = Vec3(0.0, groundY, 0.0)
            yellow.position = Vec3(0.0, groundY, 1.0)

            // Phase 1, Norma level 1: the line is "mine for the next ore", with the ore name and the level.
            advance(120)
            val mine = actionBarTexts(red).filter { it.startsWith("astera.objective.hint.annihilation.mine{") }
            assertThat(mine).`as`("a level-1 player in phase 1 is told to mine").isNotEmpty()
            assertThat(mine.last()).contains("ore=").contains("area=")

            // Phase 2: an own pillar takes a hit -> the line turns to "defend".
            assertThat(advanceUntil(600) { clock.current(matchId)?.phase == NexusPhase.Phase2Engage }).isTrue()
            val before = actionBarTexts(red).size
            hit(matchId, blue, "red", NexusPillarTier.TIER_1)
            advance(80)
            assertThat(actionBarTexts(red).drop(before)).`as`("own pillar attacked: defend").anyMatch { it.startsWith("astera.objective.hint.annihilation.defend") }
        }
    }
}
