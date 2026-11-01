package com.astarworks.astera.gamesim.skillengine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Every skill that authors a body clip (`anim:` on a `rig` or `body` op) must put that clip on the caster's body in every recorded pose: the
 * committed goldens (`skill-oracle/<skill>/skill.txt`, kept equal to the engine's output by [ShippedV3ContentScenarioTest]) carry a
 * `body=<clip>` effect row for it. The caster's own body animation vanished once (2026-10-30, the puppet was hidden from its owner); the Paper
 * adapter plays what the engine emits, so this pins the engine side of the contract and the sweep over every body-clip skill.
 */
class BodyClipEveryCasterScenarioTest : SkillEngineScenario() {

    @Test
    fun everyAuthoredBodyClipReachesTheCaster() {
        val skills = repoRoot.resolve("content/skills")
        val goldens = repoRoot.resolve("tools/game-sim/src/test/resources/skill-oracle")
        val anim = Regex("""\banim:\s*([A-Za-z0-9_-]+)""")
        val problems = ArrayList<String>()
        var checked = 0
        Files.list(skills).use { s -> s.sorted().toList() }.forEach { dir ->
            val yaml = dir.resolve("skill.yaml")
            if (!Files.isRegularFile(yaml)) return@forEach
            val clips = Files.readAllLines(yaml).filterNot { it.trimStart().startsWith("#") }.flatMap { l -> anim.findAll(l).map { it.groupValues[1] }.toList() }.toSet()
            if (clips.isEmpty()) return@forEach
            val golden = goldens.resolve(dir.fileName.toString()).resolve("skill.txt")
            if (!Files.isRegularFile(golden)) { problems += "${dir.fileName}: no oracle golden"; return@forEach }
            val text = Files.readString(golden)
            for (clip in clips) {
                checked++
                if (!text.contains(" body=$clip ")) problems += "${dir.fileName}: body clip '$clip' is authored but never played on the caster"
            }
        }
        assertThat(checked).`as`("body-clip skills swept").isGreaterThan(10)
        assertThat(problems).isEmpty()
    }
}
