package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.application.skillengine.runtime.player.ContactInfo
import com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * A9 (ADR-0303 section 12): every shipped v3 skill under content/skills decodes, compiles, and replays its own table. Files still
 * on `astera/v2` are listed in the evidence as pending migration (wave 2) and are not judged here.
 *
 * Parity oracle (docs/contributing/vanilla-parity.md): the same replay is also recorded row by row (tick, op, kind, world position,
 * rotation, scale, colour, sound, hit volume, damage; see [GatewayRecorder.oracleRows]) with the caster at yaw 0 / pitch 0 and at
 * yaw 37 / pitch -20, and compared with `src/test/resources/skill-oracle/<skill>.txt` at a fixed 1e-3. A moved particle, display,
 * sound or hit fails with the first differing row named. Regenerate on purpose with `./gradlew :tools:game-sim:regenSkillOracle`
 * (or `-Dastera.oracle.regen=true`) and review the golden diff.
 */
class ShippedV3ContentScenarioTest : SkillEngineScenario() {
    @Test
    fun everyShippedSkillLoadsExpandsAndReplays() {
        val dir = contentDir.resolve("skills")
        val files = Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(".yaml") }.sorted().toList() }
        val pending = ArrayList<String>()
        val problems = ArrayList<String>()
        val oracle = LinkedHashMap<String, CompiledSkill>()
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
                    oracle[rel.removeSuffix(".yaml")] = r.value
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
                        oracle["$id (patched)"] = r.value
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
        problems += checkOracle(oracle)
        Files.createDirectories(evidenceDir)
        Files.writeString(evidenceDir.resolve("shipped-summary.txt"), "v3 checked: $checked\nv2 pending migration: ${pending.size}\n" + pending.joinToString("\n") + "\n")
        assertThat(problems).`as`("A9 problems ($checked v3 skills checked, ${pending.size} v2 pending)").isEmpty()
    }

    // ---- parity oracle ---------------------------------------------------------------------------------------------

    private val goldenDir: Path = repoRoot.resolve("tools/game-sim/src/test/resources/skill-oracle")
    private val regenerate: Boolean = System.getProperty("astera.oracle.regen") == "true"

    /** The two caster poses every skill is recorded at: (yaw, pitch) in degrees. */
    private val poses = listOf(0.0 to 0.0, 37.0 to -20.0)

    /** Golden file of a skill: its file path under content/skills (a patched variant: `<id>.patched` beside the files). */
    private fun goldenFile(key: String): Path = goldenDir.resolve(key.replace(" (patched)", ".patched") + ".txt")

    /** Compares (or, with the regen flag, rewrites) the golden of every skill; returns one problem line per skill that differs. */
    private fun checkOracle(skills: Map<String, CompiledSkill>): List<String> {
        val problems = ArrayList<String>()
        if (regenerate && Files.exists(goldenDir)) {
            Files.walk(goldenDir).use { s -> s.sorted(Comparator.reverseOrder()).filter { it != goldenDir }.forEach { Files.delete(it) } }
        }
        for ((key, skill) in skills) {
            val recorded = try {
                recordSkill(skill)
            } catch (e: Throwable) {
                problems += "oracle $key: recording crashed: $e"
                continue
            }
            val file = goldenFile(key)
            if (regenerate) {
                Files.createDirectories(file.parent)
                Files.writeString(file, recorded.joinToString("\n", postfix = "\n"))
                continue
            }
            if (!Files.exists(file)) {
                problems += "oracle $key: no golden at $file (run :tools:game-sim:regenSkillOracle and commit it)"
                continue
            }
            allDifferences(key, Files.readAllLines(file), recorded)?.let { problems += it }
        }
        if (!regenerate && Files.exists(goldenDir)) {
            val wanted = skills.keys.map { goldenFile(it) }.toSet()
            Files.walk(goldenDir).use { s -> s.filter { Files.isRegularFile(it) && it !in wanted }.sorted().forEach { problems += "oracle: golden $it belongs to no shipped skill (delete it or regenerate)" } }
        }
        return problems
    }

    /** All oracle rows of [skill] at every pose, as golden lines (`## pose` headers separate the poses). */
    private fun recordSkill(skill: CompiledSkill): List<String> {
        val out = ArrayList<String>()
        for ((yaw, pitch) in poses) {
            val rec = GatewayRecorder()
            rec.faithfulPose = true
            val rad = Math.toRadians(yaw)
            val fwd = Vec3(-Math.sin(rad), 0.0, Math.cos(rad))
            // two dummies ahead of the caster along its heading, so hit volumes that reach forward end in damage rows
            rec.victims = listOf(2.5, 5.0).mapIndexed { i, d ->
                val feet = Vec3(fwd.x * d, 0.0, fwd.z * d)
                HitCandidate(UUID(0L, 10L + i), feet, yaw, HitBox(Vec3(feet.x - 0.3, 0.0, feet.z - 0.3), Vec3(feet.x + 0.3, 1.8, feet.z + 0.3)))
            }
            val contact = ContactInfo(Vector3d(fwd.x * 4.0, 0.0, fwd.z * 4.0), Vector3d(0.0, 1.0, 0.0))
            val events = scriptedEvents(skill).map { RuntimeEvent(it.path, it.tick, it.index, contact, it.victim) }
            expectedCalls(skill, events)
            val names = instanceNames
            // a crash or fault is part of the record (with the dummies in reach a hit can raise cells that only exist on the hit); fixing it shows as a golden diff
            val ended = try {
                play(skill, 7L, events, null, rec, yaw, pitch)
                null
            } catch (e: Exception) {
                (e.message ?: e.toString()).replace(Regex("\\s+"), "_")
            }
            out += "## pose yaw=${yaw.toInt()} pitch=${pitch.toInt()}"
            if (ended != null) out += "!! play-ended-early msg=${ended.take(400)}"
            for (c in rec.oracleRows) {
                val op = c.op ?: names[c.instance] ?: "-"
                out += listOf("@${c.tick}", c.verb, c.instance, "op=$op", c.payload, c.detail).filter { it.isNotEmpty() }.joinToString(" ")
            }
        }
        return out
    }

    /**
     * Compares golden and recording row by row (number-wise at 1e-3, text-wise exact). Returns null when equal, else one problem
     * with a count per differing field (`op` = the op id token), the number of rows that differ only by op id, and the first 10 rows plus the first 3 rows of every other differing field.
     */
    private fun allDifferences(key: String, golden: List<String>, recorded: List<String>): String? {
        var pose = "?"
        val perField = java.util.TreeMap<String, Int>()
        val shown = ArrayList<String>()
        val shownPerField = HashMap<String, Int>()
        var rowsDiffer = 0
        var opOnly = 0
        fun numEq(p: String, q: String): Boolean {
            val x = p.split(',', ';', '~')
            val y = q.split(',', ';', '~')
            return x.size == y.size && x.indices.all { k ->
                val dx = x[k].toDoubleOrNull()
                val dy = y[k].toDoubleOrNull()
                if (dx != null && dy != null) Math.abs(dx - dy) <= 1e-3 + 1e-9 else x[k] == y[k]
            }
        }
        for (i in 0 until maxOf(golden.size, recorded.size)) {
            val old = golden.getOrNull(i)
            val new = recorded.getOrNull(i)
            if (new != null && new.startsWith("## ")) pose = new.removePrefix("## ")
            if (old == new) continue
            rowsDiffer++
            val diffs = ArrayList<String>()
            if (old == null || new == null) {
                diffs += if (old == null) "extra-row" else "missing-row"
            } else {
                val a = old.split(' ')
                val b = new.split(' ')
                if (a.size != b.size) diffs += "layout"
                else for (j in a.indices) {
                    if (a[j] == b[j]) continue
                    if (j < 3) { diffs += if (j == 0) "tick" else if (j == 1) "verb" else "instance"; continue }
                    val field = a[j].substringBefore('=')
                    if (field != b[j].substringBefore('=')) { diffs += "fieldlist"; continue }
                    if (j == 3) { diffs += "op"; continue }
                    if (!numEq(a[j].substringAfter('='), b[j].substringAfter('='))) diffs += field
                }
            }
            if (diffs.isEmpty()) continue
            if (diffs == listOf("op")) opOnly++
            diffs.forEach { perField.merge(it, 1, Int::plus) }
            if (shown.size < 10 || diffs.any { d -> shownPerField.merge(d, 1, Int::plus)!! <= 3 && d != "op" && d != "seed" }) shown += "  row ${i + 1} [$pose] ${diffs.joinToString(",")}\n    old: $old\n    new: $new"
        }
        if (perField.isEmpty()) return null
        return "oracle $key: $rowsDiffer rows differ (op-id only: $opOnly); per field $perField\n" + shown.joinToString("\n")
    }
}
