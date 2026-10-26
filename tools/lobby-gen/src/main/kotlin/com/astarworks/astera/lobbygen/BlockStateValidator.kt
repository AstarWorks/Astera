package com.astarworks.astera.lobbygen

/** One placed block of a generated plan, for error reporting. */
data class BlockUse(val x: Int, val y: Int, val z: Int, val state: String)

/**
 * Server-free check of generator block-data strings against the real block registry of our Paper/MC version
 * (`block-registry.txt`, produced from the server jar's data report by `scripts/gen-block-registry.py`).
 * Mirrors what `Server.createBlockData` accepts: known id, known properties, legal values.
 */
object BlockStateValidator {
    private val registry: Map<String, Map<String, Set<String>>> by lazy {
        val text = BlockStateValidator::class.java.getResourceAsStream("/block-registry.txt")!!.bufferedReader().readText()
        text.lineSequence().filter { it.isNotBlank() }.associate { line ->
            val (id, props) = line.split('\t', limit = 2)
            id to props.split(',').filter { it.isNotEmpty() }.associate { p ->
                val (k, v) = p.split('=', limit = 2)
                k to v.split('|').toSet()
            }
        }
    }

    /** `null` when [state] is a valid block-data string, otherwise the reason. */
    fun problem(state: String): String? {
        val open = state.indexOf('[')
        if (open >= 0 && !state.endsWith("]")) return "unterminated property list"
        val rawId = if (open >= 0) state.substring(0, open) else state
        val id = if (':' in rawId) rawId else "minecraft:$rawId"
        val props = registry[id] ?: return "unknown block id '$id'"
        if (open < 0) return null
        val body = state.substring(open + 1, state.length - 1)
        if (body.isEmpty()) return null
        for (pair in body.split(',')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) return "malformed property '$pair'"
            val key = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            val allowed = props[key] ?: return "'$id' has no property '$key' (has: ${props.keys.joinToString().ifEmpty { "none" }})"
            if (value !in allowed) return "'$id' property '$key' has no value '$value' (allowed: ${allowed.joinToString("|")})"
        }
        return null
    }

    /** One line per distinct invalid string, naming [label], the string, the reason, the first position and the count. */
    fun check(label: String, blocks: Iterable<BlockUse>): List<String> {
        val first = LinkedHashMap<String, BlockUse>()
        val count = HashMap<String, Int>()
        val reasons = HashMap<String, String>()
        for (use in blocks) {
            val reason = reasons.getOrPut(use.state) { problem(use.state) ?: "" }
            if (reason.isEmpty()) continue
            first.putIfAbsent(use.state, use)
            count.merge(use.state, 1, Int::plus)
        }
        return first.map { (state, use) ->
            "$label: invalid block '$state' at (${use.x}, ${use.y}, ${use.z}) x${count[state]}: ${reasons[state]}"
        }
    }
}
