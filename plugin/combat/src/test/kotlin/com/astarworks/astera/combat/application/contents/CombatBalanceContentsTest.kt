package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.application.service.YamlStatusEffectInteractionRule
import com.astarworks.astera.combat.application.config.StatusEffectInteractionConfig
import com.astarworks.astera.core.domain.damage.DamageAttribute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CombatBalanceContentsTest {

    private fun contents() = CombatBalanceContents(
        initialMatrix = IElementMatrix.Neutral,
        initialRule = YamlStatusEffectInteractionRule(StatusEffectInteractionConfig()),
    )

    private fun matrix(dir: Path, fireVsWater: Double) {
        Files.writeString(
            dir.resolve(CombatBalanceContents.MATRIX_FILE),
            "matchups:\n  fire:\n    water: $fireVsWater\n",
        )
    }

    @Test
    fun `the stable matrix view follows a reload`(@TempDir dir: Path) {
        val contents = contents()
        assertThat(contents.elementMatrix.multiplier(DamageAttribute.FIRE, DamageAttribute.WATER)).isEqualTo(1.0)

        matrix(dir, 0.25)
        val report = contents.reload(dir)

        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.elementMatrix.multiplier(DamageAttribute.FIRE, DamageAttribute.WATER)).isEqualTo(0.25)

        matrix(dir, 3.0)
        contents.reload(dir)
        assertThat(contents.elementMatrix.multiplier(DamageAttribute.FIRE, DamageAttribute.WATER)).isEqualTo(3.0)
    }

    @Test
    fun `a broken file fails the whole reload and keeps the running balance`(@TempDir dir: Path) {
        val contents = contents()
        matrix(dir, 0.5)
        contents.reload(dir)

        matrix(dir, 9.0)
        Files.writeString(dir.resolve(CombatBalanceContents.INTERACTIONS_FILE), "rules: [ this is not valid")
        val report = contents.reload(dir)

        assertThat(report.failed).isEqualTo(1)
        assertThat(report.failures.single().file.fileName.toString()).isEqualTo(CombatBalanceContents.INTERACTIONS_FILE)
        // all-or-nothing: the good matrix file did not sneak in either
        assertThat(contents.elementMatrix.multiplier(DamageAttribute.FIRE, DamageAttribute.WATER)).isEqualTo(0.5)
    }

    @Test
    fun `the shipped balance files reload`() {
        val report = contents().reload(Path.of("../../content/balance")) // test working dir is plugin/combat
        assertThat(report.failed).isEqualTo(0)
    }
}
