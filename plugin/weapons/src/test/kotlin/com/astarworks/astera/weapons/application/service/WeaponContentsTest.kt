package com.astarworks.astera.weapons.application.service

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.weapons.domain.weapon.WeaponId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Migrated from `WeaponLoaderServiceTest` in Wave _infra-2a (ADR-0060).
 *
 * The legacy `MutableWeaponRegistry` + `WeaponLoaderService` pair is replaced
 * by a single [WeaponContents] that subclasses [YamlDynamicContents]. The
 * test surface adds reload-semantics checks (Wave _infra-2a goal); the rest
 * of the cases mirror the legacy file 1:1 to keep coverage.
 */
class WeaponContentsTest {

    private val validYaml = """
        id: test-sword
        display_name_key: test.name
        lore_key: test.lore
        archetype: sword
        rarity: common
        level_requirement: 1
        item: test-sword
        damage:
          base: 5.0
          attribute: physical
        cooldown_ticks: 10
    """.trimIndent()

    @Test
    fun `bootstrap loads one valid weapon`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-sword.yaml"), validYaml)

        val contents = WeaponContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val spec = contents.find(WeaponId("test-sword"))
        assertThat(spec).isNotNull
        requireNotNull(spec)
        assertThat(spec.displayNameKey).isEqualTo(MessageKey("test.name"))
        assertThat(spec.itemId).isEqualTo(ItemId("test-sword"))
        assertThat(spec.damage.base).isEqualTo(5.0)
        assertThat(spec.cooldownTicks).isEqualTo(10)
    }

    @Test
    fun `bootstrap is best-effort - malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), validYaml)
        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val contents = WeaponContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(WeaponId("test-sword"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `missing directory returns zero counts and registry stays empty`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val contents = WeaponContents()

        val report = contents.bootstrap(missing)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).isEmpty()
    }

    @Test
    fun `non-yaml files in the directory are ignored`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), validYaml)
        Files.writeString(dir.resolve("README.md"), "not a weapon")
        Files.writeString(dir.resolve("notes.txt"), "ignore me")

        val contents = WeaponContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
    }

    @Test
    fun `yml extension is also accepted`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-sword.yml"), validYaml)

        val contents = WeaponContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(contents.find(WeaponId("test-sword"))).isNotNull
    }

    // ----- Reload semantics (Wave _infra-2a new) ---------------------------

    @Test
    fun `reload swaps to new map when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-sword.yaml"), validYaml)

        val contents = WeaponContents()
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)

        // Replace the single weapon with a different one
        Files.delete(dir.resolve("test-sword.yaml"))
        Files.writeString(dir.resolve("replacement.yaml"), validYaml.replace("test-sword", "replacement"))

        val report = contents.reload(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.find(WeaponId("test-sword"))).isNull()
        assertThat(contents.find(WeaponId("replacement"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `reload is all-or-nothing - one bad file keeps old state intact`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-sword.yaml"), validYaml)

        val contents = WeaponContents()
        contents.bootstrap(dir)
        assertThat(contents.find(WeaponId("test-sword"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        // failed > 0 means reload aborts; old state preserved.
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(WeaponId("test-sword"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `register adds an entry without going through YAML`() {
        val contents = WeaponContents()
        val spec = com.astarworks.astera.weapons.domain.weapon.WeaponSpec(
            id = WeaponId("programmatic"),
            displayNameKey = MessageKey("test.name"),
            loreKey = MessageKey("test.lore"),
            archetype = com.astarworks.astera.weapons.domain.weapon.WeaponArchetype.SWORD,
            rarity = com.astarworks.astera.weapons.domain.weapon.Rarity.COMMON,
            levelRequirement = 1,
            itemId = ItemId("test-sword"),
            damage = com.astarworks.astera.weapons.domain.weapon.DamageProfile(
                base = 5.0,
                attribute = com.astarworks.astera.core.domain.damage.DamageAttribute.PHYSICAL,
            ),
            cooldownTicks = 10,
        )

        contents.register(spec)

        assertThat(contents.find(WeaponId("programmatic"))).isEqualTo(spec)
    }
}
