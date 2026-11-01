package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
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
import com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry
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
    private fun ScenarioScope.hit(matchId: MatchId, attacker: SimEntity, team: String, tier: NexusPillarTier, rawDamage: Double = 100.0): Double? {
        val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena")))
        val p = stage.pillars.first { it.team.value == team && it.tier == tier }
        val before = pillarHp(matchId, team, tier)
        val from = Vec3(p.centre.x, p.position.y, p.centre.z + 5.0)
        val r = sim.services.getOrThrow<IStructureTargets>()
            .damage(pillarId(matchId, team, tier), attacker.id, rawDamage, DamageSource(DamageKind.TOOL_SKILL, p.affinity, from))
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
            // The first node of each kind in the shipped content/ore-nodes/annihilation-arena.yaml, so a map move cannot stale the test.
            val oreConfig = checkNotNull(sim.services.getOrThrow<IOreNodeRegistry>().find("annihilation-arena"))
            fun firstNode(block: String): Vec3 {
                val p = oreConfig.nodes.first { it.block == block && it.positions.isNotEmpty() }.positions.first()
                return Vec3(origin.x + p.x, p.y, origin.z + p.z)
            }
            val iron = firstNode("astera:ore_iron_node")
            val gold = firstNode("astera:ore_gold_node")
            val emerald = firstNode("astera:ore_emerald_node")
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
            assertThat(checkNotNull(hit(matchId, red, "blue", NexusPillarTier.TIER_1))).`as`("phase 4: x2").isEqualTo(base * 2.0)

            assertThat(advanceUntil(600) { clock.current(matchId)?.phase == NexusPhase.Phase5SuddenDeath }).isTrue()
            assertThat(checkNotNull(hit(matchId, red, "yellow", NexusPillarTier.TIER_1))).`as`("phase 5: x3").isEqualTo(base * 3.0)

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
            // 1000 max HP x 3 pillars = 3000 per team; one hit removes 300 (raw 100 x tool-skill match 3.0 x falloff 1.0 x phase 2 mult 1.0).
            // Red took 1 hit (2700), yellow 2 (2400), blue none (3000). No bleed (interval 100_000 ticks): bleedTaken = 0 for every team.
            assertThat(ended.reason).isEqualTo(
                EndReason.PillarHpDecision(
                    TeamId("blue"), EndReason.PillarHpDecision.Criterion.PILLAR_HP,
                    playHp = mapOf(TeamId("red") to 2700, TeamId("blue") to 3000, TeamId("yellow") to 2400),
                    bleedTaken = mapOf(TeamId("red") to 0, TeamId("blue") to 0, TeamId("yellow") to 0),
                ),
            )
        }
    }

    private fun tiedAtTop(name: String, body: ScenarioScope.(MatchId, SimEntity, SimEntity, SimEntity) -> Unit): MatchEnded {
        var ended: MatchEnded? = null
        scenario(name, config.copy(suddenDeathTicks = 100, bleedIntervalTicks = 100_000)) {
            val (red, blue, yellow) = threeTeams()
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase2Engage }).isTrue()
            body(matchId, red, blue, yellow)
            assertThat(advanceUntil(1_500) { eventsOf<MatchEnded>().isNotEmpty() }).isTrue()
            ended = eventsOf<MatchEnded>().single()
        }
        return checkNotNull(ended)
    }

    @Test
    fun `tiebreak step 1 - an exact pillar HP tie goes to the team that dealt more damage to enemy pillars`() {
        val ended = tiedAtTop("annihilation-phase-flow-tie-damage") { matchId, red, _, _ ->
            // Red and blue keep full HP (equal totals); only red attacked (yellow's pillar).
            hit(matchId, red, "yellow", NexusPillarTier.TIER_1)
        }
        assertThat(ended.winningTeam).isEqualTo(TeamId("red"))
        // 1000 max HP x 3 pillars = 3000 per team; one hit removes 300 (raw 100 x tool-skill match 3.0 x falloff 1.0 x phase 2 mult 1.0).
        // Only yellow's tier 1 was hit once: play-HP red 3000, blue 3000, yellow 2700. No bleed (interval 100_000 ticks).
        assertThat(ended.reason).isEqualTo(
            EndReason.PillarHpDecision(
                TeamId("red"), EndReason.PillarHpDecision.Criterion.DAMAGE_DEALT,
                playHp = mapOf(TeamId("red") to 3000, TeamId("blue") to 3000, TeamId("yellow") to 2700),
                bleedTaken = mapOf(TeamId("red") to 0, TeamId("blue") to 0, TeamId("yellow") to 0),
            ))
    }

    @Test
    fun `tiebreak step 2 - equal HP and equal damage dealt go to the team with more kills`() {
        val ended = tiedAtTop("annihilation-phase-flow-tie-kills") { matchId, red, blue, yellow ->
            hit(matchId, red, "yellow", NexusPillarTier.TIER_1)
            advance(8)
            hit(matchId, blue, "yellow", NexusPillarTier.TIER_1)
            sim.services.getOrThrow<IDomainEventBus>().publish(PlayerKilled(yellow.id, blue.id, com.astarworks.astera.combat.domain.damage.DamageSource.Environment.Generic, 1L))
        }
        assertThat(ended.winningTeam).isEqualTo(TeamId("blue"))
        // 1000 max HP x 3 pillars = 3000 per team; one hit removes 300 (raw 100 x tool-skill match 3.0 x falloff 1.0 x phase 2 mult 1.0).
        // Yellow's tier 1 was hit twice (red, blue): play-HP red 3000, blue 3000, yellow 2400. No bleed (interval 100_000 ticks).
        assertThat(ended.reason).isEqualTo(
            EndReason.PillarHpDecision(
                TeamId("blue"), EndReason.PillarHpDecision.Criterion.KILLS,
                playHp = mapOf(TeamId("red") to 3000, TeamId("blue") to 3000, TeamId("yellow") to 2400),
                bleedTaken = mapOf(TeamId("red") to 0, TeamId("blue") to 0, TeamId("yellow") to 0),
            ))
    }

    @Test
    fun `tiebreak step 3 - equal HP, damage dealt and kills is a draw, never decided by team order`() {
        val ended = tiedAtTop("annihilation-phase-flow-tie-draw") { matchId, red, blue, _ ->
            hit(matchId, red, "yellow", NexusPillarTier.TIER_1)
            advance(8)
            hit(matchId, blue, "yellow", NexusPillarTier.TIER_1)
        }
        assertThat(ended.winningTeam).isNull()
        // Same hits as the kills case (yellow's tier 1 hit twice): play-HP red 3000, blue 3000, yellow 2400, no bleed; level on damage and kills = draw.
        assertThat(ended.reason).isEqualTo(
            EndReason.PillarHpDecision(
                null, EndReason.PillarHpDecision.Criterion.PILLAR_HP,
                playHp = mapOf(TeamId("red") to 3000, TeamId("blue") to 3000, TeamId("yellow") to 2400),
                bleedTaken = mapOf(TeamId("red") to 0, TeamId("blue") to 0, TeamId("yellow") to 0),
            ))
    }

    /** Hits [team]'s [tier] pillar until it is at [target] HP or lower (a first 100 hit measures the damage factor). */
    private fun ScenarioScope.drainTo(matchId: MatchId, attacker: SimEntity, team: String, tier: NexusPillarTier, target: Double) {
        val factor = checkNotNull(hit(matchId, attacker, team, tier)) / 100.0
        repeat(40) {
            val remaining = pillarHp(matchId, team, tier) - target
            if (remaining < 0.5) return
            checkNotNull(hit(matchId, attacker, team, tier, rawDamage = remaining / factor))
        }
    }

    @Test
    fun `the clock HP decision adds the bleed back, so a team with more pillars standing does not lose on bleed alone`() {
        // Pillar max HP 5000 / 5600 / 5600; 1% of max bled every 20 ticks for 24 intervals = 24% of each standing pillar's max.
        val cfg = config.copy(
            pillarHp = 5000, pillarHpTier2 = 5600, pillarHpTier3 = 5600,
            suddenDeathTicks = 490, bleedIntervalTicks = 20, bleedPercent = 1, bleedFloorPercent = 10,
        )
        scenario("annihilation-phase-flow-tiebreak-excludes-bleed", cfg) {
            val a = player("red-1", team = "red")
            val b = player("blue-1", team = "blue")
            val c = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(a to "red", b to "blue", c to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()

            // Blue's tier 1 falls in the clash phase; in the center phase blue's tier 2 falls and red is worn down to 8000 in total.
            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase2Engage }).isTrue()
            hit(matchId, a, "blue", NexusPillarTier.TIER_1, rawDamage = 1_000_000.0)
            assertThat(hit(matchId, a, "blue", NexusPillarTier.TIER_1)).`as`("blue tier 1 is destroyed").isNull()
            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase3Center }).isTrue()
            advance(5)
            hit(matchId, a, "blue", NexusPillarTier.TIER_2, rawDamage = 1_000_000.0)
            assertThat(hit(matchId, a, "blue", NexusPillarTier.TIER_2)).`as`("blue tier 2 is destroyed").isNull()
            // A third team only makes the match legal (min players); it is knocked out so red and blue decide on the clock.
            for (tier in NexusPillarTier.ordered()) hit(matchId, a, "yellow", tier, rawDamage = 1_000_000.0)
            drainTo(matchId, b, "red", NexusPillarTier.TIER_1, 2_500.0)
            drainTo(matchId, b, "red", NexusPillarTier.TIER_2, 2_800.0)
            drainTo(matchId, b, "red", NexusPillarTier.TIER_3, 2_700.0)
            val redStart = NexusPillarTier.ordered().sumOf { pillarHp(matchId, "red", it) }
            val blueStart = pillarHp(matchId, "blue", NexusPillarTier.TIER_3)
            assertThat(redStart).isBetween(7_990.0, 8_001.0)
            assertThat(blueStart).isEqualTo(5_600.0)

            // The whole last phase runs; read the real HP just before the clock ends it.
            assertThat(advanceUntil(2_000) { clock.current(matchId)?.phase == NexusPhase.Phase5SuddenDeath }).isTrue()
            advance(485)
            val redRaw = NexusPillarTier.ordered().sumOf { pillarHp(matchId, "red", it) }
            val blueRaw = pillarHp(matchId, "blue", NexusPillarTier.TIER_3)
            assertThat(eventsOf<MatchEnded>()).isEmpty()
            // Before the fix blue (4256) beat red (about 4112) on the raw numbers: red bled more only because it had more pillars.
            assertThat(blueRaw).isEqualTo(4_256.0)
            assertThat(redRaw).isBetween(redStart - 3_888.0 - 1.0, redStart - 3_888.0 + 1.0)
            assertThat(redRaw).`as`("the raw bled HP would have named blue").isLessThan(blueRaw)

            assertThat(advanceUntil(500) { eventsOf<MatchEnded>().isNotEmpty() }).isTrue()
            val ended = eventsOf<MatchEnded>().single()
            val reason = ended.reason as EndReason.PillarHpDecision
            assertThat(ended.winningTeam).`as`("red had more HP lost to play").isEqualTo(TeamId("red"))
            assertThat(reason.winner).isEqualTo(TeamId("red"))
            assertThat(reason.decidedBy).isEqualTo(EndReason.PillarHpDecision.Criterion.PILLAR_HP)
            // The bleed exclusion is recorded: play HP = standing HP + bleed taken, per team.
            assertThat(reason.bleedTaken[TeamId("red")]!!).isEqualTo(3_888)
            assertThat(reason.bleedTaken[TeamId("blue")]!!).isEqualTo(1_344)
            assertThat(reason.playHp[TeamId("red")]!!.toDouble()).isBetween(redStart - 1.0, redStart + 1.0)
            assertThat(reason.playHp[TeamId("blue")]).isEqualTo(5_600)
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
