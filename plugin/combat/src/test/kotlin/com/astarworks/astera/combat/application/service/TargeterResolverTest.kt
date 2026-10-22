package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.testfixtures.FakeNearbyTargetQuery
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.targeter.DirectionRef
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeMatchTeamLookup
import com.astarworks.astera.testfixtures.FakePlayerPoseLookup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID

class TargeterResolverTest {

    private val world = WorldId("world")
    private val caster = PlayerId(UUID.randomUUID())
    private val near = PlayerId(UUID.randomUUID())
    private val far = PlayerId(UUID.randomUUID())
    private val behind = PlayerId(UUID.randomUUID())
    private val offAxis = PlayerId(UUID.randomUUID())

    private val poses = FakePlayerPoseLookup().apply {
        // Caster at origin looking +X (eye at y=1.62).
        place(caster, Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), world)
        place(near, Vec3(5.0, 0.0, 0.0), world = world)
        place(far, Vec3(15.0, 0.0, 0.0), world = world)
        place(behind, Vec3(-4.0, 0.0, 0.0), world = world)
        place(offAxis, Vec3(5.0, 0.0, 6.0), world = world)
    }

    private fun resolver(
        candidates: List<PlayerId> = listOf(caster, near, far, behind, offAxis),
        teams: FakeMatchTeamLookup? = null,
        withPoses: Boolean = true,
    ) = TargeterResolver(
        nearbyQuery = FakeNearbyTargetQuery(candidates),
        log = LoggerFactory.getLogger("test"),
        poseLookup = if (withPoses) poses else null,
        teamLookup = teams,
    )

    private fun ctx() = MechanicContext(
        actorId = caster,
        origin = Vec3.ZERO,
        worldId = world,
        pose = poses.poseOf(caster),
    )

    @Test
    fun `forward ray returns the nearest player along the look direction`() {
        val result = resolver().resolvePlayers(
            Targeter.ForwardRay(Targeter.Caster, NumericValue.Literal(30.0)),
            ctx(),
        )
        assertThat(result).containsExactly(near)
    }

    @Test
    fun `forward ray respects max distance`() {
        val result = resolver().resolvePlayers(
            Targeter.ForwardRay(Targeter.Caster, NumericValue.Literal(3.0)),
            ctx(),
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `forward ray without pose lookup returns empty`() {
        val result = resolver(withPoses = false).resolvePlayers(
            Targeter.ForwardRay(Targeter.Caster, NumericValue.Literal(30.0)),
            ctx(),
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `forward ray follows a changed look direction`() {
        poses.set(caster, PlayerPose.standing(world, Vec3.ZERO, Vec3(0.0, 0.0, 1.0)))
        val result = resolver().resolvePlayers(
            Targeter.ForwardRay(Targeter.Caster, NumericValue.Literal(30.0)),
            ctx().copy(pose = poses.poseOf(caster)),
        )
        // +Z ray from the eye at x=0 misses everyone (offAxis is at x=5).
        assertThat(result).isEmpty()
    }

    @Test
    fun `cone selects players inside the half angle ordered nearest first`() {
        val result = resolver().resolvePlayers(
            Targeter.Cone(
                from = Targeter.Caster,
                direction = DirectionRef.FromCaster,
                angleDegrees = NumericValue.Literal(60.0),
                distance = NumericValue.Literal(20.0),
            ),
            ctx(),
        )
        assertThat(result).containsExactly(near, far)
    }

    @Test
    fun `cone distance and angle exclude out of range players`() {
        val result = resolver().resolvePlayers(
            Targeter.Cone(
                from = Targeter.Caster,
                direction = DirectionRef.FromCaster,
                angleDegrees = NumericValue.Literal(20.0),
                distance = NumericValue.Literal(8.0),
            ),
            ctx(),
        )
        assertThat(result).containsExactly(near)
    }

    @Test
    fun `enemies excludes caster and teammates`() {
        val teams = FakeMatchTeamLookup().apply {
            place(caster, TeamId("red"))
            place(near, TeamId("red"))
            place(far, TeamId("blue"))
        }
        val result = resolver(teams = teams).resolvePlayers(Targeter.Enemies(Targeter.Caster), ctx())
        assertThat(result).containsExactlyInAnyOrder(far, behind, offAxis)
    }

    @Test
    fun `enemies without team info is everyone but the caster`() {
        val result = resolver().resolvePlayers(Targeter.Enemies(Targeter.Caster), ctx())
        assertThat(result).containsExactlyInAnyOrder(near, far, behind, offAxis)
    }

    @Test
    fun `allies are same team members with optional self`() {
        val teams = FakeMatchTeamLookup().apply {
            place(caster, TeamId("red"))
            place(near, TeamId("red"))
            place(far, TeamId("blue"))
        }
        val r = resolver(teams = teams)
        assertThat(r.resolvePlayers(Targeter.Allies(Targeter.Caster), ctx())).containsExactly(near)
        assertThat(r.resolvePlayers(Targeter.Allies(Targeter.Caster, includeSelf = true), ctx()))
            .containsExactly(caster, near)
    }

    @Test
    fun `allies without team info is only self when requested`() {
        val r = resolver()
        assertThat(r.resolvePlayers(Targeter.Allies(Targeter.Caster), ctx())).isEmpty()
        assertThat(r.resolvePlayers(Targeter.Allies(Targeter.Caster, includeSelf = true), ctx()))
            .containsExactly(caster)
    }

    @Test
    fun `hit location targeter anchors on context hit location, caster on origin`() {
        val c = ctx().copy(hitLocation = Vec3(9.0, 2.0, 1.0))
        val r = resolver()
        assertThat(r.resolveLocations(Targeter.HitLocation, c)).containsExactly(Vec3(9.0, 2.0, 1.0))
        assertThat(r.resolveLocations(Targeter.Caster, c)).containsExactly(Vec3.ZERO)
    }

    @Test
    fun `target location uses live target position`() {
        val c = ctx().copy(targetId = near, hitLocation = Vec3(9.0, 2.0, 1.0))
        assertThat(resolver().resolveLocations(Targeter.Target, c)).containsExactly(Vec3(5.0, 0.0, 0.0))
    }

    @Test
    fun `filter evaluates its condition per candidate with that candidate as target`() {
        val r = resolver()
        r.conditionGate = { _, c -> c.targetId == near || c.targetId == far }
        val nearby = Targeter.NearbyEntities(Targeter.Caster, NumericValue.Literal(100.0))
        val result = r.resolvePlayers(
            Targeter.Filter(nearby, com.astarworks.astera.core.domain.skill.condition.Condition.Cel("x")),
            ctx(),
        )
        assertThat(result).containsExactlyInAnyOrder(near, far)
    }
}
