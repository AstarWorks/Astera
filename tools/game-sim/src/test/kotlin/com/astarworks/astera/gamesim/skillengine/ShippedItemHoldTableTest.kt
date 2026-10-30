package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files

/**
 * The weapon hold table cannot drift from the skills (real shipped content): every item that binds skills declares `hold:` (an item with no skills may declare only `none`), and that entry equals
 * what its bound skills (and the skills they `use:`) actually apply. Derivation, over the composed skill trees:
 * - a hold status (freeze -> freeze, shock -> shock, root -> root, slow / sinking -> slow), a `knockback` op (strength resolved from the skill params;
 *   below 0.3 does not count; 0.3-0.69 light, 0.7-1.29 medium, 1.3+ heavy), a `pull` op (pull; with a knockback: knockback_pull) and a hit-stop `freeze` op
 *   together with a knockback (knockback_stop);
 * - a status kind carries no level; an item whose skills apply two different kinds is ambiguous and fails (a weapon family keeps one hold kind).
 */
class ShippedItemHoldTableTest : SkillEngineScenario() {
    private val statusKinds = mapOf("freeze" to "freeze", "shock" to "shock", "root" to "root", "slow" to "slow", "sinking" to "slow")
    private val unsupported = setOf("stun")

    private class Found(val kinds: MutableSet<String> = linkedSetOf(), var kb: Double = 0.0, var pull: Boolean = false, var stop: Boolean = false)

    private fun composed(): Map<String, Map<String, Any?>> =
        when (val c = SkillComposer(contentDir.resolve("skills")).composeAll()) {
            is Result.Failure -> throw AssertionError("compose: ${c.error}")
            is Result.Success -> c.value.mapValues { it.value.tree }
        }

    private fun resolve(raw: Any?, params: Map<*, *>): Double? {
        val s = raw?.toString()?.trim() ?: return null
        s.toDoubleOrNull()?.let { return it }
        val name = Regex("""\$\{(\w+)}|=\s*(\w+)""").matchEntire(s)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: return null
        return ((params[name] as? Map<*, *>)?.get("default"))?.toString()?.toDoubleOrNull()
    }

    private fun walk(node: Any?, params: Map<*, *>, out: Found, calls: MutableSet<String>, problems: MutableList<String>, where: String) {
        when (node) {
            is Map<*, *> -> {
                when (node["op"]) {
                    "knockback" -> {
                        val v = resolve(node["strength"], params)
                        if (v == null) problems += "$where: knockback strength '${node["strength"]}' does not resolve to a param default or number" else out.kb = maxOf(out.kb, v)
                    }
                    "pull" -> out.pull = true
                    "freeze" -> out.stop = true
                    "status" -> {
                        val st = node["status"]?.toString()
                        if (st in unsupported) problems += "$where: hold status '$st' has no hold kind"
                        statusKinds[st]?.let { out.kinds += it }
                    }
                }
                (node["use"] as? String)?.takeIf { it.startsWith("skill:") }?.let { calls += it.removePrefix("skill:") }
                node.forEach { (k, v) -> walk(v, params, out, calls, problems, "$where/$k") }
            }
            is List<*> -> node.forEachIndexed { i, v -> walk(v, params, out, calls, problems, "$where[$i]") }
        }
    }

    private fun derive(skillId: String, trees: Map<String, Map<String, Any?>>, out: Found, seen: MutableSet<String>, problems: MutableList<String>) {
        if (!seen.add(skillId)) return
        val tree = trees[skillId] ?: run { problems += "skill '$skillId' is not a composed skill"; return }
        val calls = linkedSetOf<String>()
        walk(tree, tree["params"] as? Map<*, *> ?: emptyMap<Any, Any>(), out, calls, problems, skillId)
        calls.forEach { derive(it, trees, out, seen, problems) }
    }

    private fun level(kb: Double): String = when {
        kb >= 1.3 -> "heavy"
        kb >= 0.7 -> "medium"
        else -> "light"
    }

    private fun expected(f: Found, id: String, problems: MutableList<String>): Map<String, Any?> {
        val knock = f.kb >= 0.3
        val kinds = f.kinds.toMutableSet()
        if (knock) kinds += when { f.pull -> "knockback_pull"; f.stop -> "knockback_stop"; else -> "knockback" } else if (f.pull) kinds += "pull"
        if (kinds.size > 1) problems += "$id: skills apply several hold kinds $kinds (a weapon keeps one)"
        val kind = kinds.firstOrNull() ?: "none"
        return if (kind.startsWith("knockback")) mapOf("kind" to kind, "level" to level(f.kb)) else mapOf("kind" to kind)
    }

    @Test
    fun everyItemHoldMatchesItsSkills() {
        val trees = composed()
        val problems = ArrayList<String>()
        val yaml = Yaml()
        Files.list(contentDir.resolve("items")).use { it.sorted().toList() }.filter { it.toString().endsWith(".yaml") }.forEach { file ->
            val doc = Files.newBufferedReader(file).use { r -> yaml.load<Map<String, Any?>>(r) } ?: return@forEach
            val id = doc["id"]?.toString() ?: return@forEach
            val bound = (doc["skills"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("skill_id")?.toString() }.orEmpty()
            val hold = doc["hold"] as? Map<*, *>
            if (bound.isEmpty()) {
                // an item whose skills are gone (the deleted Limbus families) may only say `none`
                if (hold != null && hold.entries.associate { it.key.toString() to it.value } != mapOf("kind" to "none")) problems += "$id: binds no skills, so its hold must be `none` (or absent)"
                return@forEach
            }
            val found = Found()
            val seen = HashSet<String>()
            bound.forEach { derive(it, trees, found, seen, problems) }
            val want = expected(found, id, problems)
            val have = hold?.entries?.associate { it.key.toString() to it.value } ?: run { problems += "$id: binds skills but declares no hold (derived: $want)"; return@forEach }
            if (have != want) problems += "$id: hold $have but its skills apply $want"
        }
        assertThat(problems).`as`("item hold entries that drift from their skills").isEmpty()
    }
}
