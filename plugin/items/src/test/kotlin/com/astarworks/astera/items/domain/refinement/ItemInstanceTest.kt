package com.astarworks.astera.items.domain.refinement

import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ItemInstanceTest {

    private val itemId = ItemId("example-sword")
    private val owner = PlayerId(UUID.randomUUID())
    private fun fresh(): ItemInstance = ItemInstance(
        id = ItemInstanceId.random(),
        itemId = itemId,
        owner = owner,
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `fresh instance has no layers and zero reroll count`() {
        val instance = fresh()
        assertThat(instance.layers()).isEmpty()
        assertThat(instance.subStatRolls()).isEmpty()
        assertThat(instance.rerollCount()).isZero()
    }

    @Test
    fun `addLayer composes multiplicatively via container effectiveNumber`() {
        val instance = fresh()
        // Base of 0 — the container starts empty. We seed the REFINED_POWER_KEY
        // to verify MULTIPLY layers compound on it.
        instance.container.set(
            RefinementMath.REFINED_POWER_KEY,
            com.astarworks.astera.core.domain.attribute.AttributeValue.NumberValue(100.0),
        )

        instance.addLayer(Refinement.Reforge("sharp", 3))   // x1.30
        instance.addLayer(Refinement.Star(5))               // x1.40
        // Expected: 100 * 1.30 * 1.40 = 182
        val effective = instance.container.effectiveNumber(RefinementMath.REFINED_POWER_KEY)
        assertThat(effective).isEqualTo(100.0 * 1.30 * 1.40)
    }

    @Test
    fun `replaceSubStats updates container base value and increments reroll count`() {
        val instance = fresh()
        instance.replaceSubStats(
            listOf(
                SubStatRoll(SubStatKey.ATTACK, 42.0, 1),
                SubStatRoll(SubStatKey.CRIT_CHANCE, 7.0, 1),
            ),
        )
        assertThat(instance.rerollCount()).isEqualTo(1)
        assertThat(instance.subStatRoll(SubStatKey.ATTACK)?.magnitude).isEqualTo(42.0)
        assertThat(
            instance.container.effectiveNumber(AttributeKey(SubStatKey.ATTACK.containerKey)),
        ).isEqualTo(42.0)
        assertThat(
            instance.container.effectiveNumber(AttributeKey(SubStatKey.DEFENSE.containerKey)),
        ).isEqualTo(0.0)
    }

    @Test
    fun `bumpRerollCount increments without touching state`() {
        val instance = fresh()
        instance.replaceSubStats(listOf(SubStatRoll(SubStatKey.ATTACK, 50.0, 1)))
        val before = instance.subStatRolls()
        val countBefore = instance.rerollCount()
        instance.bumpRerollCount()
        assertThat(instance.rerollCount()).isEqualTo(countBefore + 1)
        assertThat(instance.subStatRolls()).isEqualTo(before)
    }

    @Test
    fun `rebuildContainer is idempotent across multiple calls`() {
        val instance = fresh()
        instance.container.set(
            RefinementMath.REFINED_POWER_KEY,
            com.astarworks.astera.core.domain.attribute.AttributeValue.NumberValue(50.0),
        )
        instance.addLayer(Refinement.Reforge("sharp", 2))  // x1.20
        val first = instance.container.effectiveNumber(RefinementMath.REFINED_POWER_KEY)
        instance.rebuildContainer()
        val second = instance.container.effectiveNumber(RefinementMath.REFINED_POWER_KEY)
        assertThat(second).isEqualTo(first)
    }
}
