package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorReduction
import com.astarworks.astera.combat.domain.armor.ArmorSet
import com.astarworks.astera.combat.domain.armor.ArmorSetKey
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.armor.ArmorSpec
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.MitigationReason
import com.astarworks.astera.combat.testfixtures.FakeArmorRegistry
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.domain.weapon.DamageProfile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Behavior tests for [ArmorReductionModifier] (Wave 5a, worker α).
 *
 * Covers: single-piece reduction, multi-piece additive stacking, set bonus
 * activation at 2-piece and 4-piece tiers, the 80% global cap (invuln
 * prevention), proportional mitigation attribution, and no-op cases
 * (null bundle, empty bundle, zero base damage).
 */
class ArmorReductionModifierTest {

    private val targetId = PlayerId(UUID.randomUUID())

    private val nonRandom: IDamageRandomizer = object : IDamageRandomizer {
        override fun nextDouble(): Double = 0.0
    }

    private fun piece(
        id: String,
        slot: ArmorSlot,
        reduction: Double,
        setKey: String? = null,
        perAttribute: Map<DamageAttribute, Double> = emptyMap(),
    ): ArmorSpec = ArmorSpec(
        id = ArmorId(id),
        displayNameKey = MessageKey("astera.armor.test-$id.name"),
        slot = slot,
        reduction = ArmorReduction(default = reduction, perAttribute = perAttribute),
        setKey = setKey?.let(::ArmorSetKey),
    )

    private fun bundle(vararg pieces: ArmorSpec): ArmorBundle = ArmorBundle(
        playerId = targetId,
        equipped = pieces.associateBy { it.slot },
    )

    private fun working(base: Double): WorkingDamage =
        WorkingDamage(currentAmount = base, attribute = DamageAttribute.PHYSICAL)

    private fun attempt(base: Double): DamageAttempt = DamageAttempt(
        targetId = targetId,
        baseProfile = DamageProfile(base = base, attribute = DamageAttribute.PHYSICAL),
        source = DamageSource.Environment,
    )

    @Test
    fun `null bundle is a no-op`() {
        val modifier = ArmorReductionModifier(FakeArmorRegistry())
        val out = modifier.apply(working(10.0), attempt(10.0), armor = null, randomizer = nonRandom)

        assertThat(out.currentAmount).isEqualTo(10.0)
        assertThat(out.mitigations).isEmpty()
    }

    @Test
    fun `empty bundle is a no-op`() {
        val modifier = ArmorReductionModifier(FakeArmorRegistry())
        val out = modifier.apply(
            working(10.0),
            attempt(10.0),
            armor = ArmorBundle.empty(targetId),
            randomizer = nonRandom,
        )

        assertThat(out.currentAmount).isEqualTo(10.0)
        assertThat(out.mitigations).isEmpty()
    }

    @Test
    fun `helmet with 50 percent reduction halves damage`() {
        val modifier = ArmorReductionModifier(FakeArmorRegistry())
        val out = modifier.apply(
            working(10.0),
            attempt(10.0),
            armor = bundle(piece("h50", ArmorSlot.HELMET, 0.5)),
            randomizer = nonRandom,
        )

        assertThat(out.currentAmount).isEqualTo(5.0)
        assertThat(out.mitigations).hasSize(1)
        assertThat(out.mitigations[0].amountReduced).isEqualTo(5.0)
        val reason = out.mitigations[0].reason
        assertThat(reason).isInstanceOf(MitigationReason.Armor::class.java)
        reason as MitigationReason.Armor
        assertThat(reason.slot).isEqualTo(ArmorSlot.HELMET)
        assertThat(reason.percent).isEqualTo(0.5)
    }

    @Test
    fun `four slots stack additively and emit four mitigation entries`() {
        val modifier = ArmorReductionModifier(FakeArmorRegistry())
        val out = modifier.apply(
            working(10.0),
            attempt(10.0),
            armor = bundle(
                piece("h", ArmorSlot.HELMET, 0.1),
                piece("c", ArmorSlot.CHESTPLATE, 0.2),
                piece("l", ArmorSlot.LEGGINGS, 0.15),
                piece("b", ArmorSlot.BOOTS, 0.05),
            ),
            randomizer = nonRandom,
        )

        // Total reduction = 0.50 → final = 5.0 (within float-precision tolerance)
        assertThat(out.currentAmount).isCloseTo(5.0, within(1e-9))
        assertThat(out.mitigations).hasSize(4)
        // Sum of attributions equals total reduced.
        val sum = out.mitigations.sumOf { it.amountReduced }
        assertThat(sum).isEqualTo(5.0, within(1e-9))
        // Each entry references its slot.
        val slots = out.mitigations
            .map { it.reason as MitigationReason.Armor }
            .map { it.slot }
            .toSet()
        assertThat(slots).containsExactlyInAnyOrder(
            ArmorSlot.HELMET, ArmorSlot.CHESTPLATE, ArmorSlot.LEGGINGS, ArmorSlot.BOOTS,
        )
    }

