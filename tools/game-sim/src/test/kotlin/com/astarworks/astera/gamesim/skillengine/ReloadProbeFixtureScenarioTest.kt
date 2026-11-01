package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The e2e-only probe skill (docker/e2e-fixtures/reload-probe.yaml, baked by docker/e2e-tune-content.sh, rewritten by SkillEngineV3E2eTest)
 * goes through the real composer, decoder and compiler exactly as the server loads it, so a vocabulary change that strands the fixture fails
 * here instead of as a boot ERROR in the e2e window.
 */
class ReloadProbeFixtureScenarioTest : SkillEngineScenario() {
    @Test
    fun reloadProbeFixtureDecodesAndCompiles(@TempDir tmp: Path) {
        val fixture = repoRoot.resolve("docker/e2e-fixtures/reload-probe.yaml")
        val dir = tmp.resolve("skills/reload-probe")
        Files.createDirectories(dir)
        Files.copy(fixture, dir.resolve("skill.yaml"))
        val composed = when (val c = SkillComposer(tmp.resolve("skills")).composeAll()) {
            is Result.Success -> c.value
            is Result.Failure -> error("compose: ${c.error}")
        }
        assertThat(composed.keys).contains("reload-probe")
        val tree = composed.getValue("reload-probe").tree
        val def = when (val d = decodeTree("reload-probe", tree, composed.keys.toSet())) {
            is Result.Success -> d.value
            is Result.Failure -> error("decode: ${d.error}")
        }
        when (val r = compileAgainst(def, mapOf(def.id to def))) {
            is Result.Success -> assertPlays(r.value)
            is Result.Failure -> error("compile: ${r.error}")
        }
    }

    @Test
    fun balanceReloadReplacesDamageOnceAndRestoresTheAcceptedBase(@TempDir tmp: Path) {
        val skillFile = tmp.resolve("reload-probe/skill.yaml")
        Files.createDirectories(skillFile.parent)
        Files.copy(repoRoot.resolve("docker/e2e-fixtures/reload-probe.yaml"), skillFile)
        val composer = SkillComposer(tmp)
        check(composer.composeAll() is Result.Success)
        fun measure(): String {
            val rec = GatewayRecorder().also {
                val feet = Vec3(0.0, 0.0, 3.0)
                it.victims = listOf(HitCandidate(
                    UUID(0, 77), feet, 0.0,
                    HitBox(feet, feet + Vec3(0.6, 1.8, 0.6)), 1.62,
                ))
            }
            val tree = composer.composed().getValue("reload-probe").tree
            val skill = when (val c = compileTree("reload-probe", tree)) {
                is Result.Success -> c.value
                is Result.Failure -> error("compile: ${c.error}")
            }
            play(skill, 1L, emptyList(), 42, rec)
            assertThat(lastPlayer!!.faults()).isEmpty()
            return rec.calls.single { it.verb == "damage" }.payload
        }
        assertThat(measure()).isEqualTo("physical=10")
        val patch = skillFile.parent.resolve("balance.yaml")
        Files.writeString(patch, """
            apiVersion: astera/v3
            kind: Patch
            id: reload-probe-overlay-balance
            target: reload-probe
            patchKind: balance
            ops:
              - {path: "params.dmg.default", ${'$'}patch: replace, value: 20}
        """.trimIndent())
        repeat(2) {
            check(composer.recompose(listOf(patch)) is Result.Success)
            assertThat(composer.composed().getValue("reload-probe").sources.count { it == "reload-probe/balance.yaml" }).isEqualTo(1)
            assertThat(measure()).isEqualTo("physical=20")
        }
        Files.delete(patch)
        check(composer.recompose(listOf(patch)) is Result.Success)
        assertThat(measure()).isEqualTo("physical=10")
    }
}
