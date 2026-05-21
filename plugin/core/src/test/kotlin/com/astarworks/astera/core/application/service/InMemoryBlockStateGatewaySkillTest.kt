package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.block.BlockDamageOutcome
import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.block.BlockDrop
import com.astarworks.astera.core.domain.block.CustomBlockSpec
import com.astarworks.astera.core.domain.block.IneffectiveReason
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.event.BlockBrokenBySkill
import com.astarworks.astera.core.domain.event.BlockDamagedBySkill
import com.astarworks.astera.core.domain.event.CustomBlockPlaced
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockDurabilityRule
import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

/**
 * Pins the Wave 5x skill→block damage path through
 * [InMemoryBlockStateGateway] (ADR-0090):
 *  - `applyDamageFromSkill` resolves per-block tool effectiveness
 *  - `applyDamageFromSkill` falls through to the global durability rule
 *    when the spec has no per-tool override
 *  - `placeCustomBlock` seeds the tracker at the spec's baseDurability
 *  - Domain events fire on each path
 */
internal class InMemoryBlockStateGatewaySkillTest {

    private val world = WorldId("test-world")
    private val pos = Vec3(10.0, 64.0, 20.0)
    private val attacker = PlayerId(UUID.randomUUID())
    private val skillId = SkillId("pickaxe-swing")

    private val hardenedSoil = CustomBlockSpec(
        id = BlockType("astera:hardened_soil"),
        displayName = LocalizedText(mapOf(LocaleCode.EN to "Hardened Soil")),
        baseBukkitMaterial = "DIRT",
        material = BlockMaterial.SOIL,
        baseDurability = 5000,
        toolEffectiveness = mapOf(
            ToolType.WOODEN_PICKAXE to 1,
            ToolType.STONE_PICKAXE to 10,
            ToolType.IRON_PICKAXE to 50,
            ToolType.DIAMOND_PICKAXE to 200,
        ),
        drops = listOf(
            BlockDrop(
                itemId = ItemId("hardened-soil-block"),
                requiresTool = ToolType.IRON_PICKAXE,
            ),
        ),
        placedFrom = ItemId("hardened-soil-block"),
    )

    private val granite = CustomBlockSpec(
        id = BlockType("astera:granite_block"),
        displayName = LocalizedText(mapOf(LocaleCode.EN to "Granite")),
        baseBukkitMaterial = "STONE",
        material = BlockMaterial.STONE,
        baseDurability = 100,
    )

    private val immuneCube = CustomBlockSpec(
        id = BlockType("astera:immune_cube"),
        displayName = LocalizedText(mapOf(LocaleCode.EN to "Immune Cube")),
        baseBukkitMaterial = "OBSIDIAN",
        material = BlockMaterial.STONE,
        baseDurability = 100,
        immuneTo = setOf(BlockMaterial.STONE),
    )

    private val registry = InMemoryCustomBlockRegistry(
        hardenedSoil,
        granite,
        immuneCube,
    )

    private val eventBus = InMemoryDomainEventBus()
    private val capturedEvents = mutableListOf<DomainEvent>()
    private lateinit var gateway: InMemoryBlockStateGateway

    @BeforeEach
    fun setUp() {
        capturedEvents.clear()
        eventBus.subscribe(BlockDamagedBySkill::class) { capturedEvents += it }
        eventBus.subscribe(BlockBrokenBySkill::class) { capturedEvents += it }
        eventBus.subscribe(CustomBlockPlaced::class) { capturedEvents += it }
        gateway = InMemoryBlockStateGateway(
            eventBus = eventBus,
            customBlockRegistry = registry,
            durabilityRule = BlockDurabilityRule.defaultMatrix(),
            random = Random(seed = 42L),
            initialTick = { 100L },
        )
    }

    // -------------------------------------------------------------------------
    // placeCustomBlock
    // -------------------------------------------------------------------------

    @Test
    fun `placeCustomBlock seeds tracker at baseDurability and publishes CustomBlockPlaced`() {
        val result = gateway.placeCustomBlock(world, pos, hardenedSoil, attacker)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val state = (result as Result.Success).value
        assertThat(state.blockType).isEqualTo(hardenedSoil.id)
        assertThat(state.durability).isEqualTo(5000)
        assertThat(state.maxHp).isEqualTo(5000)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(5000)

        val placed = capturedEvents.filterIsInstance<CustomBlockPlaced>()
        assertThat(placed).hasSize(1)
        assertThat(placed[0].blockType).isEqualTo(hardenedSoil.id)
        assertThat(placed[0].placedBy).isEqualTo(attacker)
    }

