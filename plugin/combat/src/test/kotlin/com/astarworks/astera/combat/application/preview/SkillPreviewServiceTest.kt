package com.astarworks.astera.combat.application.preview

import com.astarworks.astera.combat.application.admin.AdminSkillCaster
import com.astarworks.astera.combat.application.port.outbound.IEntityCasterBody
import com.astarworks.astera.combat.application.port.outbound.ISkillPreviewStage
import com.astarworks.astera.combat.application.port.outbound.ITickRateControl
import com.astarworks.astera.combat.application.port.outbound.PreviewStage
import com.astarworks.astera.combat.application.port.outbound.PreviewStageSpec
import com.astarworks.astera.combat.application.usecase.skill.CastSkillUseCase
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.combat.testfixtures.FakeCooldownTracker
import com.astarworks.astera.combat.testfixtures.FakeSkillRegistry
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakePlayerPoseLookup
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class SkillPreviewServiceTest {

    private val viewer = PlayerId(UUID.randomUUID())
    private val skillId = SkillId("preview-slash")

    private class FakeStage : ISkillPreviewStage {
        val spawned = mutableListOf<PreviewStageSpec>()
        val despawned = mutableListOf<PreviewStage>()
        val placed = mutableListOf<ViewPoint>()
        var restored = 0
        var heals = 0
        var quit: (PlayerId) -> Unit = {}
        override fun spawn(spec: PreviewStageSpec): PreviewStage {
            spawned += spec
            return PreviewStage(PlayerId(UUID.randomUUID()), PlayerId(UUID.randomUUID()), spec)
        }
        override fun healTarget(stage: PreviewStage) { heals++ }
        override fun despawn(stage: PreviewStage) { despawned += stage }
        override fun placeViewer(viewer: PlayerId, point: ViewPoint) { placed += point }
        override fun restoreViewer(viewer: PlayerId) { restored++ }
        override fun onViewerQuit(handler: (PlayerId) -> Unit) { quit = handler }
    }

    private class FakeTick : ITickRateControl {
        var currentRate = 20f
        var isFrozenNow = false
        var stepped = 0
        override fun rate() = currentRate
        override fun setRate(ticksPerSecond: Float) { currentRate = ticksPerSecond }
        override fun isFrozen() = isFrozenNow
        override fun setFrozen(frozen: Boolean) { isFrozenNow = frozen }
        override fun step(ticks: Int): Boolean { if (isFrozenNow) stepped += ticks; return isFrozenNow }
    }

    private lateinit var stage: FakeStage
    private lateinit var tick: FakeTick
    private lateinit var scheduler: FakeScheduler
    private lateinit var events: FakeDomainEventBus
    private lateinit var service: SkillPreviewService
    private val castFailures = mutableListOf<PreviewError.CastFailed>()

    @BeforeEach
    fun setUp() {
        val registry = FakeSkillRegistry()
        registry.register(
            SkillSpec(
                id = skillId,
                name = LocalizedText(mapOf(LocaleCode.EN to "Preview Slash")),
                description = LocalizedText(mapOf(LocaleCode.EN to "desc")),
                archetype = SkillArchetype.MELEE,
                cooldownTicks = 0,
                params = SkillParams.MeleeParams(
                    range = 3.0,
                    knockback = 0.5,
                    damage = DamageProfile.single(5.0, DamageAttribute.PHYSICAL),
                ),
                phases = com.astarworks.astera.core.domain.skill.phase.PhaseMechanics.Melee(),
            ),
        )
        scheduler = FakeScheduler()
        events = FakeDomainEventBus()
        val cooldowns = FakeCooldownTracker()
        val castSkill = CastSkillUseCase(registry, FakeStatusEffectContainer(), events, cooldowns, scheduler)
        val poses = FakePlayerPoseLookup().also { it.place(viewer, Vec3(0.0, 64.0, 0.0), Vec3(0.0, 0.0, 1.0)) }
        stage = FakeStage()
        tick = FakeTick()
        service = SkillPreviewService(
            stage, tick, AdminSkillCaster(registry, castSkill, cooldowns), poses, items = null, scheduler = scheduler,
            onCastFailed = { _, e -> castFailures += e },
        )
    }

    @Test
    fun `start spawns dummies, parks the camera and casts as the dummy after the lead`() {
        val result = service.start(viewer, skillId, PreviewOptions(PreviewView.SIDE), operator = true)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(stage.spawned.single().casterAt).isEqualTo(Vec3(0.0, 64.0, 3.0))
        assertThat(stage.spawned.single().weaponMaterial).isEqualTo(SkillPreviewService.FALLBACK_WEAPON)
        assertThat(stage.placed).hasSize(1)
        assertThat(events.publishedOfType<SkillCast>()).isEmpty()

        scheduler.advanceTicks(SkillPreviewService.LEAD_TICKS)

        val casts = events.publishedOfType<SkillCast>()
        assertThat(casts).hasSize(1)
        assertThat(casts[0].caster).isNotEqualTo(viewer)
    }

    @Test
    fun `cleanup after playback restores camera and despawns the dummies`() {
        service.start(viewer, skillId, PreviewOptions(PreviewView.STAY), operator = true)
        assertThat(stage.placed).isEmpty()

        scheduler.advanceTicks(SkillPreviewService.LEAD_TICKS + SkillPreviewService.PLAYBACK_TICKS + SkillPreviewService.CLEANUP_TICKS)

        assertThat(stage.despawned).hasSize(1)
        assertThat(stage.restored).isEqualTo(1)
        assertThat(stage.heals).isGreaterThan(0)
    }

    @Test
    fun `slow speed needs an operator and the tick rate is restored on end`() {
        val denied = service.start(viewer, skillId, PreviewOptions(speed = 0.5), operator = false)
        assertThat((denied as Result.Failure).error).isEqualTo(PreviewError.SpeedNeedsOperator)
        assertThat(stage.spawned).isEmpty()

        service.start(viewer, skillId, PreviewOptions(speed = 0.25), operator = true)
        scheduler.advanceTicks(SkillPreviewService.LEAD_TICKS)
        assertThat(tick.currentRate).isEqualTo(5f)

        service.end()
        assertThat(tick.currentRate).isEqualTo(20f)
        assertThat(stage.despawned).hasSize(1)
    }

    @Test
    fun `again repeats the last preview and replaces the running one`() {
        assertThat((service.again(viewer, true) as Result.Failure).error).isEqualTo(PreviewError.NothingToRepeat)

        service.start(viewer, skillId, PreviewOptions(PreviewView.OPPONENT), operator = true)
        service.again(viewer, true)

        assertThat(stage.spawned).hasSize(2)
        assertThat(stage.despawned).hasSize(1)
    }

    @Test
    fun `viewer quit ends the session and unknown skills are rejected`() {
        service.start(viewer, skillId, PreviewOptions(), operator = true)
        stage.quit(viewer)
        assertThat(stage.despawned).hasSize(1)

        val unknown = service.start(viewer, SkillId("nope"), PreviewOptions(), operator = true)
        assertThat((unknown as Result.Failure).error).isEqualTo(PreviewError.SkillNotFound(SkillId("nope")))
    }

    @Test
    fun `freeze and step go through the tick control, shutdown unfreezes`() {
        assertThat((service.step(3) as Result.Failure).error).isEqualTo(PreviewError.NotFrozen)
        service.freeze()
        assertThat(service.step(3)).isInstanceOf(Result.Success::class.java)
        assertThat(tick.stepped).isEqualTo(3)
        service.shutdown()
        assertThat(tick.isFrozenNow).isFalse()
    }

    @Test
    fun `entity caster body widens pose lookup and teleport to non-player ids`() {
        val dummy = PlayerId(UUID.randomUUID())
        val pose = PlayerPose.standing(WorldId("world"), Vec3(1.0, 2.0, 3.0), Vec3(0.0, 0.0, 1.0))
        val teleports = mutableListOf<PlayerId>()
        val body = object : IEntityCasterBody {
            override fun poseOf(id: PlayerId) = if (id == dummy) pose else null
            override fun teleport(id: PlayerId, at: Vec3, world: WorldId?, yaw: Float?, pitch: Float?): Boolean {
                teleports += id
                return id == dummy
            }
        }
        val players = FakePlayerPoseLookup().also { it.place(viewer, Vec3.ZERO) }
        val poses = EntityFallbackPoseLookup(players, body)
        assertThat(poses.poseOf(viewer)).isNotNull()
        assertThat(poses.poseOf(dummy)).isEqualTo(pose)
        assertThat(poses.poseOf(PlayerId(UUID.randomUUID()))).isNull()

        val worlds = FakeWorldGateway().also { it.teleportResult = false }
        val gateway = EntityTeleportingWorldGateway(worlds, body)
        assertThat(gateway.teleport(dummy, Vec3(5.0, 5.0, 5.0))).isTrue()
        assertThat(gateway.teleport(PlayerId(UUID.randomUUID()), Vec3.ZERO)).isFalse()
        assertThat(teleports).hasSize(2)
        assertThat(worlds.teleports).hasSize(2)
    }
}
