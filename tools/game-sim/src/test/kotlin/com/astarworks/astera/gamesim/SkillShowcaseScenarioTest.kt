package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * V-1 (ADR-0211): one clean "showcase" trace per skill in `content/skills`, for the
 * `:tools:skill-viewer` contact sheets. The caster stands at the origin, a dummy target
 * (a mob, so no match setup) stands in front; the skill is cast once and the sim runs 100 ticks.
 * Output: `build/sim-traces/showcase-<skill id>.json`. The assertion is only that the trace exists.
 */
class SkillShowcaseScenarioTest {

    private fun skillIds(): List<String> {
        val dir = Path.of(requireNotNull(System.getProperty("astera.sim.contentDir")), "skills")
        return Files.list(dir).use { s ->
            s.map { it.fileName.toString() }.filter { it.endsWith(".yaml") }
                .map { it.removeSuffix(".yaml") }.sorted().toList()
        }
    }

    private fun distanceFor(skillId: String): Double = when (skillId) {
        "sword-slash", "dagger-stab", "greatsword-cleave", "shotgun-blast", "sword-dash" -> 3.0
        "frost-nova", "mechanics-demo-quake" -> 3.0
        else -> 12.0
    }

    @TestFactory
    fun `every skill gets a showcase trace`(): List<DynamicTest> = skillIds().map { id ->
        DynamicTest.dynamicTest(id) {
            val trace = scenario("showcase-$id") {
                val caster = player("caster", team = "red")
                val dummy = mob("dummy", Vec3(0.0, groundY, distanceFor(id)))
                caster.faces(dummy)
                advance(5) // a few quiet ticks so the viewer shows the idle setup
                caster.cast(id)
                advance(95)
            }
            assertThat(trace).exists()
        }
    }
}