    // -------------------------------------------------------------------------
    // applyDamageFromSkill — per-block override path
    // -------------------------------------------------------------------------

    @Test
    fun `iron pickaxe on hardened_soil deals override damage`() {
        gateway.placeCustomBlock(world, pos, hardenedSoil, attacker)

        val outcome = gateway.applyDamageFromSkill(
            world, pos, skillId, attacker,
            BlockDamageProfile(baseAmount = 100, effectiveTool = ToolType.IRON_PICKAXE),
        )

        val damaged = (outcome as Result.Success).value as BlockDamageOutcome.Damaged
        // override cell = 50, baseAmount = 100, DEFAULT_BASE = 100 → 50 damage
        assertThat(damaged.damageDealt).isEqualTo(50)
        assertThat(damaged.newState.durability).isEqualTo(4950)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(4950)

        val events = capturedEvents.filterIsInstance<BlockDamagedBySkill>()
        assertThat(events).hasSize(1)
        assertThat(events[0].newDurability).isEqualTo(4950)
        assertThat(events[0].amount).isEqualTo(50)
    }

    @Test
    fun `wooden pickaxe on hardened_soil is ineffective due to ZeroDamageCell or tiny cell`() {
        gateway.placeCustomBlock(world, pos, hardenedSoil, attacker)

        val outcome = gateway.applyDamageFromSkill(
            world, pos, skillId, attacker,
            BlockDamageProfile(baseAmount = 50, effectiveTool = ToolType.WOODEN_PICKAXE),
        )

        // wooden pickaxe override = 1; baseAmount 50 → 0.5 → 0 (Int truncation)
        // resolves to ZeroDamageCell ineffective
        val ineffective = (outcome as Result.Success).value as BlockDamageOutcome.Ineffective
        assertThat(ineffective.reason).isEqualTo(IneffectiveReason.ZeroDamageCell)
        // No event published for ineffective
        assertThat(capturedEvents.filterIsInstance<BlockDamagedBySkill>()).isEmpty()
    }

    // -------------------------------------------------------------------------
    // applyDamageFromSkill — fallback to BlockDurabilityRule
    // -------------------------------------------------------------------------

    @Test
    fun `pickaxe on granite (no override) uses base rule`() {
        gateway.placeCustomBlock(world, pos, granite, attacker)

        val outcome = gateway.applyDamageFromSkill(
            world, pos, skillId, attacker,
            BlockDamageProfile(baseAmount = 100, effectiveTool = ToolType.IRON_PICKAXE),
        )

        // Default rule: PICKAXE × IRON × STONE = 100; baseAmount 100 → 100 dmg
        // 100 - 100 = 0 → Broken outcome
        val broken = (outcome as Result.Success).value as BlockDamageOutcome.Broken
        assertThat(broken.change.position).isEqualTo(pos)
        assertThat(broken.change.byPlayer).isEqualTo(attacker)
    }

    @Test
    fun `shovel on granite hits 0-damage cell and returns ZeroDamageCell`() {
        gateway.placeCustomBlock(world, pos, granite, attacker)

        val outcome = gateway.applyDamageFromSkill(
            world, pos, skillId, attacker,
            BlockDamageProfile(baseAmount = 100, effectiveTool = ToolType.IRON_SHOVEL),
        )

        val ineffective = (outcome as Result.Success).value as BlockDamageOutcome.Ineffective
        assertThat(ineffective.reason).isEqualTo(IneffectiveReason.ZeroDamageCell)
    }

    @Test
    fun `bare-hand on STONE returns WrongTool reason`() {
        gateway.placeCustomBlock(world, pos, granite, attacker)

        val outcome = gateway.applyDamageFromSkill(
            world, pos, skillId, attacker,
            BlockDamageProfile(baseAmount = 100, effectiveTool = ToolType.NONE),
        )

        val ineffective = (outcome as Result.Success).value as BlockDamageOutcome.Ineffective
        assertThat(ineffective.reason).isEqualTo(IneffectiveReason.WrongTool)
    }

