package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.testfixtures.FakeCooldownTracker
import com.astarworks.astera.combat.testfixtures.FakeMatchTeamLookup
import com.astarworks.astera.combat.testfixtures.FakeNearbyTargetQuery
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectRegistry
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.DirectionRef
import com.astarworks.astera.core.domain.skill.targeter.LocationRef
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.BoolValue
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import com.astarworks.astera.testfixtures.FakePlayerHealthGateway
import com.astarworks.astera.testfixtures.FakePlayerPoseLookup
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP4: mechanics batch A (status / movement / messaging / attribute / cooldown) and the entity-state conditions. */
class MechanicBatchATest {

    private val caster = PlayerId(UUID.randomUUID())
    private val target = PlayerId(UUID.randomUUID())
    private val ally = PlayerId(UUID.randomUUID())

    private val scheduler = FakeScheduler()
    private val container = FakeStatusEffectContainer()
    private val registry = FakeStatusEffectRegistry()
    private val teams = FakeMatchTeamLookup()
    private val poses = FakePlayerPoseLookup()
    private val health = FakePlayerHealthGateway()
    private val cooldowns = FakeCooldownTracker()
    private val attributes = InMemoryPlayerAttributeContainer()
    private val renderer = FakeMessageRenderer()
    private val chat = FakePlayerGateway()
    private val effects = RecordingEffects()
    private val feedback = RecordingFeedback()
    private val applyStatus = ApplyStatusUseCase(registry, container, scheduler)

    private fun dispatcher(nearby: List<PlayerId> = emptyList()) = MechanicDispatcher(
        applyDamage = null,
        applyStatus = applyStatus,
        worldGateway = FakeWorldGateway(),
        knockbackGateway = null,
        nearbyQuery = FakeNearbyTargetQuery(nearby),
        broadcaster = null,
        celEvaluator = StubCelEvaluator(),
        scheduler = scheduler,
        eventBus = FakeDomainEventBus(),
        poseLookup = poses,
        teamLookup = teams,
        statusContainer = container,
        statusRegistry = registry,
        entityEffects = effects,
        feedback = feedback,
        messageRenderer = renderer,
        playerGateway = chat,
        cooldownTracker = cooldowns,
        attributeStore = attributes,
        playerHealth = { health },
        entityHealth = { null },
    )

    private fun ctx(targetId: PlayerId? = target) = MechanicContext(
        actorId = caster,
        targetId = targetId,
        origin = Vec3(0.0, 64.0, 0.0),
        pose = null,
    )

    private fun spec(id: String, kind: StatusKind) = StatusEffectSpec(
        id = StatusEffectId(id),
        displayNameKey = MessageKey("test.$id"),
        kind = kind,
        magnitude = 1.0,
        stackable = false,
        maxStacks = 1,
    )

    private fun give(id: PlayerId, spec: StatusEffectSpec) {
        container.apply(StatusEffectInstance(spec, id, remainingTicks = 100, currentStacks = 1, appliedAtTick = 0))
    }

    // ---- status mechanics --------------------------------------------------

    @Test
    fun `Cleanse removes harmful statuses and keeps beneficial ones`() {
        give(target, spec("burn", StatusKind.BURN))
        give(target, spec("haste", StatusKind.SPEED_UP))

        dispatcher().dispatch(listOf(Mechanic.Cleanse(Targeter.Target)), ctx())

        assertThat(container.has(target, StatusEffectId("burn"))).isFalse()
        assertThat(container.has(target, StatusEffectId("haste"))).isTrue()
        assertThat(effects.fire[target]).isZero()
    }

    @Test
    fun `Cleanse with onlyHarmful false removes everything`() {
        give(target, spec("burn", StatusKind.BURN))
        give(target, spec("haste", StatusKind.SPEED_UP))

        dispatcher().dispatch(listOf(Mechanic.Cleanse(Targeter.Target, BoolValue.Literal(false))), ctx())

        assertThat(container.active(target)).isEmpty()
    }

    @Test
    fun `RemoveStatus removes just that status`() {
        give(target, spec("burn", StatusKind.BURN))
        give(target, spec("poison", StatusKind.BURN))

        dispatcher().dispatch(listOf(Mechanic.RemoveStatus(Targeter.Target, StatusEffectId("burn"))), ctx())

        assertThat(container.has(target, StatusEffectId("burn"))).isFalse()
        assertThat(container.has(target, StatusEffectId("poison"))).isTrue()
    }

