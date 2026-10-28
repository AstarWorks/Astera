package com.astarworks.astera.gamesim

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * SKL-17: what every shipped skill hands to vanilla Bukkit, tick by tick, is pinned against a committed golden. The golden
 * (`vanilla-parity/golden.txt`) is the agreed output: v1 (`d41b82f1e`) plus the approved SKL-15 / bastion changes, see
 * `docs/contributing/vanilla-parity.md`. A line is `<skill>\t<tick>\t<hash of that tick's events>`; the events are the display
 * spawns / writes / removals (position, yaw, pitch, the whole transformation, billboard, interpolation and teleport
 * durations, kind / item / model / text) and the `spawnParticle` arguments, rounded to 1e-5.
 *
 * To see WHAT differs, run the dump (`ASTERA_VANILLA_DUMP_DIR=<dir>`, one JSONL per skill) and diff it with
 * `tools/skill-convert/vanilla_diff.py`. To accept a deliberate change, regenerate the golden with
 * `ASTERA_VANILLA_GOLDEN_OUT=<abs path of golden.txt>` and review the diff in the commit.
 */
class VanillaParityScenarioTest {

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

    /** `skill -> tick -> hash`. */
    private fun digest(id: String, lines: List<String>): List<Triple<String, Int, String>> {
        val byTick = sortedMapOf<Int, MutableList<String>>()
        for (l in lines) {
            val t = Regex("""^\{"t":(\d+),""").find(l)?.groupValues?.get(1)?.toInt() ?: continue
            byTick.getOrPut(t) { ArrayList() } += l.replaceFirst(Regex("""^\{"t":\d+,"""), "{")
        }
        if (byTick.isEmpty()) return listOf(Triple(id, -1, "no-events"))
        return byTick.map { (t, ev) -> Triple(id, t, hash(ev.joinToString("\n"))) }
    }

    @Test
    fun `every shipped skill hands vanilla the agreed spawns writes and particles`() {
        val dumpDir = System.getenv("ASTERA_VANILLA_DUMP_DIR")?.let { File(it).also(File::mkdirs) }
        val actual = LinkedHashMap<String, List<Pair<Int, String>>>()
        val errors = ArrayList<String>()
        for (id in VanillaDump.ids()) {
            val lines = VanillaDump.dump(id)
            lines.firstOrNull { it.contains("\"e\":\"error\"") }?.let { errors += "$id: $it" }
            dumpDir?.let { File(it, "$id.jsonl").writeText(lines.joinToString("\n") + "\n") }
            actual[id] = digest(id, lines).map { it.second to it.third }
        }
        assertThat(errors).describedAs("every skill casts").isEmpty()

        System.getenv("ASTERA_VANILLA_GOLDEN_OUT")?.let { out ->
            File(out).writeText(actual.flatMap { (id, rows) -> rows.map { "$id\t${it.first}\t${it.second}" } }.joinToString("\n") + "\n")
            return
        }

        val golden = VanillaParityScenarioTest::class.java.getResource("/vanilla-parity/golden.txt")!!.readText().lines().filter { it.isNotBlank() }
            .map { it.split('\t') }.groupBy({ it[0] }) { it[1].toInt() to it[2] }
        val problems = ArrayList<String>()
        for (id in (golden.keys + actual.keys).sorted()) {
            val want = golden[id]
            val got = actual[id]
            if (want == null) { problems += "$id: not in the golden"; continue }
            if (got == null) { problems += "$id: in the golden but not shipped"; continue }
            val w = want.toMap(); val g = got.toMap()
            val first = (w.keys + g.keys).sorted().firstOrNull { w[it] != g[it] }
            if (first != null) {
                problems += "$id: first divergence at tick $first (${if (first !in g) "golden has events, HEAD has none" else if (first !in w) "HEAD has events, golden has none" else "events differ"}); " +
                    "${(w.keys + g.keys).count { w[it] != g[it] }} ticks differ"
            }
        }
        assertThat(problems).describedAs("vanilla output differs from the golden (run the dump + vanilla_diff.py to see the fields)").isEmpty()
    }
}
