package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.domain.skill.ChargedSkillVariant
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Migrated from `SkillLoaderServiceTest` in Wave _infra-2b (ADR-0065).
 *
 * The legacy `MutableSkillRegistry` + `SkillLoaderService` pair is replaced by
 * a single [SkillContents] that subclasses
 * [com.astarworks.astera.core.application.contents.YamlDynamicContents]. The
 * test surface adds reload-semantics checks (Wave _infra-2b goal); the rest of
 * the cases mirror the legacy file 1:1 to keep coverage.
 *
 * Wave _items-1 (ADR-0073): YAML schema migrated to inline locale maps for
 * name / description and `trigger:` removed (binding moved to item YAML).
 */
class SkillContentsTest {

    private val projectileYaml = """
        id: test-blast
        name:
          ja: テスト炸裂弾
          en: Test Blast
        description:
          ja: テスト用炸裂弾
          en: Test blast description
        archetype: projectile
        cooldown_ticks: 40
        params:
          type: projectile
          launch_speed: 1.8
          max_lifetime_ticks: 60
          damage:
            base: 12.0
            attribute: fire
    """.trimIndent()

    private val buffYaml = """
        id: test-charge
        name:
          ja: テスト溜め
          en: Test Charge
        description:
          ja: テスト用溜めスキル
          en: Test charge description
        archetype: buff
        cooldown_ticks: 80
        params:
          type: buff
          status_effect_id: example-speed
          duration_ticks: 100
    """.trimIndent()

    @Test
    fun `bootstrap loads projectile skill`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-blast.yaml"), projectileYaml)

        val contents = SkillContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val spec = contents.find(SkillId("test-blast"))
        assertThat(spec).isNotNull
        requireNotNull(spec)
        assertThat(spec.archetype).isEqualTo(SkillArchetype.PROJECTILE)
        assertThat(spec.cooldownTicks).isEqualTo(40)
        assertThat(spec.params).isInstanceOf(SkillParams.ProjectileParams::class.java)
        val params = spec.params as SkillParams.ProjectileParams
        assertThat(params.launchSpeed).isEqualTo(1.8)
        assertThat(params.maxLifetimeTicks).isEqualTo(60)
        assertThat(params.damage.base).isEqualTo(12.0)
        // Inline i18n round-trips through LocalizedText.
        assertThat(spec.name.byLocale[LocaleCode.EN]).isEqualTo("Test Blast")
        assertThat(spec.description.byLocale[LocaleCode.JA]).isEqualTo("テスト用炸裂弾")
    }

    @Test
    fun `bootstrap loads buff skill`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-charge.yaml"), buffYaml)

        val contents = SkillContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        val spec = contents.find(SkillId("test-charge"))!!
        assertThat(spec.archetype).isEqualTo(SkillArchetype.BUFF)
        assertThat(spec.params).isInstanceOf(SkillParams.BuffParams::class.java)
        val params = spec.params as SkillParams.BuffParams
        assertThat(params.statusEffectId.value).isEqualTo("example-speed")
        assertThat(params.durationTicks).isEqualTo(100)
    }

    @Test
    fun `bootstrap is best-effort - malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), projectileYaml)
        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val contents = SkillContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(SkillId("test-blast"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `missing directory returns zero counts and registry stays empty`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val contents = SkillContents()

        val report = contents.bootstrap(missing)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).isEmpty()
    }

    @Test
    fun `archetype-params mismatch fails the file but not the batch`(@TempDir dir: Path) {
        val mismatch = """
            id: bad-skill
            name:
              en: Bad Skill
            description:
              en: Bad skill description
            archetype: projectile
            cooldown_ticks: 40
            params:
              type: buff
              status_effect_id: nope
              duration_ticks: 20
        """.trimIndent()
        Files.writeString(dir.resolve("bad.yaml"), mismatch)
        Files.writeString(dir.resolve("good.yaml"), projectileYaml)

        val contents = SkillContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(SkillId("bad-skill"))).isNull()
        assertThat(contents.find(SkillId("test-blast"))).isNotNull
    }

    // ----- Reload semantics (Wave _infra-2b new) ---------------------------

    @Test
    fun `reload swaps to new map when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-blast.yaml"), projectileYaml)

        val contents = SkillContents()
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)

        // Replace the single skill with a different one
        Files.delete(dir.resolve("test-blast.yaml"))
        Files.writeString(dir.resolve("replacement.yaml"), projectileYaml.replace("test-blast", "replacement"))

        val report = contents.reload(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.find(SkillId("test-blast"))).isNull()
        assertThat(contents.find(SkillId("replacement"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `reload is all-or-nothing - one bad file keeps old state intact`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-blast.yaml"), projectileYaml)

        val contents = SkillContents()
        contents.bootstrap(dir)
        assertThat(contents.find(SkillId("test-blast"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        // failed > 0 means reload aborts; old state preserved.
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(SkillId("test-blast"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `register adds an entry without going through YAML`() {
        val contents = SkillContents()
        val spec = SkillSpec(
            id = SkillId("programmatic"),
            name = LocalizedText(mapOf(LocaleCode.EN to "Programmatic")),
            description = LocalizedText(mapOf(LocaleCode.EN to "Programmatic desc")),
            archetype = SkillArchetype.BUFF,
            cooldownTicks = 10,
            params = SkillParams.BuffParams(
                statusEffectId = StatusEffectId("test-effect"),
                durationTicks = 40,
            ),
            chargeVariant = ChargedSkillVariant.Instant,
        )

        contents.register(spec)

        assertThat(contents.find(SkillId("programmatic"))).isEqualTo(spec)
    }
}
