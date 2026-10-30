package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.domain.Result
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
}
