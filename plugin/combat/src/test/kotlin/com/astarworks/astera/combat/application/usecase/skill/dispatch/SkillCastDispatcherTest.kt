package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.FriendlyFirePolicy
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.domain.skill.ChargedSkillVariant
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillId
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.SkillTrigger
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.combat.testfixtures.FakeArmorEquipmentProvider
import com.astarworks.astera.combat.testfixtures.FakeDamageGateway
import com.astarworks.astera.combat.testfixtures.FakeDamageRandomizer
import com.astarworks.astera.combat.testfixtures.FakeKnockbackGateway
import com.astarworks.astera.combat.testfixtures.FakeNearbyTargetQuery
import com.astarworks.astera.combat.testfixtures.FakeProjectileService
import com.astarworks.astera.combat.testfixtures.FakeSkillRegistry
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectRegistry
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeWorldGateway
import com.astarworks.astera.weapons.domain.projectile.HitProfile
import com.astarworks.astera.weapons.domain.projectile.LifetimePolicy
import com.astarworks.astera.weapons.domain.projectile.MotionProfile
import com.astarworks.astera.weapons.domain.projectile.OnHitEffect
import com.astarworks.astera.weapons.domain.weapon.DamageProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5a: SkillCastDispatcher closes the [SkillCast] subscriber loop. One
 * test per archetype proves the right handler ran with the right inputs.
 *
 * Each test wires the full bus → dispatcher chain so a regression in
 * subscription wiring (not just the handler bodies) fails the build.
 */
class SkillCastDispatcherTest {

    private val caster = PlayerId(UUID.randomUUID())
    private val target1 = PlayerId(UUID.randomUUID())
    private val target2 = PlayerId(UUID.randomUUID())
    private val target3 = PlayerId(UUID.randomUUID())
    private val castPos = Vec3(0.0, 64.0, 0.0)

    private lateinit var skillRegistry: FakeSkillRegistry
    private lateinit var damageGateway: FakeDamageGateway
    private lateinit var knockback: FakeKnockbackGateway
    private lateinit var statusContainer: FakeStatusEffectContainer
    private lateinit var statusRegistry: FakeStatusEffectRegistry
    private lateinit var armorProvider: FakeArmorEquipmentProvider
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var worldGateway: FakeWorldGateway
    private lateinit var projectileService: FakeProjectileService
    private lateinit var nearbyQuery: FakeNearbyTargetQuery

    private lateinit var applyDamage: ApplyDamageUseCase
    private lateinit var applyStatus: ApplyStatusUseCase

    @BeforeEach
    fun setUp() {
        skillRegistry = FakeSkillRegistry()
        damageGateway = FakeDamageGateway()
        knockback = FakeKnockbackGateway()
        statusContainer = FakeStatusEffectContainer()
        statusRegistry = FakeStatusEffectRegistry()
        armorProvider = FakeArmorEquipmentProvider()
        eventBus = FakeDomainEventBus()
        worldGateway = FakeWorldGateway()
        projectileService = FakeProjectileService()
        nearbyQuery = FakeNearbyTargetQuery(players = emptyList())

        val rule = DamageRule(
            elementMatrix = IElementMatrix.Neutral,
            modifiers = emptyList(),
            friendlyFire = FriendlyFirePolicy.Allow,
            randomizer = FakeDamageRandomizer(listOf(0.99)),
        )
        applyDamage = ApplyDamageUseCase(
            damageGateway = damageGateway,
            knockbackGateway = knockback,
            statusContainer = statusContainer,
            damageRule = rule,
            armorProvider = armorProvider,
            teamLookup = null,
            eventBus = eventBus,
        )
        applyStatus = ApplyStatusUseCase(
            effectRegistry = statusRegistry,
            effectContainer = statusContainer,
            eventBus = eventBus,
        )
    }

    private fun dispatcherWith(query: FakeNearbyTargetQuery = nearbyQuery): SkillCastDispatcher {
        nearbyQuery = query
        return SkillCastDispatcher(
            skillRegistry = skillRegistry,
            applyDamage = applyDamage,
            applyStatus = applyStatus,
            worldGateway = worldGateway,
            projectileService = projectileService,
            nearbyQuery = query,
            eventBus = eventBus,
        )
    }

