package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5e (ADR-0058 §5) IT: round-trip a player container with mixed values
 * (STAT_KILLS number, WALLET_GOLD number, flag, modifier) and verify that
 * a *fresh* store instance against the same database reads back equivalent
 * state. Testcontainers-gated by [PostgresIntegrationTestBase].
 */
public class PostgresPlayerAttributeContainerIT : PostgresIntegrationTestBase() {

    @Test
    public fun `containerFor + mutations + flush round-trip via the V14 row`() {
        val store = attributeStore()
        val id = PlayerId(UUID.randomUUID())

        val container = store.load(id).containerFor(id)
        container.set(Attributes.STAT_KILLS, AttributeValue.NumberValue(7.0))
        container.set(Attributes.WALLET_GOLD, AttributeValue.NumberValue(1500.0))
        container.addModifier(
            AttributeModifier(
                key = Attributes.RESIST_FIRE,
                operation = AttributeModifier.Operation.ADD,
                value = 0.3,
                sourceId = "armor:fire-chest",
            ),
        )
        store.flush(id)

        // Re-load from a brand new store instance — exercises the JSONB read path.
        val freshStore = attributeStore()
        val reloaded = freshStore.load(id).containerFor(id)

        assertThat((reloaded.get(Attributes.STAT_KILLS) as AttributeValue.NumberValue).value).isEqualTo(7.0)
        assertThat((reloaded.get(Attributes.WALLET_GOLD) as AttributeValue.NumberValue).value).isEqualTo(1500.0)
        val fireMods = reloaded.modifiers().filter { it.key == Attributes.RESIST_FIRE }
        assertThat(fireMods).hasSize(1)
        assertThat(fireMods[0].sourceId).isEqualTo("armor:fire-chest")
        assertThat(fireMods[0].operation).isEqualTo(AttributeModifier.Operation.ADD)
        assertThat(fireMods[0].value).isEqualTo(0.3)
    }

    @Test
    public fun `loaded snapshot includes only the players seen via containerFor`() {
        val store = attributeStore()
        val a = PlayerId(UUID.randomUUID())
        val b = PlayerId(UUID.randomUUID())

        store.load(a).containerFor(a).set(Attributes.STAT_WINS, AttributeValue.NumberValue(1.0))
        store.load(b).containerFor(b) // touched but not mutated

        assertThat(store.loaded().keys).containsExactlyInAnyOrder(a, b)
    }

    @Test
    public fun `removeModifiersBySource persists across a fresh load`() {
        val store = attributeStore()
        val id = PlayerId(UUID.randomUUID())
        val container = store.load(id).containerFor(id)

        container.addModifier(
            AttributeModifier(Attributes.RESIST_FIRE, AttributeModifier.Operation.ADD, 0.3, "armor:fire-chest"),
        )
        container.addModifier(
            AttributeModifier(Attributes.RESIST_FIRE, AttributeModifier.Operation.ADD, 0.2, "armor:fire-chest"),
        )
        container.addModifier(
            AttributeModifier(Attributes.RESIST_FIRE, AttributeModifier.Operation.ADD, 0.5, "armor:fire-helm"),
        )
        container.removeModifiersBySource("armor:fire-chest")
        store.flush(id)

        val freshStore = attributeStore()
        val reloaded = freshStore.load(id).containerFor(id)

        assertThat(reloaded.modifiers().none { it.sourceId == "armor:fire-chest" }).isTrue()
        assertThat(reloaded.modifiers().count { it.sourceId == "armor:fire-helm" }).isEqualTo(1)
    }
}
