package com.astarworks.astera.combat.application.usecase.skill

import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillId
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.SkillTrigger
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.combat.testfixtures.FakeSkillRegistry
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.weapons.domain.weapon.DamageProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class CastSkillUseCaseTest {

    private val casterId = PlayerId(UUID.randomUUID())
    private val skillId = SkillId("fireball")

    private lateinit var skillRegistry: FakeSkillRegistry
    private lateinit var statusContainer: FakeStatusEffectContainer
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var useCase: CastSkillUseCase

    private val spec = SkillSpec(
        id = skillId,
        displayNameKey = MessageKey("test.fireball"),
        descriptionKey = MessageKey("test.fireball.desc"),
        archetype = SkillArchetype.PROJECTILE,
        trigger = SkillTrigger.RIGHT_CLICK,
        cooldownTicks = 40,
        params = SkillParams.ProjectileParams(
            launchSpeed = 20.0,
            maxLifetimeTicks = 60,
            damage = DamageProfile(base = 8.0, attribute = DamageAttribute.FIRE),
        ),
    )

    @BeforeEach
    fun setUp() {
        skillRegistry = FakeSkillRegistry()
        statusContainer = FakeStatusEffectContainer()
        eventBus = FakeDomainEventBus()
        useCase = CastSkillUseCase(
            skillRegistry = skillRegistry,
            statusContainer = statusContainer,
            eventBus = eventBus,
        )
    }

    @Test
    fun `happy path - publishes SkillCast and returns spec`() {
        skillRegistry.register(spec)

        val result = useCase.execute(casterId, skillId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(spec)

        val cast = eventBus.publishedOfType<SkillCast>()
        assertThat(cast).hasSize(1)
        assertThat(cast[0].caster).isEqualTo(casterId)
        assertThat(cast[0].skillId).isEqualTo(skillId)
    }

    @Test
    fun `unknown skill - returns SkillNotFound, no event published`() {
        // Do NOT register the spec

        val result = useCase.execute(casterId, skillId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(CastSkillError.SkillNotFound::class.java)
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `on cooldown - returns OnCooldown after first cast`() {
        skillRegistry.register(spec)

        // First cast succeeds and starts cooldown
        val first = useCase.execute(casterId, skillId)
        assertThat(first).isInstanceOf(Result.Success::class.java)

        // Second cast immediately → on cooldown
        val second = useCase.execute(casterId, skillId)
        assertThat(second).isInstanceOf(Result.Failure::class.java)
        val error = (second as Result.Failure).error
        assertThat(error).isInstanceOf(CastSkillError.OnCooldown::class.java)
        assertThat((error as CastSkillError.OnCooldown).skillId).isEqualTo(skillId)
        assertThat(error.remainingMs).isPositive()
    }

    @Test
    fun `silenced - returns Silenced, no event published`() {
        skillRegistry.register(spec)
        val silenceSpec = StatusEffectSpec(
            id = StatusEffectId("silence"),
            displayNameKey = MessageKey("test.silence"),
            kind = StatusKind.PARALYSIS,
            magnitude = 0.0,
            stackable = false,
            maxStacks = 1,
        )
        val silenceInstance = StatusEffectInstance(
            spec = silenceSpec,
            targetPlayerId = casterId,
            remainingTicks = 100,
            currentStacks = 1,
            appliedAtTick = 0L,
        )
        statusContainer.apply(silenceInstance)

        val result = useCase.execute(casterId, skillId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(CastSkillError.Silenced::class.java)
        assertThat(eventBus.published).isEmpty()
    }
}
