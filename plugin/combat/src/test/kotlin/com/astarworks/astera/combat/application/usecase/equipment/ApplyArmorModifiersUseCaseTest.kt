package com.astarworks.astera.combat.application.usecase.equipment

import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorReduction
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.armor.ArmorSpec
import com.astarworks.astera.combat.testfixtures.FakeArmorRegistry
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [ApplyArmorModifiersUseCase] (Wave 5e, ADR-0058 §armor).
 *
 * Each test exercises the equip-time reconciliation contract:
 *  - Modifiers are added under sourceId `"armor:<slot>:<armorSpecId>"`.
 *  - Re-equipping a slot replaces the prior modifiers (no double-stack).
 *  - Unequip via the [ApplyArmorModifiersUseCase.removeArmorModifiersForSlot]
 *    wrapper strips every modifier for that slot.
 *  - Set-bonus stacking adds modifiers per attribute per slot.
 *  - Unknown ArmorSpec ids surface as
 *    [ArmorModifierError.ArmorSpecNotFound] and abort the reconciliation
 *    before touching the container.
 */
class ApplyArmorModifiersUseCaseTest {

    private val playerId = PlayerId(UUID.randomUUID())

    private fun piece(
        id: String,
        slot: ArmorSlot,
        defaultReduction: Double = 0.0,
        perAttribute: Map<DamageAttribute, Double> = emptyMap(),
    ): ArmorSpec = ArmorSpec(
        id = ArmorId(id),
        displayNameKey = MessageKey("astera.armor.test-$id.name"),
        slot = slot,
        reduction = ArmorReduction(default = defaultReduction, perAttribute = perAttribute),
        setKey = null,
    )

    private fun scaffold(vararg pieces: ArmorSpec): Trio {
        val registry = FakeArmorRegistry()
        pieces.forEach(registry::register)
        val store = InMemoryPlayerAttributeContainer()
        val useCase = ApplyArmorModifiersUseCase(store, registry)
        return Trio(registry, store, useCase)
    }

    private data class Trio(
        val registry: FakeArmorRegistry,
        val store: InMemoryPlayerAttributeContainer,
        val useCase: ApplyArmorModifiersUseCase,
    )