    @Test
    fun `eighty percent cap prevents invuln`() {
        val modifier = ArmorReductionModifier(FakeArmorRegistry())
        // Per-slot 0.30 × 4 = 1.20 total reduction; cap at 0.80.
        val out = modifier.apply(
            working(10.0),
            attempt(10.0),
            armor = bundle(
                piece("h", ArmorSlot.HELMET, 0.3),
                piece("c", ArmorSlot.CHESTPLATE, 0.3),
                piece("l", ArmorSlot.LEGGINGS, 0.3),
                piece("b", ArmorSlot.BOOTS, 0.3),
            ),
            randomizer = nonRandom,
        )

        // 10 × (1 - 0.8) = 2.0, NOT zero.
        assertThat(out.currentAmount).isEqualTo(2.0, within(1e-9))
        assertThat(out.mitigations).hasSize(4)
        val sum = out.mitigations.sumOf { it.amountReduced }
        assertThat(sum).isEqualTo(8.0, within(1e-9))
    }

    @Test
    fun `four piece set adds set bonus mitigation entry`() {
        val registry = FakeArmorRegistry()
        val setKey = "test-set"
        registry.registerSet(
            ArmorSet(
                key = ArmorSetKey(setKey),
                displayNameKey = MessageKey("astera.armor.set.test-set.name"),
                pieces = setOf(ArmorId("h"), ArmorId("c"), ArmorId("l"), ArmorId("b")),
                twoPieceBonus = 0.05,
                fourPieceBonus = 0.20,
            ),
        )
        val modifier = ArmorReductionModifier(registry)

        val out = modifier.apply(
            working(10.0),
            attempt(10.0),
            armor = bundle(
                piece("h", ArmorSlot.HELMET, 0.05, setKey),
                piece("c", ArmorSlot.CHESTPLATE, 0.05, setKey),
                piece("l", ArmorSlot.LEGGINGS, 0.05, setKey),
                piece("b", ArmorSlot.BOOTS, 0.05, setKey),
            ),
            randomizer = nonRandom,
        )

        // Per-slot total 0.20 + 4-piece bonus 0.20 = 0.40 → final = 6.0
        assertThat(out.currentAmount).isEqualTo(6.0, within(1e-9))
        assertThat(out.mitigations).hasSize(5)
        val setEntries = out.mitigations
            .map { it.reason }
            .filterIsInstance<MitigationReason.ArmorSet>()
        assertThat(setEntries).hasSize(1)
        assertThat(setEntries[0].setKey).isEqualTo(setKey)
        assertThat(setEntries[0].percent).isEqualTo(0.20)
    }

    @Test
    fun `two piece set bonus activates at exactly two pieces`() {
        val registry = FakeArmorRegistry()
        val setKey = "test-set"
        registry.registerSet(
            ArmorSet(
                key = ArmorSetKey(setKey),
                displayNameKey = MessageKey("astera.armor.set.test-set.name"),
                pieces = setOf(ArmorId("h"), ArmorId("c"), ArmorId("l"), ArmorId("b")),
                twoPieceBonus = 0.10,
                fourPieceBonus = 0.30,
            ),
        )
        val modifier = ArmorReductionModifier(registry)

        val out = modifier.apply(
            working(10.0),
            attempt(10.0),
            armor = bundle(
                piece("h", ArmorSlot.HELMET, 0.05, setKey),
                piece("c", ArmorSlot.CHESTPLATE, 0.05, setKey),
            ),
            randomizer = nonRandom,
        )

        // Per-slot total 0.10 + 2-piece bonus 0.10 = 0.20 → final = 8.0
        assertThat(out.currentAmount).isEqualTo(8.0, within(1e-9))
        val setEntries = out.mitigations
            .map { it.reason }
            .filterIsInstance<MitigationReason.ArmorSet>()
        assertThat(setEntries).hasSize(1)
        assertThat(setEntries[0].percent).isEqualTo(0.10)
    }

    @Test
    fun `one piece does not activate any set bonus`() {
        val registry = FakeArmorRegistry()
        val setKey = "test-set"
        registry.registerSet(
            ArmorSet(
                key = ArmorSetKey(setKey),
                displayNameKey = MessageKey("astera.armor.set.test-set.name"),
                pieces = setOf(ArmorId("h"), ArmorId("c")),
                twoPieceBonus = 0.10,
                fourPieceBonus = 0.30,
            ),
        )
        val modifier = ArmorReductionModifier(registry)

        val out = modifier.apply(
            working(10.0),
            attempt(10.0),
            armor = bundle(piece("h", ArmorSlot.HELMET, 0.10, setKey)),
            randomizer = nonRandom,
        )

        // Only the 0.10 helmet reduction; no set bonus → final 9.0.
        assertThat(out.currentAmount).isEqualTo(9.0, within(1e-9))
        assertThat(out.mitigations).hasSize(1)
        assertThat(out.mitigations[0].reason).isInstanceOf(MitigationReason.Armor::class.java)
    }

