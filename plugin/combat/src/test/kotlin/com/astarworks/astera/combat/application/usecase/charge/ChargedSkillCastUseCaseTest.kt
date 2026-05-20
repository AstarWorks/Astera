package com.astarworks.astera.combat.application.usecase.charge

import com.astarworks.astera.combat.application.service.ChargeTracker
import com.astarworks.astera.combat.application.usecase.skill.CastSkillUseCase
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.event.SkillChargeCancelled
import com.astarworks.astera.combat.domain.event.SkillChargeStarted
import com.astarworks.astera.combat.domain.skill.ChargedSkillVariant
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.combat.testfixtures.FakeCooldownTracker
import com.astarworks.astera.combat.testfixtures.FakeSkillRegistry
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class ChargedSkillCastUseCaseTest {

    private val casterId = PlayerId(UUID.randomUUID())
    private val chargedId = SkillId("charged-blast")
    private val instantId = SkillId("instant-zap")

    private lateinit var skillRegistry: FakeSkillRegistry
    private lateinit var statusContainer: FakeStatusEffectContainer
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var cooldownTracker: FakeCooldownTracker
    private lateinit var scheduler: FakeScheduler
    private lateinit var castSkill: CastSkillUseCase
    private lateinit var chargeTracker: ChargeTracker
    private lateinit var useCase: ChargedSkillCastUseCase

    private val chargedSpec = SkillSpec(
        id = chargedId,
        name = LocalizedText(mapOf(LocaleCode.EN to "Charged Blast")),
        description = LocalizedText(mapOf(LocaleCode.EN to "Charged Blast desc")),
        archetype = SkillArchetype.PROJECTILE,
        cooldownTicks = 0,
        params = SkillParams.ProjectileParams(
            launchSpeed = 20.0,
            maxLifetimeTicks = 60,
            damage = DamageProfile.single(8.0, DamageAttribute.FIRE),
        ),
        phases = com.astarworks.astera.core.domain.skill.phase.PhaseMechanics.Projectile(),
        chargeVariant = ChargedSkillVariant.Charged(chargeTicks = 30),
    )

    private val instantSpec = SkillSpec(
        id = instantId,
        name = LocalizedText(mapOf(LocaleCode.EN to "Instant Zap")),
        description = LocalizedText(mapOf(LocaleCode.EN to "Instant Zap desc")),
        archetype = SkillArchetype.MELEE,
        cooldownTicks = 0,
        params = SkillParams.MeleeParams(
            range = 3.0,
            knockback = 0.5,
            damage = DamageProfile.single(5.0, DamageAttribute.PHYSICAL),
        ),
        phases = com.astarworks.astera.core.domain.skill.phase.PhaseMechanics.Melee(),
    )

    @BeforeEach
    fun setUp() {
        skillRegistry = FakeSkillRegistry()
        statusContainer = FakeStatusEffectContainer()
        eventBus = FakeDomainEventBus()
        cooldownTracker = FakeCooldownTracker()
        scheduler = FakeScheduler(startTick = 0L)
        castSkill = CastSkillUseCase(
            skillRegistry = skillRegistry,
            statusContainer = statusContainer,
            eventBus = eventBus,
            cooldownTracker = cooldownTracker,
            scheduler = scheduler,
        )
        chargeTracker = ChargeTracker(scheduler = scheduler)
        useCase = ChargedSkillCastUseCase(
            castSkill = castSkill,
            chargeTracker = chargeTracker,
            eventBus = eventBus,
            skillRegistry = skillRegistry,
            scheduler = scheduler,
        )
    }

    @Test
    fun `startCharge publishes SkillChargeStarted and timer fires SkillCast after chargeTicks`() {
        skillRegistry.register(chargedSpec)

        val result = useCase.startCharge(casterId, chargedId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value.chargeTicks).isEqualTo(30L)

        val started = eventBus.publishedOfType<SkillChargeStarted>()
        assertThat(started).hasSize(1)
        assertThat(started[0].caster).isEqualTo(casterId)
        assertThat(started[0].skillId).isEqualTo(chargedId)
        assertThat(started[0].chargeTicks).isEqualTo(30L)
        assertThat(started[0].atTick).isEqualTo(0L)

        // No SkillCast yet — timer hasn't fired.
        assertThat(eventBus.publishedOfType<SkillCast>()).isEmpty()

        // Advance 29 ticks — still 1 tick before the timer fires.
        scheduler.advanceTicks(29)
        cooldownTracker.currentTick = scheduler.currentTick()
        assertThat(eventBus.publishedOfType<SkillCast>()).isEmpty()

        // Final tick — timer fires, CastSkillUseCase.execute() runs.
        scheduler.advanceTicks(1)
        cooldownTracker.currentTick = scheduler.currentTick()
        val casts = eventBus.publishedOfType<SkillCast>()
        assertThat(casts).hasSize(1)
        assertThat(casts[0].caster).isEqualTo(casterId)
        assertThat(casts[0].skillId).isEqualTo(chargedId)

        // No cancel event was emitted.
        assertThat(eventBus.publishedOfType<SkillChargeCancelled>()).isEmpty()

        // Tracker entry is cleared on natural completion.
        assertThat(chargeTracker.activeChargeFor(casterId)).isNull()
    }

    @Test
    fun `cancelCharge publishes SkillChargeCancelled and suppresses SkillCast`() {
        skillRegistry.register(chargedSpec)

        useCase.startCharge(casterId, chargedId)
        scheduler.advanceTicks(10)
        cooldownTracker.currentTick = scheduler.currentTick()

        val result = useCase.cancelCharge(casterId)
        assertThat(result).isInstanceOf(Result.Success::class.java)

        val cancelled = eventBus.publishedOfType<SkillChargeCancelled>()
        assertThat(cancelled).hasSize(1)
        assertThat(cancelled[0].caster).isEqualTo(casterId)
        assertThat(cancelled[0].skillId).isEqualTo(chargedId)
        assertThat(cancelled[0].elapsedTicks).isEqualTo(10L)

        // Advance past the original chargeTicks — the timer must NOT fire
        // because the task was cancelled.
        scheduler.advanceTicks(30)
        cooldownTracker.currentTick = scheduler.currentTick()
        assertThat(eventBus.publishedOfType<SkillCast>()).isEmpty()

        assertThat(chargeTracker.activeChargeFor(casterId)).isNull()
    }

    @Test
    fun `startCharge on an instant skill returns NotAChargedSkill failure`() {
        skillRegistry.register(instantSpec)

        val result = useCase.startCharge(casterId, instantId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(ChargedSkillError.NotAChargedSkill::class.java)
        assertThat((error as ChargedSkillError.NotAChargedSkill).skillId).isEqualTo(instantId)

        // No charge-related events were published.
        assertThat(eventBus.publishedOfType<SkillChargeStarted>()).isEmpty()
        assertThat(eventBus.publishedOfType<SkillCast>()).isEmpty()
    }

    @Test
    fun `startCharge on unknown skill returns UnknownSkill failure`() {
        // Do not register anything.

        val result = useCase.startCharge(casterId, chargedId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(ChargedSkillError.UnknownSkill::class.java)
    }

    @Test
    fun `second startCharge while charging silently displaces the first`() {
        skillRegistry.register(chargedSpec)

        // First charge at tick 0.
        useCase.startCharge(casterId, chargedId)
        scheduler.advanceTicks(10)
        cooldownTracker.currentTick = scheduler.currentTick()

        // Second charge at tick 10 — displaces the first without publishing
        // SkillChargeCancelled (the player chose to retarget).
        val second = useCase.startCharge(casterId, chargedId)
        assertThat(second).isInstanceOf(Result.Success::class.java)

        // Two start events; zero cancel events.
        assertThat(eventBus.publishedOfType<SkillChargeStarted>()).hasSize(2)
        assertThat(eventBus.publishedOfType<SkillChargeCancelled>()).isEmpty()

        // Advance past the *first* charge's original deadline (tick 30 in
        // absolute terms). The first timer was displaced, so no cast yet.
        scheduler.advanceTicks(20) // now at tick 30
        cooldownTracker.currentTick = scheduler.currentTick()
        assertThat(eventBus.publishedOfType<SkillCast>()).isEmpty()

        // Advance to the *second* charge's deadline (tick 40 in absolute
        // terms). Now the second timer fires.
        scheduler.advanceTicks(10) // now at tick 40
        cooldownTracker.currentTick = scheduler.currentTick()
        assertThat(eventBus.publishedOfType<SkillCast>()).hasSize(1)
    }

    @Test
    fun `releaseCharge fires SkillCast immediately and cancels the pending timer`() {
        skillRegistry.register(chargedSpec)

        useCase.startCharge(casterId, chargedId)
        scheduler.advanceTicks(5)
        cooldownTracker.currentTick = scheduler.currentTick()

        val result = useCase.releaseCharge(casterId)
        assertThat(result).isInstanceOf(Result.Success::class.java)

        // SkillCast is published immediately.
        assertThat(eventBus.publishedOfType<SkillCast>()).hasSize(1)
        // No cancel event on release.
        assertThat(eventBus.publishedOfType<SkillChargeCancelled>()).isEmpty()

        // Advance past the original chargeTicks deadline — the timer was
        // cancelled, so no second SkillCast should appear.
        scheduler.advanceTicks(30)
        cooldownTracker.currentTick = scheduler.currentTick()
        assertThat(eventBus.publishedOfType<SkillCast>()).hasSize(1)
    }

    @Test
    fun `cancelCharge without active charge returns NoActiveCharge`() {
        val result = useCase.cancelCharge(casterId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(ChargedSkillError.NoActiveCharge::class.java)
    }

    @Test
    fun `releaseCharge without active charge returns NoActiveCharge`() {
        val result = useCase.releaseCharge(casterId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(ChargedSkillError.NoActiveCharge::class.java)
    }
}
