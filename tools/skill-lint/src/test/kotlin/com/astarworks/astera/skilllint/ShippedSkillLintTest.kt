package com.astarworks.astera.skilllint

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Runs the real linter (real composer, production decoder) over the shipped v2 skills and over one deliberately
 * broken fixture. The production decoder is resolved by [ProductionDecoder]; this test fails until it exists.
 */
class ShippedSkillLintTest {
    private val linter by lazy { SkillLinter(ProductionDecoder.create()) }

    @Test
    fun `shipped v2 skills have no structural FAIL`() {
        val root = Path.of(System.getProperty("astera.lint.contentDir")).resolve("skills")
        val report = linter.lint(root)
        assertThat(report.failures).describedAs(report.render()).isEmpty()
    }

    @Test
    fun `a broken skill is reported by rule with model paths`() {
        val root = Path.of(javaClass.getResource("/skill-lint/broken")!!.toURI())
        val report = linter.lint(root)
        val rules = report.failures.map { it.rule }.toSet()
        assertThat(rules).describedAs(report.render()).contains(
            "presentation-moves-frames", "pose-density", "effect-outside-hit", "hit-without-effect", "projectile-fate",
        )
    }
}
