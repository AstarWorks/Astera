package com.astarworks.astera.gamesim

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * SKL-17: what every shipped skill DOES (sounds, damage, knockback, velocity and movement of caster and dummy, statuses, block
 * edits, cast phases, cooldown and cost) in the scripted scene of [GameplayDump], pinned per tick against a committed golden.
 * The golden (`gameplay-parity/golden.txt`) is the CURRENT output of HEAD, not an agreed target: it exists so a change in
 * gameplay shows up as a diff. Where HEAD differs from v1 is in `/tmp/claude-1000/skill-engine/audit/gameplay-diff.md`, made by
 * `tools/skill-convert/gameplay_diff.py` from `ASTERA_GAMEPLAY_DUMP_DIR=<dir>` (HEAD) and the v1 dump.
 * To accept a deliberate change, regenerate with `ASTERA_GAMEPLAY_GOLDEN_OUT=<abs path of golden.txt>`.
 */
class GameplayParityScenarioTest {

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

    /** Events of one tick, hashed in emission order, but `pos`/`vel` lines are kept (they carry the movement). */
    private fun digest(id: String, lines: List<String>): List<Pair<Int, String>> {
        val byTick = sortedMapOf<Int, MutableList<String>>()
        for (l in lines) {
            val t = Regex("""^\{"t":(\d+),""").find(l)?.groupValues?.get(1)?.toInt() ?: continue
            byTick.getOrPut(t) { ArrayList() } += l.replaceFirst(Regex("""^\{"t":\d+,"""), "{")
        }
        if (byTick.isEmpty()) return listOf(-1 to "no-events")
        return byTick.map { (t, ev) -> t to hash(ev.joinToString("\n")) }
    }

    @Test
    fun `every shipped skill does what HEAD did when the golden was taken`() {
        val dumpDir = System.getenv("ASTERA_GAMEPLAY_DUMP_DIR")?.let { File(it).also(File::mkdirs) }
        val actual = LinkedHashMap<String, List<Pair<Int, String>>>()
        val errors = ArrayList<String>()
        for (id in GameplayDump.ids()) {
            val lines = GameplayDump.dump(id)
            lines.firstOrNull { it.contains("\"e\":\"error\"") }?.let { errors += "$id: $it" }
            dumpDir?.let { File(it, "$id.jsonl").writeText(lines.joinToString("\n") + "\n") }
            actual[id] = digest(id, lines)
        }
        assertThat(errors).describedAs("every skill casts").isEmpty()

        System.getenv("ASTERA_GAMEPLAY_GOLDEN_OUT")?.let { out ->
            File(out).writeText(actual.flatMap { (id, rows) -> rows.map { "$id\t${it.first}\t${it.second}" } }.joinToString("\n") + "\n")
            return
        }

        val golden = GameplayParityScenarioTest::class.java.getResource("/gameplay-parity/golden.txt")!!.readText().lines().filter { it.isNotBlank() }
            .map { it.split('\t') }.groupBy({ it[0] }) { it[1].toInt() to it[2] }
        val problems = ArrayList<String>()
        for (id in (golden.keys + actual.keys).sorted()) {
            val want = golden[id]
            val got = actual[id]
            if (want == null) { problems += "$id: not in the golden"; continue }
            if (got == null) { problems += "$id: in the golden but not shipped"; continue }
            val w = want.toMap(); val g = got.toMap()
            val first = (w.keys + g.keys).sorted().firstOrNull { w[it] != g[it] }
            if (first != null) problems += "$id: first divergence at tick $first; ${(w.keys + g.keys).count { w[it] != g[it] }} ticks differ"
        }
        assertThat(problems).describedAs("gameplay differs from the golden (dump + gameplay_diff.py shows the fields)").isEmpty()
    }
}
