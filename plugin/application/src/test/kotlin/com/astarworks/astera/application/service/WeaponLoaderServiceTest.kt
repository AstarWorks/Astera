package com.astarworks.astera.application.service

import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.weapon.WeaponId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WeaponLoaderServiceTest {

    private val validYaml = """
        id: test-sword
        display_name_key: test.name
        lore_key: test.lore
        archetype: sword
        rarity: common
        level_requirement: 1
        material: STONE_SWORD
        damage:
          base: 5.0
          attribute: physical
        cooldown_ticks: 10
    """.trimIndent()

    @Test
    fun `happy path loads one valid weapon`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-sword.yaml"), validYaml)

        val registry = MutableWeaponRegistry()
        val report = WeaponLoaderService(registry).loadFrom(dir)

        assertThat(report).isEqualTo(WeaponLoaderService.LoadReport(loaded = 1, failed = 0))
        val spec = registry.find(WeaponId("test-sword"))
        assertThat(spec).isNotNull
        requireNotNull(spec)
        assertThat(spec.displayNameKey).isEqualTo(MessageKey("test.name"))
        assertThat(spec.materialKey).isEqualTo("STONE_SWORD")
        assertThat(spec.damage.base).isEqualTo(5.0)
        assertThat(spec.cooldownTicks).isEqualTo(10)
    }

    @Test
    fun `malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), validYaml)
        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val registry = MutableWeaponRegistry()
        val report = WeaponLoaderService(registry).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(registry.find(WeaponId("test-sword"))).isNotNull
        assertThat(registry.all()).hasSize(1)
    }

    @Test
    fun `missing directory returns zero counts and registry stays empty`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val registry = MutableWeaponRegistry()

        val report = WeaponLoaderService(registry).loadFrom(missing)

        assertThat(report).isEqualTo(WeaponLoaderService.LoadReport(loaded = 0, failed = 0))
        assertThat(registry.all()).isEmpty()
    }

    @Test
    fun `non-yaml files in the directory are ignored`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), validYaml)
        Files.writeString(dir.resolve("README.md"), "not a weapon")
        Files.writeString(dir.resolve("notes.txt"), "ignore me")

        val registry = MutableWeaponRegistry()
        val report = WeaponLoaderService(registry).loadFrom(dir)

        assertThat(report).isEqualTo(WeaponLoaderService.LoadReport(loaded = 1, failed = 0))
    }

    @Test
    fun `yml extension is also accepted`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-sword.yml"), validYaml)

        val registry = MutableWeaponRegistry()
        val report = WeaponLoaderService(registry).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(registry.find(WeaponId("test-sword"))).isNotNull
    }
}
