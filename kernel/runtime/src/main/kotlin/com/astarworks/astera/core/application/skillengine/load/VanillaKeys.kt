package com.astarworks.astera.core.application.skillengine.load

/**
 * The vanilla block / item registry keys of the pinned Paper API, generated into `skillengine/vanilla-{blocks,items}.txt`
 * (generator: `tools/vanilla-catalog/GenerateVanillaKeys.java`), so a bad `block` / `item` of a particle is a load error
 * and not a cast-time drop.
 */
public object VanillaKeys {
    private fun read(name: String): Set<String> =
        (VanillaKeys::class.java.getResourceAsStream("/skillengine/$name") ?: error("missing resource skillengine/$name"))
            .bufferedReader().useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() }.toSet() }

    private val blocks: Set<String> by lazy { read("vanilla-blocks.txt") }
    private val items: Set<String> by lazy { read("vanilla-items.txt") }

    private fun bare(key: String): String? =
        key.removePrefix("minecraft:").takeIf { !it.contains(':') }

    public fun isBlock(key: String): Boolean = bare(key)?.let { it in blocks } ?: false

    public fun isItem(key: String): Boolean = bare(key)?.let { it in items } ?: false
}
