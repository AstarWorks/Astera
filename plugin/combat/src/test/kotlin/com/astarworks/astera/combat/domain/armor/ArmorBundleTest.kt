package com.astarworks.astera.combat.domain.armor

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Tests for the [ArmorBundle] container — construction, the [ArmorBundle.empty]
 * factory, and per-slot lookup semantics that [ArmorReductionModifier] relies
 * on. Aggregation / cap logic lives in `ArmorReductionModifierTest`.
 */
class ArmorBundleTest {

    private val playerId = PlayerId(UUID.randomUUID())

    private fun piece(
        id: String,
        slot: ArmorSlot,
        reduction: Double,
        setKey: String? = null,
    ): ArmorSpec = ArmorSpec(
        id = ArmorId(id),
        displayNameKey = MessageKey("astera.armor.test-$id.name"),
        slot = slot,
        damageReduction = reduction,
        setKey = setKey?.let(::ArmorSetKey),
    )

    @Test
    fun `empty bundle has no equipped pieces`() {
        val bundle = ArmorBundle.empty(playerId)

        assertThat(bundle.playerId).isEqualTo(playerId)
        assertThat(bundle.equipped).isEmpty()
    }

    @Test
    fun `single slot equipped`() {
        val helmet = piece("test-helmet", ArmorSlot.HELMET, reduction = 0.5)
        val bundle = ArmorBundle(playerId, mapOf(ArmorSlot.HELMET to helmet))

        assertThat(bundle.equipped).hasSize(1)
        assertThat(bundle.equipped[ArmorSlot.HELMET]).isEqualTo(helmet)
        assertThat(bundle.equipped[ArmorSlot.BOOTS]).isNull()
    }

    @Test
    fun `four slot bundle exposes all pieces`() {
        val pieces = mapOf(
            ArmorSlot.HELMET to piece("h", ArmorSlot.HELMET, 0.1),
            ArmorSlot.CHESTPLATE to piece("c", ArmorSlot.CHESTPLATE, 0.25),
            ArmorSlot.LEGGINGS to piece("l", ArmorSlot.LEGGINGS, 0.15),
            ArmorSlot.BOOTS to piece("b", ArmorSlot.BOOTS, 0.05),
        )
        val bundle = ArmorBundle(playerId, pieces)

        assertThat(bundle.equipped).hasSize(4)
        ArmorSlot.entries.forEach { slot ->
            assertThat(bundle.equipped[slot]).isNotNull
        }
    }

    @Test
    fun `pieces may share a setKey`() {
        val setKey = "test-set"
        val pieces = mapOf(
            ArmorSlot.HELMET to piece("h", ArmorSlot.HELMET, 0.1, setKey),
            ArmorSlot.CHESTPLATE to piece("c", ArmorSlot.CHESTPLATE, 0.25, setKey),
        )
        val bundle = ArmorBundle(playerId, pieces)

        val keys = bundle.equipped.values.mapNotNull { it.setKey?.value }
        assertThat(keys).containsExactly(setKey, setKey)
    }

    @Test
    fun `ArmorSpec rejects reduction outside 0 to 1`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            piece("bad", ArmorSlot.HELMET, reduction = -0.1)
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            piece("bad", ArmorSlot.HELMET, reduction = 1.5)
        }
    }
}