    private fun skillSpec(
        id: String,
        archetype: SkillArchetype,
        params: SkillParams,
    ): SkillSpec = SkillSpec(
        id = SkillId(id),
        displayNameKey = MessageKey("test.skill.$id"),
        descriptionKey = MessageKey("test.skill.$id.desc"),
        archetype = archetype,
        trigger = SkillTrigger.RIGHT_CLICK,
        cooldownTicks = 0,
        params = params,
        chargeVariant = ChargedSkillVariant.Instant,
    )

    private fun cast(id: String, at: Vec3 = castPos): SkillCast =
        SkillCast(caster = caster, skillId = SkillId(id), at = at, atTick = 100L)

    // PROJECTILE -------------------------------------------------------------

    @Test
    fun `PROJECTILE archetype - publishes spawn through IProjectileService`() {
        val spec = skillSpec(
            id = "fireball",
            archetype = SkillArchetype.PROJECTILE,
            params = SkillParams.ProjectileParams(
                launchSpeed = 24.0,
                maxLifetimeTicks = 40,
                damage = DamageProfile(base = 5.0, attribute = DamageAttribute.FIRE),
            ),
        )
        skillRegistry.register(spec)
        val dispatcher = dispatcherWith()
        dispatcher.start()

        eventBus.publish(cast("fireball"))

        assertThat(projectileService.spawned).hasSize(1)
        val s = projectileService.spawned[0]
        assertThat(s.owner).isEqualTo(caster)
        assertThat(s.origin).isEqualTo(castPos)
        assertThat(s.direction).isEqualTo(ProjectileHandler.DEFAULT_DIRECTION)
        assertThat(s.spec.motion).isInstanceOf(MotionProfile.Linear::class.java)
        assertThat((s.spec.motion as MotionProfile.Linear).initialSpeed).isEqualTo(24.0)
        assertThat(s.spec.hit).isInstanceOf(HitProfile.Sphere::class.java)
        assertThat(s.spec.lifetime).isInstanceOf(LifetimePolicy.Ticks::class.java)
        assertThat((s.spec.lifetime as LifetimePolicy.Ticks).maxTicks).isEqualTo(40)
        assertThat(s.spec.onHit).hasSize(1).first().isInstanceOf(OnHitEffect.DealDamage::class.java)
        val dealDamage = s.spec.onHit[0] as OnHitEffect.DealDamage
        assertThat(dealDamage.profile.base).isEqualTo(5.0)
        assertThat(dealDamage.profile.attribute).isEqualTo(DamageAttribute.FIRE)
    }

    // MELEE ------------------------------------------------------------------

    @Test
    fun `MELEE archetype - applies damage to each nearby player except caster`() {
        val spec = skillSpec(
            id = "sword-spin",
            archetype = SkillArchetype.MELEE,
            params = SkillParams.MeleeParams(
                range = 3.0,
                knockback = 0.4,
                damage = DamageProfile(base = 8.0, attribute = DamageAttribute.PHYSICAL),
            ),
        )
        skillRegistry.register(spec)
        // Includes caster — should be filtered out by the handler.
        val dispatcher = dispatcherWith(FakeNearbyTargetQuery(players = listOf(target1, target2, target3, caster)))
        dispatcher.start()

        eventBus.publish(cast("sword-spin"))

        assertThat(damageGateway.applied).hasSize(3)
        assertThat(damageGateway.applied.map { it.first })
            .containsExactlyInAnyOrder(target1, target2, target3)
        // Verify the query was called with the right (center, radius).
        assertThat(nearbyQuery.calls).hasSize(1)
        assertThat(nearbyQuery.calls[0].center).isEqualTo(castPos)
        assertThat(nearbyQuery.calls[0].radius).isEqualTo(3.0)
    }

    // AOE --------------------------------------------------------------------

    @Test
    fun `AOE archetype - applies falloff-scaled damage to each nearby player`() {
        val baseDamage = 20.0
        val spec = skillSpec(
            id = "thunder-clap",
            archetype = SkillArchetype.AOE,
            params = SkillParams.AoeParams(
                radius = 6.0,
                damage = DamageProfile(base = baseDamage, attribute = DamageAttribute.ELECTRIC),
                falloff = Easing.LINEAR,
            ),
        )
        skillRegistry.register(spec)
        val dispatcher = dispatcherWith(FakeNearbyTargetQuery(players = listOf(target1, target2, caster)))
        dispatcher.start()

        eventBus.publish(cast("thunder-clap"))

        // Caster excluded → exactly two applications.
        assertThat(damageGateway.applied).hasSize(2)
        // LINEAR.apply(0.5) = 0.5 → multiplier = 1 - 0.5 = 0.5 → resolved = 10.0
        val expected = baseDamage * (1.0 - Easing.LINEAR.apply(AoeHandler.AOE_AVERAGE_DISTANCE_RATIO))
        for (entry in damageGateway.applied) {
            assertThat(entry.second.finalAmount).isEqualTo(expected)
            assertThat(entry.second.attribute).isEqualTo(DamageAttribute.ELECTRIC)
        }
    }

