package com.astarworks.astera.combat.application.usecase.skill

import com.astarworks.astera.combat.domain.event.CastRejectionReason
import com.astarworks.astera.combat.domain.event.SkillCastRejected
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.combat.testfixtures.FakeCooldownTracker
import com.astarworks.astera.combat.testfixtures.FakeSkillRegistry
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.service.InMemoryResourcePoolService
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
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
            damage = DamageProfile.single(8.0, DamageAttribute.FIRE),
        ),
        phases = com.astarworks.astera.core.domain.skill.phase.PhaseMechanics.Projectile(),
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
        assertThat(eventBus.publishedOfType<SkillCast>()).isEmpty()
        assertThat(eventBus.publishedOfType<SkillCastRejected>().single().reason).isEqualTo(CastRejectionReason.SILENCED)
    }

    // ---- WP2: resource pools, reload, cooldown override ------------------

    private fun advance(ticks: Int) {
        scheduler.advanceTicks(ticks.toLong())
        cooldownTracker.currentTick = scheduler.currentTick()
    }

    private val pistolId = ItemId("test-pistol")
    private val staffId = ItemId("test-staff")

    private fun item(id: ItemId, type: ResourceType, cost: Int, max: Int, reloadable: Boolean = false) = ItemSpec(
        id = id,
        template = ItemTemplate.Vanilla("STICK", null),
        displayName = LocalizedText(mapOf(LocaleCode.EN to id.value)),
        lore = LocalizedLore.EMPTY,
        rarity = Rarity.COMMON,
        levelRequirement = 1,
        attributes = ItemAttributes(),
        equipSlot = EquipSlot.MAINHAND,
        durability = DurabilityPolicy(null, false),
        dropPolicy = DropPolicy(dropOnDeath = true, keepOnDeath = false),
        enchantable = true,
        glint = null,
        skillBindings = emptyList(),
        pdcTags = emptyMap(),
        resourceType = type,
        resourceCostPerUse = cost,
        resourceMaxPool = max,
        resourceReloadable = reloadable,
    )

    private fun withResources(): Pair<CastSkillUseCase, InMemoryResourcePoolService> {
        val itemRegistry = ItemContents()
        itemRegistry.register(pistolId, item(pistolId, ResourceType.Ammo, cost = 1, max = 3, reloadable = true))
        itemRegistry.register(staffId, item(staffId, ResourceType.Mana, cost = 40, max = 100))
        val pool = InMemoryResourcePoolService(scheduler, eventBus, itemRegistry)
        pool.initialiseFor(casterId)
        val uc = CastSkillUseCase(
            skillRegistry = skillRegistry,
            statusContainer = statusContainer,
            eventBus = eventBus,
            cooldownTracker = cooldownTracker,
            scheduler = scheduler,
            resourcePool = pool,
            items = itemRegistry,
        )
        return uc to pool
    }

    @Test
    fun `item cooldown override wins over the skill cooldown`() {
        skillRegistry.register(spec) // cooldown_ticks = 40

        useCase.execute(casterId, skillId, cooldownOverrideTicks = 6)
        advance(5)
        val early = useCase.execute(casterId, skillId, cooldownOverrideTicks = 6)
        assertThat((early as Result.Failure).error).isInstanceOf(CastSkillError.OnCooldown::class.java)

        advance(1) // 6 ticks since the first cast, 34 before the skill's own cooldown
        val again = useCase.execute(casterId, skillId, cooldownOverrideTicks = 6)
        assertThat(again).isInstanceOf(Result.Success::class.java)
        assertThat(eventBus.publishedOfType<SkillCast>()).hasSize(2)
    }

    @Test
    fun `item cost per use is spent from the item's resource and an empty pool rejects the cast`() {
        val (uc, pool) = withResources()
        skillRegistry.register(spec.copy(cooldownTicks = 0))

        repeat(3) { assertThat(uc.execute(casterId, skillId, sourceItemId = pistolId)).isInstanceOf(Result.Success::class.java) }
        assertThat(pool.currentPool(casterId)!!.ammo).isEqualTo(0)

        val result = uc.execute(casterId, skillId, sourceItemId = pistolId)
        val error = (result as Result.Failure).error as CastSkillError.InsufficientResource
        assertThat(error.type).isEqualTo(ResourceType.Ammo)
        assertThat(error.have).isEqualTo(0)
        assertThat(eventBus.publishedOfType<SkillCast>()).hasSize(3)
        assertThat(eventBus.publishedOfType<SkillCastRejected>().single().reason)
            .isEqualTo(CastRejectionReason.INSUFFICIENT_AMMO)
    }

    @Test
    fun `skill resource cost overrides the item default and a failed spend leaves the cooldown untouched`() {
        val (uc, pool) = withResources()
        skillRegistry.register(spec.copy(resourceCost = 70))

        val ok = uc.execute(casterId, skillId, sourceItemId = staffId)
        assertThat(ok).isInstanceOf(Result.Success::class.java)
        assertThat(pool.currentPool(casterId)!!.mana).isEqualTo(30)

        cooldownTracker.reset(casterId, CooldownKey.Skill(skillId.value))
        val short = uc.execute(casterId, skillId, sourceItemId = staffId)
        assertThat(((short as Result.Failure).error as CastSkillError.InsufficientResource).have).isEqualTo(30)
        assertThat(cooldownTracker.isCoolingDown(casterId, CooldownKey.Skill(skillId.value))).isFalse()
        assertThat(eventBus.publishedOfType<SkillCastRejected>().single().reason)
            .isEqualTo(CastRejectionReason.INSUFFICIENT_MANA)
    }

    @Test
    fun `explicit zero cost is free`() {
        val (uc, pool) = withResources()
        skillRegistry.register(spec.copy(resourceCost = 0))

        uc.execute(casterId, skillId, sourceItemId = staffId)

        assertThat(pool.currentPool(casterId)!!.mana).isEqualTo(100)
    }

    @Test
    fun `reload skill refills ammo after reload_ticks and blocks ammo casts meanwhile`() {
        val (uc, pool) = withResources()
        val shot = spec.copy(cooldownTicks = 0)
        val reloadId = SkillId("reload")
        skillRegistry.register(shot)
        skillRegistry.register(spec.copy(id = reloadId, cooldownTicks = 0, resourceCost = 0, reloadTicks = 30))

        repeat(3) { uc.execute(casterId, skillId, sourceItemId = pistolId) }
        assertThat(pool.currentPool(casterId)!!.ammo).isEqualTo(0)

        assertThat(uc.execute(casterId, reloadId, sourceItemId = pistolId)).isInstanceOf(Result.Success::class.java)
        advance(29)
        val midReload = uc.execute(casterId, skillId, sourceItemId = pistolId)
        assertThat((midReload as Result.Failure).error).isInstanceOf(CastSkillError.Reloading::class.java)
        assertThat(pool.currentPool(casterId)!!.ammo).isEqualTo(0)

        advance(1)
        assertThat(pool.currentPool(casterId)!!.ammo).isEqualTo(3)
        assertThat(uc.execute(casterId, skillId, sourceItemId = pistolId)).isInstanceOf(Result.Success::class.java)
        assertThat(pool.currentPool(casterId)!!.ammo).isEqualTo(2)
    }

    @Test
    fun `reload with a full magazine or from a non-reloadable item is rejected`() {
        val (uc, _) = withResources()
        val reloadId = SkillId("reload")
        skillRegistry.register(spec.copy(id = reloadId, cooldownTicks = 0, resourceCost = 0, reloadTicks = 30))

        val full = uc.execute(casterId, reloadId, sourceItemId = pistolId)
        assertThat((full as Result.Failure).error).isInstanceOf(CastSkillError.MagazineFull::class.java)

        val staff = uc.execute(casterId, reloadId, sourceItemId = staffId)
        assertThat((staff as Result.Failure).error).isInstanceOf(CastSkillError.NotReloadable::class.java)
    }

    @Test
    fun `pose lookup stamps world and facing on SkillCast and fills a missing position`() {
        skillRegistry.register(spec)
        val poses = com.astarworks.astera.testfixtures.FakePlayerPoseLookup().apply {
            place(
                casterId,
                com.astarworks.astera.core.domain.geometry.Vec3(3.0, 70.0, 4.0),
                com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 1.0),
            )
        }
        val withPose = CastSkillUseCase(
            skillRegistry = skillRegistry,
            statusContainer = statusContainer,
            eventBus = eventBus,
            cooldownTracker = cooldownTracker,
            scheduler = scheduler,
            poseLookup = poses,
        )

        withPose.execute(casterId, skillId) // at defaults to Vec3.ZERO

        val cast = eventBus.publishedOfType<SkillCast>().single()
        assertThat(cast.pose).isEqualTo(poses.poseOf(casterId))
        assertThat(cast.at).isEqualTo(com.astarworks.astera.core.domain.geometry.Vec3(3.0, 70.0, 4.0))
    }
}
