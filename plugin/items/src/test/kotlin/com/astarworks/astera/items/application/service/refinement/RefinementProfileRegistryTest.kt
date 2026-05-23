package com.astarworks.astera.items.application.service.refinement

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.items.application.config.refinement.RefinementProfileYaml
import com.astarworks.astera.items.application.config.refinement.RefinementYamlConfig
import com.astarworks.astera.items.domain.refinement.RefinementSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RefinementProfileRegistryTest {

    @Test
    fun `unregistered ItemId resolves to DISABLED`() {
        val registry = RefinementProfileRegistry()
        assertThat(registry.resolve(ItemId("never-registered"))).isEqualTo(RefinementSpec.DISABLED)
    }

    @Test
    fun `registerFromYaml ingests profile and resolves it back`() {
        val registry = RefinementProfileRegistry()
        registry.registerFromYaml(
            RefinementProfileYaml(
                id = "example-sword",
                profile = RefinementYamlConfig(enabled = true, gemstoneSlots = 2),
            ),
        )
        val spec = registry.resolve(ItemId("example-sword"))
        assertThat(spec.enabled).isTrue()
        assertThat(spec.gemstoneSlots).isEqualTo(2)
    }

    @Test
    fun `clear empties the registry`() {
        val registry = RefinementProfileRegistry()
        registry.register(ItemId("a"), RefinementSpec(enabled = true, gemstoneSlots = 1))
        registry.register(ItemId("b"), RefinementSpec(enabled = true, gemstoneSlots = 2))
        assertThat(registry.all()).hasSize(2)
        registry.clear()
        assertThat(registry.all()).isEmpty()
        assertThat(registry.resolve(ItemId("a"))).isEqualTo(RefinementSpec.DISABLED)
    }
}