    @Test
    fun `equip chest with fire 0_3 writes RESIST_FIRE ADD modifier`() {
        val fireChest = piece(
            "fire-chest",
            ArmorSlot.CHESTPLATE,
            defaultReduction = 0.0,
            perAttribute = mapOf(DamageAttribute.FIRE to 0.3),
        )
        val s = scaffold(fireChest)

        val result = s.useCase.applyEquipped(
            playerId,
            ArmorBundle(playerId, mapOf(ArmorSlot.CHESTPLATE to fireChest)),
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val mods = s.store.containerFor(playerId).modifiers()
        assertThat(mods).containsExactly(
            AttributeModifier(
                key = Attributes.RESIST_FIRE,
                operation = AttributeModifier.Operation.ADD,
                value = 0.3,
                sourceId = "armor:CHESTPLATE:fire-chest",
            ),
        )
    }

    @Test
    fun `re-equip same slot with different armor replaces prior modifiers`() {
        val oldChest = piece(
            "old-chest",
            ArmorSlot.CHESTPLATE,
            perAttribute = mapOf(DamageAttribute.FIRE to 0.4),
        )
        val newChest = piece(
            "new-chest",
            ArmorSlot.CHESTPLATE,
            perAttribute = mapOf(DamageAttribute.WATER to 0.5),
        )
        val s = scaffold(oldChest, newChest)

        s.useCase.applyEquipped(
            playerId,
            ArmorBundle(playerId, mapOf(ArmorSlot.CHESTPLATE to oldChest)),
        )
        s.useCase.applyEquipped(
            playerId,
            ArmorBundle(playerId, mapOf(ArmorSlot.CHESTPLATE to newChest)),
        )

        val mods = s.store.containerFor(playerId).modifiers()
        assertThat(mods).containsExactly(
            AttributeModifier(
                key = Attributes.RESIST_WATER,
                operation = AttributeModifier.Operation.ADD,
                value = 0.5,
                sourceId = "armor:CHESTPLATE:new-chest",
            ),
        )
        // Old chest's RESIST_FIRE modifier must not survive.
        val container = s.store.containerFor(playerId)
        assertThat(container.effectiveNumber(Attributes.RESIST_FIRE)).isEqualTo(0.0)
    }

    @Test
    fun `multi-slot equip writes one modifier per slot per nonzero attribute`() {
        val helmet = piece(
            "h",
            ArmorSlot.HELMET,
            perAttribute = mapOf(DamageAttribute.PHYSICAL to 0.1),
        )
        val chest = piece(
            "c",
            ArmorSlot.CHESTPLATE,
            perAttribute = mapOf(DamageAttribute.PHYSICAL to 0.2),
        )
        val s = scaffold(helmet, chest)

        s.useCase.applyEquipped(
            playerId,
            ArmorBundle(
                playerId,
                mapOf(ArmorSlot.HELMET to helmet, ArmorSlot.CHESTPLATE to chest),
            ),
        )

        val container = s.store.containerFor(playerId)
        // Two ADD modifiers stack → 0.3 effective.
        assertThat(container.effectiveNumber(Attributes.RESIST_PHYSICAL))
            .isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-9))
        val sources = container.modifiers().map { it.sourceId }.toSet()
        assertThat(sources).containsExactlyInAnyOrder(
            "armor:HELMET:h",
            "armor:CHESTPLATE:c",
        )
    }

    @Test
    fun `default reduction materialises one modifier per attribute`() {
        val flat = piece("flat", ArmorSlot.HELMET, defaultReduction = 0.1)
        val s = scaffold(flat)

        s.useCase.applyEquipped(
            playerId,
            ArmorBundle(playerId, mapOf(ArmorSlot.HELMET to flat)),
        )

        val mods = s.store.containerFor(playerId).modifiers()
        // One modifier per DamageAttribute (six entries).
        assertThat(mods).hasSize(DamageAttribute.entries.size)
        assertThat(mods.map { it.key }.toSet()).containsExactlyInAnyOrder(
            Attributes.RESIST_PHYSICAL,
            Attributes.RESIST_FIRE,
            Attributes.RESIST_ELECTRIC,
            Attributes.RESIST_WIND,
            Attributes.RESIST_WATER,
            Attributes.RESIST_EARTH,
        )
        assertThat(mods).allMatch { it.value == 0.1 }
        assertThat(mods).allMatch { it.sourceId == "armor:HELMET:flat" }
    }

    @Test
    fun `removeArmorModifiersForSlot strips every modifier for that slot`() {
        val chest = piece(
            "c",
            ArmorSlot.CHESTPLATE,
            perAttribute = mapOf(
                DamageAttribute.FIRE to 0.3,
                DamageAttribute.WATER to 0.2,
            ),
        )
        val helmet = piece(
            "h",
            ArmorSlot.HELMET,
            perAttribute = mapOf(DamageAttribute.FIRE to 0.1),
        )
        val s = scaffold(chest, helmet)

        s.useCase.applyEquipped(
            playerId,
            ArmorBundle(
                playerId,
                mapOf(ArmorSlot.CHESTPLATE to chest, ArmorSlot.HELMET to helmet),
            ),
        )
        val container = s.store.containerFor(playerId)
        // Sanity: starting state has both slots represented.
        assertThat(container.modifiers().map { it.sourceId }.toSet())
            .containsExactlyInAnyOrder(
                "armor:CHESTPLATE:c",
                "armor:HELMET:h",
            )

        s.useCase.removeArmorModifiersForSlot(container, ArmorSlot.CHESTPLATE)

        // Only helmet survives; chest's FIRE + WATER modifiers stripped.
        val remaining = container.modifiers()
        assertThat(remaining).hasSize(1)
        assertThat(remaining[0].sourceId).isEqualTo("armor:HELMET:h")
        assertThat(container.effectiveNumber(Attributes.RESIST_FIRE)).isEqualTo(0.1)
        assertThat(container.effectiveNumber(Attributes.RESIST_WATER)).isEqualTo(0.0)
    }

    @Test
    fun `ArmorSpecNotFound when registry lacks the equipped id`() {
        // Build a use case whose registry does NOT know the spec we'll equip.
        val registry = FakeArmorRegistry()
        val store = InMemoryPlayerAttributeContainer()
        val useCase = ApplyArmorModifiersUseCase(store, registry)

        val ghost = piece(
            "ghost",
            ArmorSlot.CHESTPLATE,
            perAttribute = mapOf(DamageAttribute.FIRE to 0.3),
        )

        val result = useCase.applyEquipped(
            playerId,
            ArmorBundle(playerId, mapOf(ArmorSlot.CHESTPLATE to ghost)),
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err).isInstanceOf(ArmorModifierError.ArmorSpecNotFound::class.java)
        err as ArmorModifierError.ArmorSpecNotFound
        assertThat(err.slot).isEqualTo(ArmorSlot.CHESTPLATE)
        assertThat(err.id).isEqualTo("ghost")
        // Container untouched on failure.
        assertThat(store.containerFor(playerId).modifiers()).isEmpty()
    }

    @Test
    fun `re-equip with empty bundle clears every armor modifier`() {
        val chest = piece(
            "c",
            ArmorSlot.CHESTPLATE,
            perAttribute = mapOf(DamageAttribute.FIRE to 0.3),
        )
        val s = scaffold(chest)

        s.useCase.applyEquipped(
            playerId,
            ArmorBundle(playerId, mapOf(ArmorSlot.CHESTPLATE to chest)),
        )
        s.useCase.applyEquipped(playerId, ArmorBundle.empty(playerId))

        val container = s.store.containerFor(playerId)
        assertThat(container.modifiers()).isEmpty()
        assertThat(container.effectiveNumber(Attributes.RESIST_FIRE)).isEqualTo(0.0)
    }
}
