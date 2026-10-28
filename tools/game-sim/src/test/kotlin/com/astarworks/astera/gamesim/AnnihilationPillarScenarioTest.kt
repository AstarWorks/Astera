package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.NexusDestroyed
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.stage.StagePillar
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarTargets
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.gamemode.annihilation.PillarDamageTuning
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.application.port.outbound.IStructureTargets.DamageKind
import com.astarworks.astera.core.application.port.outbound.IStructureTargets.DamageSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * WP10: Annihilation pillars take damage from skills. Three teams, one player each.
 * Red burns blue's pillars (Build immunity, tier opening, the Tier 1 + 2 cascade into
 * Phase 3, elimination), cannot hurt its own, then takes yellow's too and wins.
 */
class AnnihilationPillarScenarioTest {

    private val config = AnnihilationConfig(
        pillarHp = 40,
        // The siege test casts a plain weapon skill from 5 blocks: count it in full so 40 HP stays a handful of casts.
        pillarDamage = PillarDamageTuning(weaponMult = 1.0),
        buildPhaseTicks = 200,
        engagePhaseTicks = 100_000,
        tier3PhaseTicks = 100_000,
    )

    @Test
    fun `red destroys blue then yellow pillars with skills and wins`() {
        scenario("annihilation-pillar-siege", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")

            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matchId = (lifecycle.start(StageId("annihilation-arena"), "annihilation") as Result.Success).value
            lifecycle.entry(matchId, red.id, TeamId("red"))
            lifecycle.entry(matchId, blue.id, TeamId("blue"))
            lifecycle.entry(matchId, yellow.id, TeamId("yellow"))
            lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
            advance(40)
            assertThat(eventsOf<MatchStarted>().map { it.matchId }).contains(matchId)

            val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena")))
            val targets = sim.services.getOrThrow<IStructureTargets>()
            val pillars = stage.pillars.associateBy { it.team.value to it.tier }
            val world = stage.world

            fun pillar(team: String, tier: NexusPillarTier): StagePillar = checkNotNull(pillars[team to tier])
            fun id(team: String, tier: NexusPillarTier) =
                AnnihilationPillarTargets.idOf("${matchId.value}-$team-${tier.ordinalValue}")
            fun hp(team: String, tier: NexusPillarTier): Double = checkNotNull(targets.hitPoints(id(team, tier)))
            fun destroyed() = eventsOf<NexusDestroyed>()

            // The arena's pillar blocks stand in the world from the match start.
            pillars.values.forEach { assertThat(sim.world.blockAt(world, it.position)).isEqualTo(it.block) }
            // The scenario config sets one pillar hp for every tier (the shipped stage has no per-pillar hp override).
            assertThat(hp("blue", NexusPillarTier.TIER_1)).isEqualTo(40.0)
            assertThat(hp("blue", NexusPillarTier.TIER_3)).isEqualTo(40.0)

            // blue / yellow stay out of the line of fire.
            blue.position = Vec3(0.0, groundY, 0.0)
            yellow.position = Vec3(0.0, groundY, 1.0)

            /** Red stands 5 blocks from [p] and burns it with flame-burst until [done]. */
            fun siege(p: StagePillar, maxCasts: Int = 120, done: () -> Boolean) {
                red.position = Vec3(p.centre.x, p.position.y, p.centre.z + 5.0)
                red.lookAt(p.centre)
                var casts = 0
                while (!done() && casts++ < maxCasts) {
                    red.cast("flame-burst")
                    advance(85)
                }
                assertThat(done()).`as`("pillar at ${p.position} fell within $maxCasts casts").isTrue()
            }

            // --- Phase 1 (Build): pillars are immune --------------------------------
            red.position = Vec3(pillar("blue", NexusPillarTier.TIER_1).centre.x, pillar("blue", NexusPillarTier.TIER_1).position.y, pillar("blue", NexusPillarTier.TIER_1).centre.z + 5.0)
            red.holds("fire-staff")
            red.lookAt(pillar("blue", NexusPillarTier.TIER_1).centre)
            red.cast("flame-burst")
            advance(60)
            assertThat(hp("blue", NexusPillarTier.TIER_1)).`as`("Build phase is immune").isEqualTo(40.0)

            advance(200) // Build ends -> Phase 2 opens Tier 1

            // --- own pillar: a team cannot damage itself ----------------------------
            val ownT1 = pillar("red", NexusPillarTier.TIER_1)
            red.position = Vec3(ownT1.centre.x, ownT1.position.y, ownT1.centre.z - 5.0)
            red.lookAt(ownT1.centre)
            repeat(3) { red.cast("flame-burst"); advance(85) }
            assertThat(hp("red", NexusPillarTier.TIER_1)).`as`("ally damage is ignored").isEqualTo(40.0)
            assertThat(destroyed()).isEmpty()

            // --- blue falls: Tier 1 -> its Tier 2 opens -> cascade -> Phase 3 -> Tier 3 -
            val blueT1 = pillar("blue", NexusPillarTier.TIER_1)
            siege(blueT1) { destroyed().any { it.owningTeam.value == "blue" && it.tier == NexusPillarTier.TIER_1 } }
            assertThat(sim.world.blockAt(world, blueT1.position)).`as`("destroyed pillar block is gone").isNull()
            assertThat(destroyed().first().attackerId).isEqualTo(red.id)

            val blueT2 = pillar("blue", NexusPillarTier.TIER_2)
            siege(blueT2) { destroyed().any { it.owningTeam.value == "blue" && it.tier == NexusPillarTier.TIER_2 } }
            assertThat(eventsOf<TeamEliminated>()).isEmpty()
            advance(40) // the mode tick notices the lower-tier cascade -> Phase 3 -> Tier 3 opens

            val blueT3 = pillar("blue", NexusPillarTier.TIER_3)
            siege(blueT3, maxCasts = 200) { eventsOf<TeamEliminated>().any { it.teamId.value == "blue" } }
            assertThat(eventsOf<MatchEnded>()).`as`("two teams still stand").isEmpty()

            // --- yellow falls too: red is the last team with pillars ---------------
            for (tier in NexusPillarTier.ordered()) {
                val p = pillar("yellow", tier)
                siege(p, maxCasts = 200) {
                    destroyed().any { it.owningTeam.value == "yellow" && it.tier == tier }
                }
                advance(40)
            }

            assertThat(eventsOf<TeamEliminated>().map { it.teamId.value }).containsExactly("blue", "yellow")
            val ended = eventsOf<MatchEnded>().single()
            assertThat(ended.matchId).isEqualTo(matchId)
            assertThat(ended.winningTeam).isEqualTo(TeamId("red"))
            assertThat(ended.reason).isEqualTo(EndReason.TeamVictory(TeamId("red")))
            // Red's own pillars were never touched.
            assertThat(destroyed().none { it.owningTeam.value == "red" }).isTrue()
        }
    }