    // BUFF -------------------------------------------------------------------

    @Test
    fun `BUFF archetype - applies status effect to caster`() {
        val effectId = StatusEffectId("speed-up")
        statusRegistry.register(
            StatusEffectSpec(
                id = effectId,
                displayNameKey = MessageKey("test.speed-up"),
                kind = StatusKind.SPEED_UP,
                magnitude = 1.5,
                stackable = false,
                maxStacks = 1,
            ),
        )
        val spec = skillSpec(
            id = "haste",
            archetype = SkillArchetype.BUFF,
            params = SkillParams.BuffParams(
                statusEffectId = effectId,
                durationTicks = 100,
            ),
        )
        skillRegistry.register(spec)
        val dispatcher = dispatcherWith()
        dispatcher.start()

        eventBus.publish(cast("haste"))

        assertThat(statusContainer.has(caster, effectId)).isTrue()
        val active = statusContainer.active(caster).toList()
        assertThat(active).hasSize(1)
        assertThat(active[0].remainingTicks).isEqualTo(100)
    }

    // TELEPORT ---------------------------------------------------------------

    @Test
    fun `TELEPORT archetype - delegates to IWorldGateway teleport`() {
        val spec = skillSpec(
            id = "blink",
            archetype = SkillArchetype.TELEPORT,
            params = SkillParams.TeleportParams(
                maxDistance = 8.0,
                requiresLineOfSight = false,
            ),
        )
        skillRegistry.register(spec)
        val dispatcher = dispatcherWith()
        dispatcher.start()

        eventBus.publish(cast("blink"))

        assertThat(worldGateway.teleports).hasSize(1)
        val tp = worldGateway.teleports[0]
        assertThat(tp.playerId).isEqualTo(caster)
        // origin + DEFAULT_DIRECTION * 8.0
        assertThat(tp.at).isEqualTo(castPos + TeleportHandler.DEFAULT_DIRECTION * 8.0)
        assertThat(tp.world).isNull()
    }

    // SUMMON -----------------------------------------------------------------

    @Test
    fun `SUMMON archetype - stub does not crash and produces no side effects`() {
        val spec = skillSpec(
            id = "summon-imp",
            archetype = SkillArchetype.SUMMON,
            params = SkillParams.SummonParams(
                entityTypeKey = "ZOMBIE",
                count = 1,
                lifetimeTicks = 200,
            ),
        )
        skillRegistry.register(spec)
        val dispatcher = dispatcherWith()
        dispatcher.start()

        eventBus.publish(cast("summon-imp"))

        // Nothing else should have happened.
        assertThat(projectileService.spawned).isEmpty()
        assertThat(damageGateway.applied).isEmpty()
        assertThat(worldGateway.teleports).isEmpty()
        // statusContainer untouched (caster has no effects).
        assertThat(statusContainer.active(caster)).isEmpty()
    }

    // Missing spec -----------------------------------------------------------

    @Test
    fun `unknown skill id - dropped silently without throwing`() {
        // No spec registered.
        val dispatcher = dispatcherWith()
        dispatcher.start()

        eventBus.publish(cast("ghost-skill"))

        assertThat(projectileService.spawned).isEmpty()
        assertThat(damageGateway.applied).isEmpty()
        assertThat(worldGateway.teleports).isEmpty()
    }

    // Subscription cancel ----------------------------------------------------

    @Test
    fun `cancelled subscription - no further dispatch on subsequent events`() {
        val spec = skillSpec(
            id = "blink2",
            archetype = SkillArchetype.TELEPORT,
            params = SkillParams.TeleportParams(maxDistance = 2.0, requiresLineOfSight = false),
        )
        skillRegistry.register(spec)
        val dispatcher = dispatcherWith()
        val sub = dispatcher.start()

        eventBus.publish(cast("blink2"))
        assertThat(worldGateway.teleports).hasSize(1)

        sub.cancel()
        eventBus.publish(cast("blink2"))
        assertThat(worldGateway.teleports).hasSize(1) // unchanged
    }
}
