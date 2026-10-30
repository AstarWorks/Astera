package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.combat.adapter.paper.command.skillExpandSubcommand
import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.skillengine.load.EngineConfigError
import com.astarworks.astera.core.application.skillengine.load.EngineConfigLoader
import com.astarworks.astera.core.application.skillengine.load.SkillEngineSpecs
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.testfixtures.FakeScheduler
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Engine config file and the wiring around it (ADR-0303 11.4, wave1-resolutions item 19, List C entry 6): every key of
 * `content/engine/skill-engine.yaml` is required, the shipped file loads, every op of the ADR is registered and
 * `astera skill expand` writes to `expand_dir`.
 */
class EngineConfigScenarioTest : SkillEngineScenario() {
    private val shipped: Path = contentDir.resolve("engine/skill-engine.yaml")

    @Test
    fun missingKeyIsMissing() {
        val text = Files.readAllLines(shipped)
        for (key in EngineConfigLoader.KEYS) {
            // a key's own line plus the indented block under it (caster_eye)
            val without = mutableListOf<String>().also { out ->
                var skipping = false
                for (l in text) {
                    if (l.startsWith("$key:")) { skipping = true; continue }
                    if (skipping && (l.startsWith(" ") || l.isBlank())) continue
                    skipping = false
                    out += l
                }
            }.joinToString("\n")
            val r = EngineConfigLoader().parse(without, "<test>")
            assertThat(r).`as`("config without $key").isInstanceOf(Result.Failure::class.java)
            assertThat((r as Result.Failure).error).isEqualTo(EngineConfigError.Missing("<test>", key))
        }
    }

    @Test
    fun missingFileIsMissing() {
        val absent = contentDir.resolve("no-such-engine-dir/skill-engine.yaml")
        val r = EngineConfigLoader().load(absent)
        assertThat((r as Result.Failure).error).isEqualTo(EngineConfigError.Missing(absent.toString(), null))
    }

    @Test
    fun shippedConfigLoads() {
        val r = EngineConfigLoader().load(shipped)
        assertThat(r).`as`("shipped config: ${(r as? Result.Failure)?.error?.message}").isInstanceOf(Result.Success::class.java)
        val config = (r as Result.Success).value
        assertThat(config.expandDir).isEqualTo("build/skill-expand")
        assertThat(config).isEqualTo(EngineConfig())
    }

    @Test
    fun everyOpNameInTheAdrIsRegistered() {
        val registered = SkillEngineSpecs.all().map { it.name }
        // example c line 18 still names `caster_anim`, which no spec defines (List A edit of the examples, W1-27).
        val notOps = setOf("caster_anim")
        val opName = Regex("""\bop: ([a-z_]+)""")
        val named = fixtureNames(examplesDir).flatMap { name ->
            opName.findAll(Files.readString(examplesDir.resolve("$name.yaml"))).map { it.groupValues[1] }.toList()
        }.toSet() - notOps
        assertThat(registered).containsAll(named)
        assertThat(registered).containsAll(
            listOf(
                "group", "lock", "cancel", "use", "frame", "display", "particle", "particle_def", "sound", "ribbon", "projectile", "hit",
                "damage", "status", "modify_attribute", "impulse", "knockback", "pull", "heal", "resource", "dash", "reel", "rig", "emit", "remove",
            ),
        )
        assertThat(registered).`as`("one spec per op name").doesNotHaveDuplicates()
    }

    @Test
    fun expandCommandWritesDumpToTheDir() {
        val table = loadExample("a-couture-rapier-s2-coin1").table
        val outDir = evidenceDir.resolve("expand-command-case")
        val spec = skillExpandSubcommand(ids = { listOf(table.skillId) }, tableOf = { id -> table.takeIf { it.skillId == id } }, outDir = outDir)
        val replies = ArrayList<String>()
        val ctx = CommandContext(
            sender = null,
            args = mapOf("target" to "all"),
            scheduler = FakeScheduler(),
            replier = { key, _ -> replies += key.value },
        )
        val result = runBlocking { spec.executor!!(ctx) }
        assertThat(result).isEqualTo(CommandResult.Success)
        assertThat(outDir.resolve("${table.skillId}.txt")).exists()
        assertThat(outDir.resolve("${table.skillId}.json")).exists()
        assertThat(replies).containsExactly("astera.skill.expand.written")
    }
}
