package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ResourceError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerActiveWeaponChanged
import com.astarworks.astera.core.domain.event.ResourceReloaded
import com.astarworks.astera.core.domain.event.ResourceSpent
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceCap
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5k (ADR-0080) — [InMemoryResourcePoolService] contract.
 */
class ResourcePoolServiceTest {

    private lateinit var scheduler: FakeScheduler
    private lateinit var eventBus: InMemoryDomainEventBus
    private lateinit var itemRegistry: ItemContents
    private lateinit var service: InMemoryResourcePoolService
    private val pid: PlayerId = PlayerId(UUID.randomUUID())

    private val pistolId = ItemId("test-pistol")
    private val swordId = ItemId("test-sword")
    private val wandId = ItemId("test-wand")
    private val potionId = ItemId("test-potion")

    @BeforeEach
    fun setUp() {
        scheduler = FakeScheduler()
        eventBus = InMemoryDomainEventBus()
        itemRegistry = ItemContents()
        seedItems()
        service = InMemoryResourcePoolService(scheduler, eventBus, itemRegistry)
    }

    private fun seedItems() {
        itemRegistry.register(pistolId, makeItem(pistolId, ResourceType.Ammo, max = 12, reloadable = true))
        itemRegistry.register(swordId, makeItem(swordId, ResourceType.Stamina, max = 50, reloadable = false))
        itemRegistry.register(wandId, makeItem(wandId, ResourceType.Mana, max = 80, reloadable = false))
        itemRegistry.register(potionId, makeItem(potionId, type = null))
    }

    private fun makeItem(
        id: ItemId,
        type: ResourceType?,
        max: Int = -1,
        reloadable: Boolean = false,
    ): ItemSpec = ItemSpec(
        id = id,
        template = ItemTemplate.Vanilla("STICK", null),
        displayName = LocalizedText.EMPTY,
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
        modelSlug = null,
        resourceType = type,
        resourceCostPerUse = if (type != null) 1 else 0,
        resourceMaxPool = max,
        resourceRegenPerSecond = -1.0,
        resourceReloadable = reloadable,
    )

    @Test
    fun `initialiseFor seeds defaults`() {
        val pool = service.initialiseFor(pid)

        assertThat(pool.ammo).isEqualTo(ResourceCap.DEFAULTS[ResourceType.Ammo]!!.maxPool)
        assertThat(pool.stamina).isEqualTo(ResourceCap.DEFAULTS[ResourceType.Stamina]!!.maxPool)
        assertThat(pool.mana).isEqualTo(ResourceCap.DEFAULTS[ResourceType.Mana]!!.maxPool)
        assertThat(pool.activeWeapon).isNull()
    }

    @Test
    fun `initialiseFor is idempotent`() {
        service.initialiseFor(pid)
        service.spendResource(pid, ResourceType.Mana, 50)
        val again = service.initialiseFor(pid)

        // The second call must NOT reset (idempotent).
        assertThat(again.mana).isEqualTo(ResourceCap.DEFAULTS[ResourceType.Mana]!!.maxPool - 50)
    }

