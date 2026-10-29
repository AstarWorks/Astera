package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.compose.PartInliner
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.part.PartAbort
import com.astarworks.astera.core.application.skillengine.part.PartParser
import com.astarworks.astera.core.domain.skillengine.part.Part

/**
 * ADR-0303 9, skill-local `fragments:`: a reusable flat op list of one skill, called from any op list (state `do:`, group `do:`,
 * `on.hit` / `on.miss` / transition `do:`, another fragment) with `{op: use, use: fragment:<name>, with: {...}}`.
 *
 * A fragment's body is `params`, `frames_in` and `do` (no `machine`: it is one op list). It is read as a part with a single final state
 * `main` carrying the `do:` list, so the part machinery ([PartInliner.bind], the op-level call expansion) lowers it unchanged; its part
 * id is `<skill>#<name>`. Load errors: an undeclared `fragment:<name>` and a fragment that reaches itself.
 */
internal object SkillFragments {
    private val NAME = Regex("[a-z][a-z0-9_-]*")
    private val BODY_KEYS = setOf("params", "frames_in", "do")
    private const val MAIN = "main"

    fun read(ctx: GrammarContext, skillId: String, raw: Any?): Map<String, Part> {
        if (raw == null) return emptyMap()
        val m = raw as? Map<*, *> ?: ctx.mismatch("fragments", "map of fragments", raw)
        val out = LinkedHashMap<String, Part>()
        for ((k, v) in m) {
            val name = k as? String ?: ctx.fail("fragments", "fragment name '$k' is not a string")
            val path = "fragments.$name"
            if (!NAME.matches(name)) ctx.fail(path, "fragment name '$name' must match [a-z][a-z0-9_-]*")
            val body = v as? Map<*, *> ?: ctx.mismatch(path, "{params, frames_in, do}", v)
            for (key in body.keys) {
                if (key == "machine") ctx.fail("$path.machine", "a fragment is a flat op list (do:); machine is not allowed")
                if (key !is String || key !in BODY_KEYS) ctx.abort(SkillDecodeError.UnknownKey(ctx.file, path, key.toString()))
            }
            val doList = body["do"] ?: ctx.abort(SkillDecodeError.MissingField(ctx.file, path, "do"))
            if (doList !is List<*>) ctx.mismatch("$path.do", "list of ops", doList)
            val doc = linkedMapOf<String, Any?>(
                "apiVersion" to PartParser.API_VERSION,
                "kind" to "Part",
                "id" to name,
                "machine" to linkedMapOf("start" to MAIN, "states" to linkedMapOf(MAIN to linkedMapOf("final" to true, "do" to doList))),
            )
            body["params"]?.let { doc["params"] = it }
            body["frames_in"]?.let { doc["frames_in"] = it }
            val part = try {
                PartParser.part(doc, path)
            } catch (e: PartAbort) {
                ctx.fail(e.error.path, e.error.message)
            }
            out[name] = part.copy(id = "$skillId#$name")
        }
        return out
    }

    /** Every `use: fragment:<name>` in [machine] and in the fragment bodies [raw] names a declared fragment, and no fragment reaches itself. */
    fun check(ctx: GrammarContext, declared: Map<String, Part>, rawFragments: Any?, machine: Any?) {
        val names = declared.keys
        val listing = if (names.isEmpty()) "none" else names.joinToString()
        val edges = LinkedHashMap<String, List<String>>()
        fun scan(node: Any?, path: String, into: MutableList<String>) {
            when (node) {
                is Map<*, *> -> for ((k, v) in node) {
                    if (k == "use" && v is String && v.startsWith(PartInliner.FRAGMENT_PREFIX)) {
                        val n = v.removePrefix(PartInliner.FRAGMENT_PREFIX)
                        if (n !in names) ctx.fail("$path.use", "use '$v': this skill declares no fragment '$n' (fragments: $listing)")
                        into += n
                    } else scan(v, "$path.$k", into)
                }
                is List<*> -> node.forEachIndexed { i, e -> scan(e, "$path[$i]", into) }
            }
        }
        scan(machine, "machine", ArrayList())
        val bodies = rawFragments as? Map<*, *> ?: return
        for (name in names) {
            val into = ArrayList<String>()
            scan((bodies[name] as Map<*, *>)["do"], "fragments.$name.do", into)
            edges[name] = into
        }
        for (name in names) {
            val chain = reach(name, name, edges, listOf(name), HashSet()) ?: continue
            ctx.fail("fragments.$name", "fragment '$name' calls itself (${chain.joinToString(" -> ")})")
        }
    }

    private fun reach(root: String, at: String, edges: Map<String, List<String>>, chain: List<String>, seen: MutableSet<String>): List<String>? {
        for (next in edges[at].orEmpty()) {
            if (next == root) return chain + root
            if (seen.add(next)) reach(root, next, edges, chain + next, seen)?.let { return it }
        }
        return null
    }
}