    /**
     * MODE-A1/A2: pillars are mining blocks. A tool skill of the pillar's affinity beats a tool of another class, which beats a
     * weapon; a shot from beyond the far radius does nothing; tier 3 (hard stone / pickaxe) falls to a tool skill like the others.
     * The shipped stage declares tier 1 axe, tier 2 shovel, tier 3 pickaxe; tuning is the shipped default.
     */
    @Test
    fun `matching tool skill beats other tool beats weapon and far hits do nothing`() {
        val mining = AnnihilationConfig(
            pillarHp = 900,
            pillarHpTier2 = 300,
            pillarHpTier3 = 100_000,
            buildPhaseTicks = 200,
            engagePhaseTicks = 100_000,
            tier3PhaseTicks = 100_000,
        )
        scenario("annihilation-pillar-mining", mining) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matchId = (lifecycle.start(StageId("annihilation-arena"), "annihilation") as Result.Success).value
            lifecycle.entry(matchId, red.id, TeamId("red"))
            lifecycle.entry(matchId, blue.id, TeamId("blue"))
            lifecycle.entry(matchId, yellow.id, TeamId("yellow"))
            lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
            advance(40)
            val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena")))
            val targets = sim.services.getOrThrow<IStructureTargets>()
            val pillars = stage.pillars.associateBy { it.team.value to it.tier }
            fun pillar(team: String, tier: NexusPillarTier): StagePillar = checkNotNull(pillars[team to tier])
            fun id(team: String, tier: NexusPillarTier) =
                AnnihilationPillarTargets.idOf("${matchId.value}-$team-${tier.ordinalValue}")
            fun hp(team: String, tier: NexusPillarTier): Double = checkNotNull(targets.hitPoints(id(team, tier)))
            blue.position = Vec3(0.0, groundY, 0.0)
            yellow.position = Vec3(0.0, groundY, 1.0)

            assertThat(pillar("blue", NexusPillarTier.TIER_1).affinity).isEqualTo(ToolKind.AXE)
            assertThat(pillar("blue", NexusPillarTier.TIER_2).affinity).isEqualTo(ToolKind.SHOVEL)
            assertThat(pillar("blue", NexusPillarTier.TIER_3).affinity).isEqualTo(ToolKind.PICKAXE)

            advance(200) // Build ends -> Tier 1 opens
            val t1 = pillar("blue", NexusPillarTier.TIER_1)
            val near = Vec3(t1.centre.x, t1.position.y, t1.centre.z + 5.0)
            val far = Vec3(t1.centre.x, t1.position.y, t1.centre.z + 40.0)
            /** One hit of raw 100 (a tool skill's break_area block_damage) and the HP it removed; null when rejected. */
            fun hit(kind: DamageKind, tool: ToolKind, from: Vec3): Double? {
                val before = hp("blue", NexusPillarTier.TIER_1)
                val r = targets.damage(id("blue", NexusPillarTier.TIER_1), red.id, 100.0, DamageSource(kind, tool, from))
                return if (r is Result.Success) before - r.value else null
            }

            val matching = checkNotNull(hit(DamageKind.TOOL_SKILL, ToolKind.AXE, near))
            val otherTool = checkNotNull(hit(DamageKind.TOOL_SKILL, ToolKind.PICKAXE, near))
            val weapon = checkNotNull(hit(DamageKind.WEAPON, ToolKind.NONE, near))
            assertThat(matching).`as`("matching tool skill").isEqualTo(300.0)
            assertThat(otherTool).`as`("non-matching tool skill").isEqualTo(100.0)
            assertThat(weapon).`as`("weapon hit").isLessThan(otherTool).isGreaterThan(0.0)
            assertThat(matching).isGreaterThan(otherTool)
            val hpBeforeFar = hp("blue", NexusPillarTier.TIER_1)
            assertThat(hit(DamageKind.TOOL_SKILL, ToolKind.AXE, far)).`as`("beyond the far radius").isNull()
            assertThat(hit(DamageKind.WEAPON, ToolKind.NONE, far)).isNull()
            assertThat(hp("blue", NexusPillarTier.TIER_1)).isEqualTo(hpBeforeFar)
            // Between near (8) and far (32) the damage falls off linearly: 20 blocks = half.
            val mid = Vec3(t1.centre.x, t1.position.y, t1.centre.z + 20.0)
            assertThat(checkNotNull(hit(DamageKind.TOOL_SKILL, ToolKind.AXE, mid))).isEqualTo(150.0)

            // Fell tier 1 and tier 2 with their matching tools, then tier 3 (hard stone) takes a pickaxe skill.
            while ((targets.hitPoints(id("blue", NexusPillarTier.TIER_1)) ?: 0.0) > 0.0) hit(DamageKind.TOOL_SKILL, ToolKind.AXE, near)
            val t2 = pillar("blue", NexusPillarTier.TIER_2)
            val t2Id = id("blue", NexusPillarTier.TIER_2)
            targets.damage(t2Id, red.id, 100.0, DamageSource(DamageKind.TOOL_SKILL, ToolKind.SHOVEL, Vec3(t2.centre.x, t2.position.y, t2.centre.z + 5.0)))
            advance(40) // the mode tick notices the cascade -> Phase 3 -> Tier 3 opens
            val t3 = pillar("blue", NexusPillarTier.TIER_3)
            val t3Id = id("blue", NexusPillarTier.TIER_3)
            val t3Before = hp("blue", NexusPillarTier.TIER_3)
            val r = targets.damage(t3Id, red.id, 100.0, DamageSource(DamageKind.TOOL_SKILL, ToolKind.PICKAXE, Vec3(t3.centre.x, t3.position.y, t3.centre.z + 5.0)))
            assertThat(r).isInstanceOf(Result.Success::class.java)
            assertThat(t3Before - hp("blue", NexusPillarTier.TIER_3)).`as`("pickaxe skill on a hard stone pillar").isEqualTo(300.0)
        }
    }

    @Test
    fun `a solo annihilation playtest does not end by itself`() {
        scenario("annihilation-solo-playtest", config) {
            val solo = player("solo", team = "red")
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matchId: MatchId = (lifecycle.start(StageId("annihilation-arena"), "annihilation") as Result.Success).value
            lifecycle.entry(matchId, solo.id, TeamId("red"))
            // The admin force-start waives the roster floor (min 3 players); without it the countdown guard cancels a lone playtest.
            sim.services.getOrThrow<com.astarworks.astera.match.application.admin.MatchMinPlayerWaivers>().waive(matchId)
            lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
            advance(600)
            assertThat(eventsOf<MatchStarted>()).isNotEmpty()
            assertThat(eventsOf<MatchEnded>()).`as`("empty enemy teams do not end the match").isEmpty()
        }
    }
}
