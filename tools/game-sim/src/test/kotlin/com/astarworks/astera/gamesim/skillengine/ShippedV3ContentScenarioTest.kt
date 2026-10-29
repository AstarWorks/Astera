package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.application.skillengine.runtime.player.ContactInfo
import com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * A9 (ADR-0303 section 12): every composed skill id under content/skills (one skill id per file) decodes, compiles, and replays its own table;
 * the oracle holds one golden per composed id.
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
        val problems = ArrayList<String>()
        val oracle = LinkedHashMap<String, CompiledSkill>()
        var checked = 0
        // every composed skill id exactly as the server loads it (patches applied, one skill id per file): one compile, one replay, one golden per id
        val composer = com.astarworks.astera.core.application.skillengine.compose.SkillComposer(dir)
        when (val c = composer.composeAll()) {
            is Result.Failure -> problems += "compose: ${c.error}"
            is Result.Success -> {
                // pass 1: decode every composed tree, so a `use: skill:<id>` resolves against the whole catalog, as on the server
                val ids = c.value.keys.toSet()
                val defs = LinkedHashMap<String, SkillDef>()
                val keys = LinkedHashMap<String, String>()
                for ((id, composed) in c.value) {
                    val patched = composed.patch != com.astarworks.astera.core.application.skillengine.compose.PatchMeta.BASE
                    val key = oracleKey(id, patched)
                    when (val d = decodeTree(key, composed.tree, ids)) {
                        is Result.Failure -> problems += "$key: ${d.error}"
                        is Result.Success -> { defs[id] = d.value; keys[id] = key }
                    }
                }
                // pass 2: compile, replay, golden each id
                for ((id, def) in defs) {
                    val key = keys.getValue(id)
                    val patched = key.endsWith("(patched)")
                    when (val r = compileAgainst(def, defs)) {
                        is Result.Failure -> problems += "$key: ${r.error}"
                        is Result.Success -> {
                            checked++
                            oracle[key] = r.value
                            writeEvidence(if (patched) id else "shipped-" + id.replace('/', '_') + "_skill", r.value.table, if (patched) "patched composition of $id" else null)
                            // a callee-only skill (frames_in) cannot be cast directly; it is replayed inside its callers' goldens
                            if (def.framesIn.isNotEmpty()) continue
                            try {
                                assertPlays(r.value)
                            } catch (e: AssertionError) {
                                problems += "$key: ${e.message}"
                            } catch (e: Throwable) {
                                problems += "$key: replay crashed: $e @ ${e.stackTrace.firstOrNull { it.className.contains("astarworks") }}"
                            }
                        }
                    }
                }
            }
        }
        problems += checkOracle(oracle)
        Files.createDirectories(evidenceDir)
        Files.writeString(evidenceDir.resolve("shipped-summary.txt"), "v3 checked: $checked\n")
        assertThat(problems).`as`("A9 problems ($checked composed skill ids checked)").isEmpty()
    }

    /** One golden per composed id (design 1.6): a skill id that composes without a golden file fails here by name, whatever its file layout. */
    @Test
    fun everyComposedIdHasAGolden() {
        if (regenerate) return
        val composer = com.astarworks.astera.core.application.skillengine.compose.SkillComposer(contentDir.resolve("skills"))
        val composed = when (val c = composer.composeAll()) {
            is Result.Failure -> throw AssertionError("compose: ${c.error}")
            is Result.Success -> c.value
        }
        val missing = composed.filter { (id, v) ->
            !Files.exists(goldenFile(oracleKey(id, v.patch != com.astarworks.astera.core.application.skillengine.compose.PatchMeta.BASE)))
        }.keys
        assertThat(missing).`as`("composed skill ids without a golden under skill-oracle/ (run :tools:game-sim:regenSkillOracle)").isEmpty()
    }

    /** Oracle key of a composed id: `<id>/skill` (golden `<id>/skill.txt`), or `<id> (patched)` for a patched variant (`<id>.patched.txt`). */
    private fun oracleKey(id: String, patched: Boolean): String = if (patched) "$id (patched)" else "$id/skill"

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
        // a callee-only skill (frames_in) is not castable on its own: its golden is the static section; its rows play inside the callers' goldens
        for ((yaw, pitch) in if (skill.def.framesIn.isNotEmpty()) emptyList() else poses) {
            val rec = GatewayRecorder()
            rec.faithfulPose = true
            val rad = Math.toRadians(yaw)
            val fwd = Vec3(-Math.sin(rad), 0.0, Math.cos(rad))
            // two dummies ahead of the caster along its heading, so hit volumes that reach forward end in damage rows
            rec.victims = listOf(2.5, 5.0).mapIndexed { i, d ->
                val feet = Vec3(fwd.x * d, 0.0, fwd.z * d)
                HitCandidate(UUID(0L, 10L + i), feet, yaw, HitBox(Vec3(feet.x - 0.3, 0.0, feet.z - 0.3), Vec3(feet.x + 0.3, 1.8, feet.z + 0.3)), 1.62)
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
            // the cast's schedule as it ran: every state exit and the tick the cast ended (see OracleGameplay)
            var castEnd = -1
            for (t in rec.trace) if (t.startsWith("exit:")) {
                val tick = t.substringAfterLast('@').toInt()
                val (state, reason) = t.removePrefix("exit:").substringBeforeLast('@').let { it.substringBeforeLast(':') to it.substringAfterLast(':') }
                out += "g @$tick state-exit reason=$reason | state=$state"
                castEnd = maxOf(castEnd, tick)
            }
            if (castEnd >= 0) out += "g @$castEnd cast-end | pose"
        }
        out += "## gameplay-static"
        out += OracleGameplay.lines(skill)
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
