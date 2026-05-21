package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.entity.EntityTypeSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryEntityRegistryTest {

    @Test
    fun `default catalog resolves known vanilla types case-insensitively`() {
        val registry = InMemoryEntityRegistry()

        assertThat(registry.resolve("minecraft:zombie")).isNotNull()
        assertThat(registry.resolve("MINECRAFT:Zombie")?.category).isEqualTo(EntityCategory.MOB)
        assertThat(registry.resolve("minecraft:arrow")?.category).isEqualTo(EntityCategory.PROJECTILE)
        assertThat(registry.resolve("minecraft:text_display")?.category).isEqualTo(EntityCategory.DISPLAY)
        assertThat(registry.resolve("minecraft:armor_stand")?.category).isEqualTo(EntityCategory.NPC)
    }

    @Test
    fun `unknown keys resolve to null`() {
        val registry = InMemoryEntityRegistry()
        assertThat(registry.resolve("minecraft:not_a_real_type")).isNull()
        assertThat(registry.resolve("astera:custom")).isNull()
    }

    @Test
    fun `custom entries override or extend the default catalog`() {
        val custom = InMemoryEntityRegistry(
            entries = listOf(EntityTypeSpec("astera:guard", EntityCategory.NPC)),
        )

        assertThat(custom.resolve("astera:guard")).isNotNull()
        // Custom-only registry does NOT carry the default vanilla list.
        assertThat(custom.resolve("minecraft:zombie")).isNull()
    }

    @Test
    fun `all returns every registered entry`() {
        val registry = InMemoryEntityRegistry()
        assertThat(registry.all()).isNotEmpty()
        assertThat(registry.all().map { it.key }).contains("minecraft:zombie", "minecraft:arrow")
    }
}
