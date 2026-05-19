package com.astarworks.astera.combat.application.usecase.skill

import com.astarworks.astera.combat.application.usecase.cost.ConsumeManaUseCase
import com.astarworks.astera.combat.domain.event.ManaConsumed
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.combat.testfixtures.FakeCooldownTracker
import com.astarworks.astera.combat.testfixtures.FakeManaPoolService
import com.astarworks.astera.combat.testfixtures.FakeSkillRegistry
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
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
    private lateinit var cooldownTracker: FakeCooldownTracker
    private lateinit var scheduler: FakeScheduler
    private lateinit var useCase: CastSkillUseCase

    private val spec = SkillSpec(
        id = skillId,
        name = LocalizedText(mapOf(LocaleCode.EN to "Fireball")),
        description = LocalizedText(mapOf(LocaleCode.EN to "Fireball desc")),
        archetype = SkillArchetype.PROJECTILE,
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
        cooldownTracker = FakeCooldownTracker()
        scheduler = FakeScheduler(startTick = 0L)
        useCase = CastSkillUseCase(
            skillRegistry = skillRegistry,
            statusContainer = statusContainer,
            eventBus = eventBus,
            cooldownTracker = cooldownTracker,
            scheduler = scheduler,
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

        // First cast succeeds and starts cooldown (spec.cooldownTicks = 40)
        val first = useCase.execute(casterId, skillId)
        assertThat(first).isInstanceOf(Result.Success::class.java)

        // Advance time by 10 ticks — still within the 40-tick cooldown window
        scheduler.advanceTicks(10)
        cooldownTracker.currentTick = scheduler.currentTick()

        // Second cast immediately → on cooldown
        val second = useCase.execute(casterId, skillId)
        assertThat(second).isInstanceOf(Result.Failure::class.java)
        val error = (second as Result.Failure).error
        assertThat(error).isInstanceOf(CastSkillError.OnCooldown::class.java)
        assertThat((error as CastSkillError.OnCooldown).skillId).isEqualTo(skillId)
        // remainingMs = remainingTicks * 50ms (30 ticks left → 1500ms)
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

    @Test
    fun `mana cost - sufficient pool consumes mana and publishes both events`() {
        val manaService = FakeManaPoolService()
        manaService.init(casterId, max = 100L, regenPerSec = 1.0)
        val consumeMana = ConsumeManaUseCase(manaService = manaService, eventBus = eventBus)
        val withCost = CastSkillUseCase(
            skillRegistry = skillRegistry,
            statusContainer = statusContainer,
            eventBus = eventBus,
            cooldownTracker = cooldownTracker,
            scheduler = scheduler,
            consumeMana = consumeMana,
        )
        val specWithCost = spec.copy(manaCost = 30L)
        skillRegistry.register(specWithCost)

        val result = withCost.execute(casterId, skillId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        // Pool was debited 30.
        assertThat(manaService.get(casterId)?.current).isEqualTo(70L)
        // Both events fired: ManaConsumed first (debit), then SkillCast (commit).
        val consumed = eventBus.publishedOfType<ManaConsumed>()
        assertThat(consumed).hasSize(1)
        assertThat(consumed[0].amount).isEqualTo(30L)
        assertThat(consumed[0].remaining).isEqualTo(70L)
        val casts = eventBus.publishedOfType<SkillCast>()
        assertThat(casts).hasSize(1)
        assertThat(casts[0].caster).isEqualTo(casterId)
    }

    @Test
    fun `mana cost - empty pool returns InsufficientMana and SkillCast is not published`() {
        val manaService = FakeManaPoolService()
        manaService.init(casterId, max = 10L, regenPerSec = 1.0)
        val consumeMana = ConsumeManaUseCase(manaService = manaService, eventBus = eventBus)
        val withCost = CastSkillUseCase(
            skillRegistry = skillRegistry,
            statusContainer = statusContainer,
            eventBus = eventBus,
            cooldownTracker = cooldownTracker,
            scheduler = scheduler,
            consumeMana = consumeMana,
        )
        val specWithCost = spec.copy(manaCost = 30L)
        skillRegistry.register(specWithCost)

        val result = withCost.execute(casterId, skillId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(CastSkillError.InsufficientMana::class.java)
        val insufficient = error as CastSkillError.InsufficientMana
        assertThat(insufficient.have).isEqualTo(10L)
        assertThat(insufficient.tried).isEqualTo(30L)

        // Pool unchanged (mana was not debited).
        assertThat(manaService.get(casterId)?.current).isEqualTo(10L)
        // No SkillCast → downstream mechanics don't trigger.
        assertThat(eventBus.publishedOfType<SkillCast>()).isEmpty()
        // No ManaConsumed either (failure short-circuits before publish).
        assertThat(eventBus.publishedOfType<ManaConsumed>()).isEmpty()
        // Cooldown is not started → player can retry once regen catches up.
        assertThat(cooldownTracker.isCoolingDown(casterId, CooldownKey.Skill(skillId.value))).isFalse()
    }
}