    // -------------------------------------------------------------------------
    // immuneTo
    // -------------------------------------------------------------------------

    @Test
    fun `immuneTo material short-circuits with ImmuneByMaterial`() {
        gateway.placeCustomBlock(world, pos, immuneCube, attacker)

        val outcome = gateway.applyDamageFromSkill(
            world, pos, skillId, attacker,
            BlockDamageProfile(baseAmount = 100, effectiveTool = ToolType.IRON_PICKAXE),
        )

        val ineffective = (outcome as Result.Success).value as BlockDamageOutcome.Ineffective
        assertThat(ineffective.reason).isEqualTo(IneffectiveReason.ImmuneByMaterial)
        // No damage applied
        assertThat(gateway.getDurability(world, pos)).isEqualTo(100)
    }

    // -------------------------------------------------------------------------
    // Drops + tool gating
    // -------------------------------------------------------------------------

    @Test
    fun `breaking hardened_soil with iron+ pickaxe drops the item`() {
        gateway.placeCustomBlock(world, pos, hardenedSoil, attacker)
        // 5000 / 50 per hit = 100 hits to break; loop until broken
        var broken: BlockDamageOutcome.Broken? = null
        repeat(120) {
            val outcome = gateway.applyDamageFromSkill(
                world, pos, skillId, attacker,
                BlockDamageProfile(baseAmount = 100, effectiveTool = ToolType.IRON_PICKAXE),
            ).getOrNull()
            if (outcome is BlockDamageOutcome.Broken) {
                broken = outcome
                return@repeat
            }
        }

        val b = broken!!
        assertThat(b.drops).containsExactly(ItemId("hardened-soil-block"))
    }

    @Test
    fun `breaking hardened_soil with wooden pickaxe yields no drops (gate filter)`() {
        // Wooden pickaxe override = 1; baseAmount = 5000 → 50 damage per hit
        gateway.placeCustomBlock(world, pos, hardenedSoil, attacker)
        var broken: BlockDamageOutcome.Broken? = null
        repeat(120) {
            val outcome = gateway.applyDamageFromSkill(
                world, pos, skillId, attacker,
                BlockDamageProfile(baseAmount = 5000, effectiveTool = ToolType.WOODEN_PICKAXE),
            ).getOrNull()
            if (outcome is BlockDamageOutcome.Broken) {
                broken = outcome
                return@repeat
            }
        }
        val b = broken!!
        // requiresTool = IRON_PICKAXE; wooden fails the gate → no drop
        assertThat(b.drops).isEmpty()
    }

    // -------------------------------------------------------------------------
    // Per-block maxHp interaction with restore()
    // -------------------------------------------------------------------------

    @Test
    fun `restore returns hardened_soil to its 5000 maxHp`() {
        gateway.placeCustomBlock(world, pos, hardenedSoil, attacker)
        gateway.applyDamageFromSkill(
            world, pos, skillId, attacker,
            BlockDamageProfile(baseAmount = 100, effectiveTool = ToolType.IRON_PICKAXE),
        )
        assertThat(gateway.getDurability(world, pos)).isLessThan(5000)

        gateway.restore(world, pos)
        // Custom maxHp respected — not capped at 100
        assertThat(gateway.getDurability(world, pos)).isEqualTo(5000)
    }

    // -------------------------------------------------------------------------
    // tiny inline ICustomBlockRegistry stub for the gateway dependency
    // -------------------------------------------------------------------------

    private class InMemoryCustomBlockRegistry(vararg specs: CustomBlockSpec) : ICustomBlockRegistry {
        private val byType = specs.associateBy { it.id }
        override fun find(key: BlockType): CustomBlockSpec? = byType[key]
        override fun all(): Collection<CustomBlockSpec> = byType.values
        override fun resolveByItem(itemId: ItemId): CustomBlockSpec? =
            byType.values.firstOrNull { it.placedFrom == itemId }
        override val categoryName: String = "blocks"
        override fun reload(dir: java.nio.file.Path): ContentLoader.LoadReport =
            ContentLoader.LoadReport(loaded = 0, failed = 0)
    }
}