    @Test
    fun `Ignite sets fire ticks`() {
        dispatcher().dispatch(listOf(Mechanic.Ignite(Targeter.Target, IntValue.Literal(60))), ctx())

        assertThat(effects.fire[target]).isEqualTo(60)
    }

    @Test
    fun `Freeze applies the built-in freeze status and the body freeze`() {
        dispatcher().dispatch(listOf(Mechanic.Freeze(Targeter.Target, IntValue.Literal(40))), ctx())

        val frozen = container.active(target).single()
        assertThat(frozen.spec.id).isEqualTo(MechanicStatuses.FREEZE_ID)
        assertThat(frozen.remainingTicks).isEqualTo(40)
        assertThat(effects.frozen[target]).isEqualTo(40)
    }

    @Test
    fun `Freeze prefers a content freeze status over the built-in one`() {
        val content = spec("freeze", StatusKind.SPEED_DOWN)
        registry.register(content)

        dispatcher().dispatch(listOf(Mechanic.Freeze(Targeter.Target, IntValue.Literal(40))), ctx())

        assertThat(container.active(target).single().spec).isEqualTo(content)
    }

    @Test
    fun `Silence applies the silence status CastSkillUseCase checks`() {
        dispatcher().dispatch(listOf(Mechanic.Silence(Targeter.Target, IntValue.Literal(100))), ctx())

        assertThat(container.has(target, StatusEffectId("silence"))).isTrue()
    }

    @Test
    fun `statuses are not applied to a target that is already dead`() {
        health.setMaxHealth(target, 20.0)
        health.setHealth(target, 0.0, com.astarworks.astera.core.domain.player.physiology.HealthChangeSource.Other("test"))

        dispatcher().dispatch(
            listOf(
                Mechanic.Freeze(Targeter.Target, IntValue.Literal(40)),
                Mechanic.Silence(Targeter.Target, IntValue.Literal(40)),
                Mechanic.Ignite(Targeter.Target, IntValue.Literal(40)),
                Mechanic.Propel(Targeter.Target, DirectionRef.Up, NumericValue.Literal(1.0)),
            ),
            ctx(),
        )

        assertThat(container.active(target)).isEmpty()
        assertThat(effects.fire).isEmpty()
        assertThat(effects.velocity).isEmpty()
    }

    // ---- movement ----------------------------------------------------------

    @Test
    fun `SetVelocity replaces and Propel adds along the direction`() {
        val d = dispatcher()
        d.dispatch(listOf(Mechanic.SetVelocity(Targeter.Caster, DirectionRef.Up, NumericValue.Literal(0.5))), ctx())
        assertThat(effects.velocity[caster]).isEqualTo(Vec3(0.0, 0.5, 0.0))

        d.dispatch(listOf(Mechanic.Propel(Targeter.Caster, DirectionRef.Up, NumericValue.Literal(0.25))), ctx())
        assertThat(effects.velocity[caster]).isEqualTo(Vec3(0.0, 0.75, 0.0))
    }

    @Test
    fun `Propel with FromCaster uses the caster look direction`() {
        val withPose = ctx().copy(
            pose = com.astarworks.astera.core.domain.player.PlayerPose.standing(
                com.astarworks.astera.core.domain.world.WorldId("world"), Vec3.ZERO, Vec3(0.0, 0.0, 1.0),
            ),
        )

        dispatcher().dispatch(listOf(Mechanic.Propel(Targeter.Caster, DirectionRef.FromCaster, NumericValue.Literal(2.0))), withPose)

        assertThat(effects.velocity[caster]).isEqualTo(Vec3(0.0, 0.0, 2.0))
    }

    @Test
    fun `Leap launches upward and toward the destination`() {
        poses.place(caster, Vec3(0.0, 64.0, 0.0))
        val to = LocationRef.Absolute(com.astarworks.astera.core.domain.world.WorldId("world"), Vec3(10.0, 64.0, 0.0))

        dispatcher().dispatch(listOf(Mechanic.Leap(Targeter.Caster, to, NumericValue.Literal(2.0))), ctx())

        val v = effects.velocity.getValue(caster)
        assertThat(v.y).isGreaterThan(0.0)
        assertThat(v.x).isGreaterThan(0.0)
        assertThat(v.z).isCloseTo(0.0, Offset.offset(1e-12))
    }

