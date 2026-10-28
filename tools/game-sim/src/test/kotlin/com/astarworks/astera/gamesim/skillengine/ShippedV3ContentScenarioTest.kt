package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * A9 (ADR-0303 section 12): every shipped v3 skill under content/skills decodes, compiles, and replays its own table. Files still
 * on `astera/v2` are listed in the evidence as pending migration (wave 2) and are not judged here.
 */
class ShippedV3ContentScenarioTest : SkillEngineScenario() {
    @Test
    fun everyShippedSkillLoadsExpandsAndReplays() {
        val dir = contentDir.resolve("skills")
        val files = Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(".yaml") }.sorted().toList() }
        val pending = ArrayList<String>()
        val problems = ArrayList<String>()
        var checked = 0
        for (file in files) {
            val text = Files.readString(file)
            val rel = dir.relativize(file).toString()
            // a `kind: Patch` document is applied to its target by the composer (second phase below), it is no skill of its own
            if (text.lineSequence().any { it.trim() == "kind: Patch" }) continue
            if (text.lineSequence().none { it.trim() == "apiVersion: astera/v3" }) {
                pending += rel
                continue
            }
            when (val r = compileText(rel, text)) {
                is Result.Failure -> problems += "$rel: ${r.error}"
                is Result.Success -> {
                    checked++
                    writeEvidence("shipped-" + rel.removeSuffix(".yaml").replace('/', '_'), r.value.table)
                    try {
                        assertPlays(r.value)
                    } catch (e: AssertionError) {
                        problems += "$rel: ${e.message}"
                    } catch (e: Throwable) {
                        problems += "$rel: replay crashed: $e @ ${e.stackTrace.firstOrNull { it.className.contains("astarworks") }}"
                    }
                }
            }
        }
        // second phase: every patched base and skin variant, composed exactly as the server loads them (R35)
        val composer = com.astarworks.astera.core.application.skillengine.compose.SkillComposer(dir)
        when (val c = composer.composeAll()) {
            is Result.Failure -> problems += "compose: ${c.error}"
            is Result.Success -> for ((id, composed) in c.value.filter { (_, v) -> v.patch != com.astarworks.astera.core.application.skillengine.compose.PatchMeta.BASE }) {
                when (val r = compileTree("$id (patched)", composed.tree)) {
                    is Result.Failure -> problems += "$id (patched): ${r.error}"
                    is Result.Success -> {
                        checked++
                        try {
                            assertPlays(r.value)
                        } catch (e: AssertionError) {
                            problems += "$id (patched): ${e.message}"
                        } catch (e: Throwable) {
                            problems += "$id (patched): replay crashed: $e"
                        }
                    }
                }
            }
        }
        Files.createDirectories(evidenceDir)
        Files.writeString(evidenceDir.resolve("shipped-summary.txt"), "v3 checked: $checked\nv2 pending migration: ${pending.size}\n" + pending.joinToString("\n") + "\n")
        assertThat(problems).`as`("A9 problems ($checked v3 skills checked, ${pending.size} v2 pending)").isEmpty()
    }
}
