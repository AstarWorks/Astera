package com.astarworks.astera.match.application.gamemode.annihilation

import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.stage.StagePillar
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP10: the rules between a hit and [AnnihilationMatchTracker.recordDamage]. */
class AnnihilationPillarTargetsTest {

    private val matchId = MatchId("pt-1")
    private val world = WorldId("world")
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val redPlayer = PlayerId(UUID.randomUUID())
    private val bluePlayer = PlayerId(UUID.randomUUID())
    private val outsider = PlayerId(UUID.randomUUID())
    private val config = AnnihilationConfig(pillarHp = 100, pillarBreakDamage = 30)

    private val bus = FakeDomainEventBus()
    private val tracker = AnnihilationMatchTracker(bus)
    private val matches = InMemoryMatchRepository()
    private val targets = AnnihilationPillarTargets(tracker, matches, FakeScheduler())

    private val blueT1 = StagePillar(blue, NexusPillarTier.TIER_1, Vec3(10.0, -60.0, 0.0), BlockType("minecraft:obsidian"))
    private val blueT2 = StagePillar(blue, NexusPillarTier.TIER_2, Vec3(12.0, -60.0, 0.0), BlockType("minecraft:crying_obsidian"), hp = 250)
    private val redT1 = StagePillar(red, NexusPillarTier.TIER_1, Vec3(-10.0, -60.0, 0.0), BlockType("minecraft:obsidian"))

    init {
        matches.save(
            Match(
                id = matchId,
                phase = MatchPhase.ACTIVE,
                teams = listOf(team(red, redPlayer), team(blue, bluePlayer)),
                score = Score(mapOf(red to 0, blue to 0)),
                stageId = StageId("arena"),
                startedAtTick = 0L,
            ),
        )
        tracker.register(matchId, config, listOf(red, blue), world = world, pillarSites = listOf(blueT1, blueT2, redT1))
    }

    private fun team(id: TeamId, member: PlayerId) =
        Team(id, MessageKey("astera.match.team.${id.value}"), MessageKey("astera.match.team.${id.value}.color"), setOf(member))

    private fun open() {
        tracker.advancePhasesIfNeeded(matchId, config.buildPhaseTicks) // -> Phase 2, Tier 1 vulnerable
    }

    private fun idOf(team: String, tier: Int) = AnnihilationPillarTargets.idOf("${matchId.value}-$team-$tier")

    @Test
    fun `stage hp overrides the mode's pillar hp`() {
        assertThat(targets.maxHitPoints(idOf("blue", 1))).isEqualTo(100.0)
        assertThat(targets.maxHitPoints(idOf("blue", 2))).isEqualTo(250.0)
    }

    @Test
    fun `a pillar is immune during the Build phase`() {
        val r = targets.damage(idOf("blue", 1), redPlayer, 50.0)
        assertThat((r as Result.Failure).error).isEqualTo(IStructureTargets.Rejection.NOT_VULNERABLE)
        assertThat(targets.hitPoints(idOf("blue", 1))).isEqualTo(100.0)
        assertThat(targets.findInRadius(blueT1.centre, 3.0, world)).isEmpty()
    }

    @Test
    fun `an enemy damages a vulnerable pillar and destroys it`() {
        open()
        assertThat(targets.findInRadius(blueT1.centre, 3.0, world).map { it.id }).containsExactly(idOf("blue", 1))
        assertThat((targets.damage(idOf("blue", 1), redPlayer, 40.2) as Result.Success).value).isEqualTo(59.0) // rounds up to 41
        assertThat((targets.damage(idOf("blue", 1), redPlayer, 500.0) as Result.Success).value).isEqualTo(0.0)
        assertThat(targets.findInRadius(blueT1.centre, 1.0, world)).`as`("destroyed pillars are not targets").isEmpty()
        assertThat(targets.isStructure(idOf("blue", 1))).isTrue()
    }

    @Test
    fun `a team cannot damage its own pillar`() {
        open()
        val r = targets.damage(idOf("blue", 1), bluePlayer, 50.0)
        assertThat((r as Result.Failure).error).isEqualTo(IStructureTargets.Rejection.FRIENDLY)
        assertThat(targets.hitPoints(idOf("blue", 1))).isEqualTo(100.0)
    }

    @Test
    fun `players outside the match and environmental damage are rejected`() {
        open()
        assertThat((targets.damage(idOf("blue", 1), outsider, 50.0) as Result.Failure).error)
            .isEqualTo(IStructureTargets.Rejection.NO_ATTACKER)
        assertThat((targets.damage(idOf("blue", 1), null, 50.0) as Result.Failure).error)
            .isEqualTo(IStructureTargets.Rejection.NO_ATTACKER)
        assertThat((targets.damage(PlayerId(UUID.randomUUID()), redPlayer, 50.0) as Result.Failure).error)
            .isEqualTo(IStructureTargets.Rejection.UNKNOWN_TARGET)
    }

    @Test
    fun `an enemy block break deals pillarBreakDamage and must be cancelled`() {
        open()
        val v = targets.onBlockBreak(redPlayer, world, blueT1.position)
        assertThat(v).isEqualTo(AnnihilationPillarTargets.BlockBreakVerdict.Pillar(damaged = true))
        assertThat(targets.hitPoints(idOf("blue", 1))).isEqualTo(70.0)
    }

    @Test
    fun `breaking your own pillar block is cancelled without damage, other blocks are none of our business`() {
        open()
        assertThat(targets.onBlockBreak(bluePlayer, world, blueT1.position))
            .isEqualTo(AnnihilationPillarTargets.BlockBreakVerdict.Pillar(damaged = false))
        assertThat(targets.hitPoints(idOf("blue", 1))).isEqualTo(100.0)
        assertThat(targets.onBlockBreak(redPlayer, world, Vec3(0.0, -61.0, 0.0)))
            .isEqualTo(AnnihilationPillarTargets.BlockBreakVerdict.NotAPillar)
    }

    @Test
    fun `destroying Tier 1 opens that team's Tier 2 but not the others`() {
        open()
        targets.damage(idOf("blue", 1), redPlayer, 500.0)
        assertThat(targets.findInRadius(blueT2.centre, 1.0, world).map { it.id }).containsExactly(idOf("blue", 2))
        assertThat(targets.findInRadius(redT1.centre, 1.0, world).map { it.id }).containsExactly(idOf("red", 1))
    }

    @Test
    fun `projectiles landing on a pillar block find it, a destroyed pillar frees the block`() {
        open()
        assertThat(targets.targetAtBlock(world, blueT1.position)?.id).isEqualTo(idOf("blue", 1))
        targets.damage(idOf("blue", 1), redPlayer, 500.0)
        assertThat(targets.targetAtBlock(world, blueT1.position)).isNull()
        assertThat(targets.onBlockBreak(redPlayer, world, blueT1.position))
            .isEqualTo(AnnihilationPillarTargets.BlockBreakVerdict.NotAPillar)
    }

    @Test
    fun `a match without stage sites has no structure targets`() {
        val bare = MatchId("bare")
        tracker.register(bare, config, listOf(red, blue))
        assertThat(tracker.allActive()).contains(bare)
        assertThat(targets.isStructure(AnnihilationPillarTargets.idOf("bare-red-1"))).isFalse()
    }
}
