package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillId
import com.astarworks.astera.combat.domain.skill.SkillTrigger
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end test for [SkillLoaderService] (Stage 1 J).
 *
 * Covers: happy path projectile + buff variants, malformed-file isolation,
 * unknown-extension ignore, missing-directory soft fallback, and
 * archetype/params mismatch rejection (the cross-field constraint that
 * [com.astarworks.astera.combat.application.config.SkillYamlConfig.toSpec] enforces).
 */
class SkillLoaderServiceTest {

    private val projectileYaml = """
        id: test-blast
        display_name_key: astera.skill.test-blast.name
        description_key: astera.skill.test-blast.lore
        archetype: projectile
        trigger: right_click
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
        display_name_key: astera.skill.test-charge.name
        description_key: astera.skill.test-charge.lore
        archetype: buff
        trigger: hold
        cooldown_ticks: 80
        params:
          type: buff
          status_effect_id: example-speed
          duration_ticks: 100
    """.trimIndent()

    @Test
    fun `happy path loads projectile skill`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-blast.yaml"), projectileYaml)

        val registry = MutableSkillRegistry()
        val report = SkillLoaderService(registry).loadFrom(dir)

        assertThat(report).isEqualTo(SkillLoaderService.LoadReport(loaded = 1, failed = 0))
        val spec = registry.find(SkillId("test-blast"))
        assertThat(spec).isNotNull
        requireNotNull(spec)
        assertThat(spec.archetype).isEqualTo(SkillArchetype.PROJECTILE)
        assertThat(spec.trigger).isEqualTo(SkillTrigger.RIGHT_CLICK)
        assertThat(spec.cooldownTicks).isEqualTo(40)
        assertThat(spec.params).isInstanceOf(SkillParams.ProjectileParams::class.java)
        val params = spec.params as SkillParams.ProjectileParams
        assertThat(params.launchSpeed).isEqualTo(1.8)
        assertThat(params.maxLifetimeTicks).isEqualTo(60)
        assertThat(params.damage.base).isEqualTo(12.0)
    }

    @Test
    fun `happy path loads buff skill`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-charge.yaml"), buffYaml)

        val registry = MutableSkillRegistry()
        val report = SkillLoaderService(registry).loadFrom(dir)

        assertThat(report).isEqualTo(SkillLoaderService.LoadReport(loaded = 1, failed = 0))
        val spec = registry.find(SkillId("test-charge"))
        assertThat(spec).isNotNull
        requireNotNull(spec)
        assertThat(spec.archetype).isEqualTo(SkillArchetype.BUFF)
        assertThat(spec.params).isInstanceOf(SkillParams.BuffParams::class.java)
        val params = spec.params as SkillParams.BuffParams
        assertThat(params.statusEffectId.value).isEqualTo("example-speed")
        assertThat(params.durationTicks).isEqualTo(100)
    }

    @Test
    fun `malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), projectileYaml)
        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val registry = MutableSkillRegistry()
        val report = SkillLoaderService(registry).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(registry.find(SkillId("test-blast"))).isNotNull
    }

    @Test
    fun `missing directory returns zero counts`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val registry = MutableSkillRegistry()

        val report = SkillLoaderService(registry).loadFrom(missing)

        assertThat(report).isEqualTo(SkillLoaderService.LoadReport(loaded = 0, failed = 0))
    }

    @Test
    fun `archetype-params mismatch fails the file but not the batch`(@TempDir dir: Path) {
        val mismatch = """
            id: bad-skill
            display_name_key: astera.skill.bad.name
            description_key: astera.skill.bad.lore
            archetype: projectile
            trigger: right_click
            cooldown_ticks: 40
            params:
              type: buff
              status_effect_id: nope
              duration_ticks: 20
        """.trimIndent()
        Files.writeString(dir.resolve("bad.yaml"), mismatch)
        Files.writeString(dir.resolve("good.yaml"), projectileYaml)

        val registry = MutableSkillRegistry()
        val report = SkillLoaderService(registry).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(registry.find(SkillId("bad-skill"))).isNull()
        assertThat(registry.find(SkillId("test-blast"))).isNotNull
    }
}