    @Test
    fun `LeapPhysics lands on the destination when simulated without drag`() {
        val from = Vec3(0.0, 64.0, 0.0)
        val to = Vec3(12.0, 66.0, -5.0)
        val v = LeapPhysics.velocity(from, to, arcHeight = 3.0)

        var pos = from
        var vy = v.y
        var ticks = 0
        // Step until y comes back down through the destination height.
        do {
            pos = Vec3(pos.x + v.x, pos.y + vy, pos.z + v.z)
            vy -= LeapPhysics.GRAVITY
            ticks++
        } while (!(vy < 0 && pos.y <= to.y) && ticks < 1000)

        // Discrete stepping vs the continuous solution: within a block.
        assertThat(pos.x).isCloseTo(to.x, Offset.offset(1.0))
        assertThat(pos.z).isCloseTo(to.z, Offset.offset(1.0))
    }

    // ---- cooldown / attribute / messages ----------------------------------

    @Test
    fun `SetCooldown starts a skill cooldown and zero resets it`() {
        val d = dispatcher()
        d.dispatch(listOf(Mechanic.SetCooldown(Targeter.Caster, SkillId("fireball"), IntValue.Literal(60))), ctx())
        assertThat(cooldowns.isCoolingDown(caster, CooldownKey.Skill("fireball"))).isTrue()

        d.dispatch(listOf(Mechanic.SetCooldown(Targeter.Caster, SkillId("fireball"), IntValue.Literal(0))), ctx())
        assertThat(cooldowns.isCoolingDown(caster, CooldownKey.Skill("fireball"))).isFalse()
    }

    @Test
    fun `ModifyAttribute adds a modifier and removes it after its ticks`() {
        val key = AttributeKey("stat.attack_damage")
        val mechanic = Mechanic.ModifyAttribute(Targeter.Caster, "stat.attack_damage", NumericValue.Literal(3.0), IntValue.Literal(40))

        dispatcher().dispatch(listOf(mechanic), ctx())
        val container = attributes.containerFor(caster)
        assertThat(container.modifiers()).singleElement().satisfies({ m: AttributeModifier ->
            assertThat(m.key).isEqualTo(key)
            assertThat(m.value).isEqualTo(3.0)
        })

        scheduler.advanceTicks(39)
        assertThat(container.modifiers()).hasSize(1)
        scheduler.advanceTicks(1)
        assertThat(container.modifiers()).isEmpty()
    }

    @Test
    fun `ModifyAttribute with an invalid key is dropped`() {
        val mechanic = Mechanic.ModifyAttribute(Targeter.Caster, "NOT A KEY", NumericValue.Literal(1.0), IntValue.Literal(0))

        dispatcher().dispatch(listOf(mechanic), ctx())

        assertThat(attributes.containerFor(caster).modifiers()).isEmpty()
    }

    @Test
    fun `SendMessage ActionBar and Title are rendered per recipient`() {
        val hi = LocalizedText(mapOf(LocaleCode.EN to "Hello"))
        val sub = LocalizedText(mapOf(LocaleCode.EN to "World"))

        dispatcher().dispatch(
            listOf(
                Mechanic.SendMessage(Targeter.Target, hi),
                Mechanic.SendActionBar(Targeter.Target, hi),
                Mechanic.SendTitle(Targeter.Target, hi, sub, IntValue.Literal(5), IntValue.Literal(30), IntValue.Literal(7)),
            ),
            ctx(),
        )

        assertThat(chat.messages).containsExactly(target to "Hello")
        assertThat(feedback.actionBars).containsExactly(target to "Hello")
        assertThat(feedback.titles.single()).isEqualTo(listOf<Any?>(target, "Hello", "World", 5, 30, 7))
    }

    // ---- conditions --------------------------------------------------------

    private fun ignitesWhen(condition: Condition, nearby: List<PlayerId> = emptyList()): Boolean {
        effects.fire.clear()
        dispatcher(nearby).dispatch(
            listOf(Mechanic.Ignite(Targeter.Target, IntValue.Literal(10), condition = condition)),
            ctx(),
        )
        return effects.fire[target] == 10
    }

    @Test
    fun `IsAlly and IsEnemy follow team membership`() {
        teams.assign(caster, TeamId("red"))
        teams.assign(ally, TeamId("red"))
        teams.assign(target, TeamId("blue"))
        val allyCtx = ctx(ally)

        // target is an enemy of the caster, ally is not.
        assertThat(ignitesWhen(Condition.IsEnemy(Targeter.Caster, Targeter.Target))).isTrue()
        assertThat(ignitesWhen(Condition.IsAlly(Targeter.Caster, Targeter.Target))).isFalse()

        effects.fire.clear()
        dispatcher().dispatch(
            listOf(Mechanic.Ignite(Targeter.Target, IntValue.Literal(10), condition = Condition.IsEnemy(Targeter.Caster, Targeter.Target))),
            allyCtx,
        )
        assertThat(effects.fire).isEmpty()
    }

