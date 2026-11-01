package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID

/** Reuse the shipped-skill oracle recorder; compare these dumps byte-for-byte across kernel + content revisions. */
class FrameFallbackParityScenarioTest : SkillEngineScenario() {
    private val skills = listOf("pistol-shot", "flame-burst", "couture-rapier-s1", "dagger-stab")

    @Test
    fun nonVerticalHits() {
        for (id in skills) {
            record(id, "horizontal", 0.0)
            record(id, "down45", 45.0)
        }
    }

    @Test
    fun verticalHitsProducePosesWithoutFaults() {
        for (id in skills) record(id, "vertical", 90.0)
    }

    private fun record(id: String, case: String, pitch: Double) {
        val file = contentDir.resolve("skills/$id/skill.yaml")
        val skill = when (val loaded = compileText(file.toString(), Files.readString(file))) {
            is Result.Success -> loaded.value
            is Result.Failure -> error("$id: ${loaded.error}")
        }
        val rec = GatewayRecorder().also { it.faithfulPose = true }
        val radians = Math.toRadians(pitch)
        val distance = if (id in listOf("pistol-shot", "flame-burst")) 2.0 else 0.4
        // The victim centre lies exactly on the incidence ray from the caster eye (also for incoming frames).
        val center = Vec3(0.0, 1.62 - kotlin.math.sin(radians) * distance,
            if (pitch == 90.0) 0.0 else kotlin.math.cos(radians) * distance)
        val feet = center - Vec3(0.0, 0.81, 0.0)
        rec.victims = listOf(HitCandidate(UUID(0, 77), feet, 0.0,
            HitBox(feet + Vec3(-0.3, 0.0, -0.3), feet + Vec3(0.3, 1.8, 0.3)), 1.62))
        play(skill, 7L, emptyList(), skill.table.bounds.path.max + skill.table.bounds.tail + 62, rec, pitchDeg = pitch)
        val rows = rec.oracleRows.map { c ->
            listOf("@${c.tick}", c.verb, c.instance, "op=${c.op ?: "-"}", c.payload, c.detail)
                .filter { it.isNotEmpty() }.joinToString(" ")
        }
        val dir = evidenceDir.parent.resolve("parity")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$id-$case.txt"), rows.joinToString("\n", postfix = "\n"))
        assertThat(rec.calls.filter { it.verb == "damage" }).describedAs("$id $case must really hit").isNotEmpty()
        assertThat(rows).describedAs("$id $case frame poses").anyMatch { " rot=" in it }
        assertThat(rows.joinToString("\n")).doesNotContain("NaN", "Infinity")
    }
}