    @Test
    fun `setActiveWeapon publishes change event`() {
        val captured = mutableListOf<PlayerActiveWeaponChanged>()
        eventBus.subscribe(PlayerActiveWeaponChanged::class) { captured += it }

        service.setActiveWeapon(pid, pistolId)

        assertThat(service.currentPool(pid)?.activeWeapon).isEqualTo(pistolId)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].previous).isNull()
        assertThat(captured[0].current).isEqualTo(pistolId)
    }

    @Test
    fun `setActiveWeapon to same value is a no-op`() {
        val captured = mutableListOf<PlayerActiveWeaponChanged>()
        service.setActiveWeapon(pid, pistolId)
        eventBus.subscribe(PlayerActiveWeaponChanged::class) { captured += it }

        service.setActiveWeapon(pid, pistolId)

        assertThat(captured).isEmpty()
    }

    @Test
    fun `spendResource subtracts and emits event`() {
        val captured = mutableListOf<ResourceSpent>()
        eventBus.subscribe(ResourceSpent::class) { captured += it }
        service.initialiseFor(pid)
        val before = service.currentPool(pid)!!.mana

        val outcome = service.spendResource(pid, ResourceType.Mana, 20)

        assertThat(outcome).isEqualTo(Result.success(before - 20))
        assertThat(service.currentPool(pid)!!.mana).isEqualTo(before - 20)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].type).isEqualTo(ResourceType.Mana)
        assertThat(captured[0].amount).isEqualTo(20)
        assertThat(captured[0].remaining).isEqualTo(before - 20)
    }

    @Test
    fun `spendResource fails Insufficient when below amount`() {
        service.initialiseFor(pid)

        val outcome = service.spendResource(pid, ResourceType.Mana, 9999)

        assertThat(outcome.errorOrNull()).isInstanceOf(ResourceError.Insufficient::class.java)
    }

    @Test
    fun `spendResource fails UninitialisedPlayer before init`() {
        val outcome = service.spendResource(pid, ResourceType.Mana, 1)
        assertThat(outcome.errorOrNull()).isEqualTo(ResourceError.UninitialisedPlayer)
    }

    @Test
    fun `spendResource rejects negative amount`() {
        service.initialiseFor(pid)
        val outcome = service.spendResource(pid, ResourceType.Mana, -1)
        assertThat(outcome.errorOrNull()).isInstanceOf(ResourceError.InvalidValue::class.java)
    }

    @Test
    fun `reload refills Ammo to weapon-spec cap`() {
        val captured = mutableListOf<ResourceReloaded>()
        eventBus.subscribe(ResourceReloaded::class) { captured += it }
        service.initialiseFor(pid)
        service.spendResource(pid, ResourceType.Ammo, ResourceCap.DEFAULTS[ResourceType.Ammo]!!.maxPool)

        val outcome = service.reload(pid, pistolId)

        // Weapon override cap = 12.
        assertThat(outcome).isEqualTo(Result.success(12))
        assertThat(service.currentPool(pid)!!.ammo).isEqualTo(12)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].ammoAfter).isEqualTo(12)
    }

    @Test
    fun `reload fails NotReloadable for Stamina weapon`() {
        service.initialiseFor(pid)
        val outcome = service.reload(pid, swordId)
        assertThat(outcome.errorOrNull()).isEqualTo(ResourceError.NotReloadable(swordId))
    }

    @Test
    fun `reload fails NotReloadable for non-resource item`() {
        service.initialiseFor(pid)
        val outcome = service.reload(pid, potionId)
        assertThat(outcome.errorOrNull()).isEqualTo(ResourceError.NotReloadable(potionId))
    }

    @Test
    fun `reload fails UnknownWeapon for unknown id`() {
        service.initialiseFor(pid)
        val outcome = service.reload(pid, ItemId("ghost-item"))
        assertThat(outcome.errorOrNull()).isInstanceOf(ResourceError.UnknownWeapon::class.java)
    }

    @Test
    fun `applyRegen clamps at type cap`() {
        service.initialiseFor(pid)
        val outcome = service.applyRegen(pid, ResourceType.Mana, 9999)
        assertThat(outcome).isEqualTo(Result.success(ResourceCap.DEFAULTS[ResourceType.Mana]!!.maxPool))
    }

    @Test
    fun `applyRegen UninitialisedPlayer when no init`() {
        val outcome = service.applyRegen(pid, ResourceType.Mana, 5)
        assertThat(outcome.errorOrNull()).isEqualTo(ResourceError.UninitialisedPlayer)
    }

    @Test
    fun `capFor returns cap defaults without an active weapon`() {
        service.initialiseFor(pid)
        assertThat(service.capFor(pid, ResourceType.Ammo)).isEqualTo(ResourceCap.DEFAULTS[ResourceType.Ammo])
        assertThat(service.capFor(pid, ResourceType.Stamina)).isEqualTo(ResourceCap.DEFAULTS[ResourceType.Stamina])
        assertThat(service.capFor(pid, ResourceType.Mana)).isEqualTo(ResourceCap.DEFAULTS[ResourceType.Mana])
    }

    @Test
    fun `capFor uses the active weapon override for its own type only`() {
        service.initialiseFor(pid)
        service.setActiveWeapon(pid, pistolId)
        assertThat(service.capFor(pid, ResourceType.Ammo).maxPool).isEqualTo(12)
        assertThat(service.capFor(pid, ResourceType.Mana)).isEqualTo(ResourceCap.DEFAULTS[ResourceType.Mana])
    }

    @Test
    fun `equipping a smaller-capacity weapon clamps the carried counter`() {
        service.initialiseFor(pid)
        assertThat(service.currentPool(pid)!!.ammo).isEqualTo(30)
        service.setActiveWeapon(pid, pistolId)
        assertThat(service.currentPool(pid)!!.ammo).isEqualTo(12)
    }

    @Test
    fun `applyRegen clamps at the active weapon capacity`() {
        service.initialiseFor(pid)
        service.setActiveWeapon(pid, wandId)
        service.spendResource(pid, ResourceType.Mana, 70)
        val outcome = service.applyRegen(pid, ResourceType.Mana, 999)
        assertThat(outcome.getOrNull()).isEqualTo(80)
    }

    @Test
    fun `currentPool returns null before init`() {
        assertThat(service.currentPool(pid)).isNull()
    }
}