    @Test
    fun `an entity without a team is an enemy and never an ally`() {
        assertThat(ignitesWhen(Condition.IsEnemy(Targeter.Caster, Targeter.Target))).isTrue()
        assertThat(ignitesWhen(Condition.IsAlly(Targeter.Caster, Targeter.Target))).isFalse()
        // Self is always an ally.
        assertThat(ignitesWhen(Condition.IsAlly(Targeter.Target, Targeter.Target))).isTrue()
    }

    @Test
    fun `HasStatus checks the status container`() {
        val burning = Condition.HasStatus(Targeter.Target, StatusEffectId("burn"))
        assertThat(ignitesWhen(burning)).isFalse()

        give(target, spec("burn", StatusKind.BURN))
        assertThat(ignitesWhen(burning)).isTrue()
        assertThat(ignitesWhen(Condition.Not(burning))).isFalse()
    }

    @Test
    fun `HealthBelow compares the health fraction`() {
        health.setMaxHealth(target, 20.0)
        health.setHealth(target, 5.0, com.astarworks.astera.core.domain.player.physiology.HealthChangeSource.Other("test"))

        assertThat(ignitesWhen(Condition.HealthBelow(Targeter.Target, NumericValue.Literal(0.5)))).isTrue()
        assertThat(ignitesWhen(Condition.HealthBelow(Targeter.Target, NumericValue.Literal(0.25)))).isFalse()
    }

    @Test
    fun `HealthBelow is false when health is unknown`() {
        assertThat(ignitesWhen(Condition.HealthBelow(Targeter.Target, NumericValue.Literal(1.0)))).isFalse()
    }

    @Test
    fun `DistanceLessThan compares live positions`() {
        poses.place(caster, Vec3(0.0, 64.0, 0.0))
        poses.place(target, Vec3(3.0, 64.0, 4.0))

        assertThat(ignitesWhen(Condition.DistanceLessThan(Targeter.Caster, Targeter.Target, NumericValue.Literal(5.1)))).isTrue()
        assertThat(ignitesWhen(Condition.DistanceLessThan(Targeter.Caster, Targeter.Target, NumericValue.Literal(5.0)))).isFalse()
    }

    @Test
    fun `Enemies targeter excludes allies`() {
        teams.assign(caster, TeamId("red"))
        teams.assign(ally, TeamId("red"))
        teams.assign(target, TeamId("blue"))

        dispatcher(nearby = listOf(caster, ally, target)).dispatch(
            listOf(Mechanic.Ignite(Targeter.Enemies(Targeter.Caster), IntValue.Literal(10))),
            ctx(),
        )

        assertThat(effects.fire.keys).containsExactly(target)
    }

    // ---- fakes -------------------------------------------------------------

    private class RecordingEffects : IEntityEffectsGateway {
        val velocity = mutableMapOf<PlayerId, Vec3>()
        val fire = mutableMapOf<PlayerId, Int>()
        val frozen = mutableMapOf<PlayerId, Int>()

        override fun setVelocity(target: PlayerId, velocity: Vec3) {
            this.velocity[target] = velocity
        }

        override fun addVelocity(target: PlayerId, delta: Vec3) {
            velocity[target] = (velocity[target] ?: Vec3.ZERO) + delta
        }

        override fun setFireTicks(target: PlayerId, ticks: Int) {
            fire[target] = ticks
        }

        override fun freeze(target: PlayerId, ticks: Int) {
            frozen[target] = ticks
        }

        override fun thaw(target: PlayerId) {
            frozen[target] = 0
        }

        override fun setGlowing(target: PlayerId, glowing: Boolean) = Unit
    }

    private class RecordingFeedback : IPlayerFeedbackGateway {
        val actionBars = mutableListOf<Pair<PlayerId, String>>()
        val titles = mutableListOf<List<Any?>>()

        override fun sendActionBar(target: PlayerId, rendered: String) {
            actionBars += target to rendered
        }

        override fun sendTitle(
            target: PlayerId,
            title: String,
            subtitle: String?,
            fadeInTicks: Int,
            stayTicks: Int,
            fadeOutTicks: Int,
        ) {
            titles += listOf(target, title, subtitle, fadeInTicks, stayTicks, fadeOutTicks)
        }

        override fun cameraKick(target: PlayerId, intensity: Double) = Unit
    }
}
