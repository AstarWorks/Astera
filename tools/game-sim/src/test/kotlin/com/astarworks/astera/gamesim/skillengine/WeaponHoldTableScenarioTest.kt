package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Weapon hold table guard (real shipped content): a hold-class status (freeze, shock, root, slow, sinking, stun) is always applied, never
 * gated by `rand()` / `roll()`; strength is tuned by duration. The loader refuses it too (StatusOpSpec); this names the offending row.
 * Each hold family keeps only its table hold kind: couture s2 roots, frost-shard freezes, lightning-bolt shocks.
 */
class WeaponHoldTableScenarioTest : SkillEngineScenario() {
    private val holdStatuses = setOf("freeze", "shock", "root", "slow", "sinking", "stun")
    private val chance = Regex("""\b(rand|roll)\s*\(""")

    private fun composed(): Map<String, Map<String, Any?>> =
        when (val c = SkillComposer(contentDir.resolve("skills")).composeAll()) {
            is Result.Failure -> throw AssertionError("compose: ${c.error}")
            is Result.Success -> c.value.mapValues { it.value.tree }
        }

    /** Every map in the tree that is a `status` op, with its path for the message. */
    private fun statusOps(node: Any?, path: String, out: MutableList<Pair<String, Map<*, *>>>) {
        when (node) {
            is Map<*, *> -> {
                if (node["op"] == "status") out += path to node
                node.forEach { (k, v) -> statusOps(v, "$path/$k", out) }
            }
            is List<*> -> node.forEachIndexed { i, v -> statusOps(v, "$path[$i]", out) }
        }
    }

    private fun opsOf(id: String): List<Pair<String, Map<*, *>>> {
        val out = ArrayList<Pair<String, Map<*, *>>>()
        statusOps(composed().getValue(id), id, out)
        return out
    }

    @Test
    fun noHoldStatusHasAChance() {
        val problems = ArrayList<String>()
        for ((id, tree) in composed()) {
            val ops = ArrayList<Pair<String, Map<*, *>>>()
            statusOps(tree, id, ops)
            for ((path, op) in ops) {
                val status = op["status"]?.toString() ?: continue
                val w = op["when"]?.toString() ?: continue
                if (status in holdStatuses && chance.containsMatchIn(w)) problems += "$path: hold status '$status' gated by chance: $w"
            }
        }
        assertThat(problems).`as`("hold statuses gated by chance").isEmpty()
    }

    @Test
    fun holdFamiliesUseTheirTableKind() {
        fun kinds(id: String) = opsOf(id).mapNotNull { it.second["status"]?.toString() }.filter { it in holdStatuses }.toSet()
        assertThat(kinds("couture-rapier-s2")).containsExactly("root")
        assertThat(kinds("frost-shard")).containsExactly("freeze")
        assertThat(kinds("lightning-bolt")).containsExactly("shock")
        assertThat(kinds("shock-wand-zap")).containsExactly("shock")
        assertThat(kinds("frost-rod-sleet")).containsExactly("freeze")
    }
}