    @Test
    fun `unknown setKey is silently ignored`() {
        // Registry has no set, but pieces still reference one.
        val modifier = ArmorReductionModifier(FakeArmorRegistry())
        val out = modifier.apply(
            working(10.0),
            attempt(10.0),
            armor = bundle(
                piece("h", ArmorSlot.HELMET, 0.10, "unknown-set"),
                piece("c", ArmorSlot.CHESTPLATE, 0.10, "unknown-set"),
            ),
            randomizer = nonRandom,
        )

        // Per-slot total 0.20 → final 8.0, no set bonus entry.
        assertThat(out.currentAmount).isEqualTo(8.0, within(1e-9))
        assertThat(out.mitigations).hasSize(2)
        assertThat(out.mitigations.map { it.reason })
            .allMatch { it is MitigationReason.Armor }
    }

    @Test
    fun `zero base damage skips processing`() {
        val modifier = ArmorReductionModifier(FakeArmorRegistry())
        val out = modifier.apply(
            working(0.0),
            attempt(0.0),
            armor = bundle(piece("h", ArmorSlot.HELMET, 0.5)),
            randomizer = nonRandom,
        )

        assertThat(out.currentAmount).isEqualTo(0.0)
        assertThat(out.mitigations).isEmpty()
    }

    @Test
    fun `custom cap is respected`() {
        val modifier = ArmorReductionModifier(FakeArmorRegistry(), maxReduction = 0.5)
        // Per-slot 0.40 × 2 = 0.80 → capped to 0.50 → final 5.0.
        val out = modifier.apply(
            working(10.0),
            attempt(10.0),
            armor = bundle(
                piece("h", ArmorSlot.HELMET, 0.4),
                piece("c", ArmorSlot.CHESTPLATE, 0.4),
            ),
            randomizer = nonRandom,
        )

        assertThat(out.currentAmount).isEqualTo(5.0, within(1e-9))
        val sum = out.mitigations.sumOf { it.amountReduced }
        assertThat(sum).isEqualTo(5.0, within(1e-9))
    }

    // Wave 5a-fix: per-attribute reduction tests --------------------------

    @Test
    fun `fire-resistant chestplate reduces fire more than physical`() {
        val modifier = ArmorReductionModifier(FakeArmorRegistry())
        val fireChest = piece(
            "fire-chest",
            ArmorSlot.CHESTPLATE,
            reduction = 0.2,
            perAttribute = mapOf(DamageAttribute.FIRE to 0.6),
        )
        val bundleWithFireChest = bundle(fireChest)

        // FIRE attack: 60% reduction → final 4.0
        val fireOut = modifier.apply(
            working(10.0),
            DamageAttempt(
                targetId = targetId,
                baseProfile = DamageProfile(base = 10.0, attribute = DamageAttribute.FIRE),
                source = DamageSource.Environment,
            ),
            armor = bundleWithFireChest,
            randomizer = nonRandom,
        )
        assertThat(fireOut.currentAmount).isEqualTo(4.0, within(1e-9))
        val fireReason = fireOut.mitigations.single().reason as MitigationReason.Armor
        assertThat(fireReason.attribute).isEqualTo(DamageAttribute.FIRE)
        assertThat(fireReason.percent).isEqualTo(0.6)

        // PHYSICAL attack: 20% reduction (default) → final 8.0
        val physOut = modifier.apply(
            working(10.0),
            attempt(10.0), // PHYSICAL by default
            armor = bundleWithFireChest,
            randomizer = nonRandom,
        )
        assertThat(physOut.currentAmount).isEqualTo(8.0, within(1e-9))
        val physReason = physOut.mitigations.single().reason as MitigationReason.Armor
        assertThat(physReason.attribute).isEqualTo(DamageAttribute.PHYSICAL)
        assertThat(physReason.percent).isEqualTo(0.2)
    }

    @Test
    fun `default reduction applies for attributes not in perAttribute map`() {
        val modifier = ArmorReductionModifier(FakeArmorRegistry())
        val partialResist = piece(
            "partial",
            ArmorSlot.HELMET,
            reduction = 0.3,
            perAttribute = mapOf(DamageAttribute.FIRE to 0.7),
        )

        // ELECTRIC is not in perAttribute → falls back to default 0.3
        val electricOut = modifier.apply(
            working(10.0),
            DamageAttempt(
                targetId = targetId,
                baseProfile = DamageProfile(base = 10.0, attribute = DamageAttribute.ELECTRIC),
                source = DamageSource.Environment,
            ),
            armor = bundle(partialResist),
            randomizer = nonRandom,
        )
        assertThat(electricOut.currentAmount).isEqualTo(7.0, within(1e-9))
        val reason = electricOut.mitigations.single().reason as MitigationReason.Armor
        assertThat(reason.attribute).isEqualTo(DamageAttribute.ELECTRIC)
        assertThat(reason.percent).isEqualTo(0.3)
    }
}
