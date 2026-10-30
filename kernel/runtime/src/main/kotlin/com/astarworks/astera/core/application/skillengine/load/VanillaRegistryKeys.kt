package com.astarworks.astera.core.application.skillengine.load

/**
 * [IVanillaKeyCatalog] over the vanilla data report of the Paper server jar, bundled as `block-registry.txt` (id, properties and their
 * legal values) and `item-registry.txt` (item ids); regenerate both with `tools/lobby-gen/scripts/gen-block-registry.py` after
 * bumping `paper`. Mirrors what `Server.createBlockData` accepts: known id, known properties, legal values.
 */
public object VanillaRegistryKeys : IVanillaKeyCatalog {
    private val blocks: Map<String, Map<String, Set<String>>> by lazy {
        resource("/block-registry.txt").lineSequence().filter { it.isNotBlank() }.associate { line ->
            val (id, props) = line.split('\t', limit = 2)
            id to props.split(',').filter { it.isNotEmpty() }.associate { p ->
                val (k, v) = p.split('=', limit = 2)
                k to v.split('|').toSet()
            }
        }
    }

    private val items: Set<String> by lazy { resource("/item-registry.txt").lineSequence().filter { it.isNotBlank() }.toSet() }

    private fun resource(path: String): String =
        checkNotNull(VanillaRegistryKeys::class.java.getResourceAsStream(path)) { "bundled registry $path is missing" }.bufferedReader().use { it.readText() }

    private fun qualified(id: String): String = if (':' in id) id else "minecraft:$id"

    override fun blockProblem(state: String): String? {
        val open = state.indexOf('[')
        if (open >= 0 && !state.endsWith("]")) return "unterminated property list"
        val id = qualified(if (open >= 0) state.substring(0, open) else state)
        val props = blocks[id] ?: return "unknown block id '$id'"
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

    override fun itemProblem(key: String): String? = qualified(key).let { if (it in items) null else "unknown item id '$it'" }
}
