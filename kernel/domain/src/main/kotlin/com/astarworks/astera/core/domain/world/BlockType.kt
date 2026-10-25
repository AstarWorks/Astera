package com.astarworks.astera.core.domain.world

/**
 * Vendor-neutral block / item-material reference.
 *
 * Use a namespace prefix to stay open for non-vanilla providers:
 * - `minecraft:stone`, `minecraft:iron_block` — vanilla
 * - `oraxen:astera_brick` — Oraxen provider
 * - `astera:siege_core` — a future first-party custom block
 *
 * The adapter layer resolves the key to a concrete material at I/O time.
 */
@JvmInline
public value class BlockType(public val key: String) {
    init {
        require(VALID.matches(key)) {
            "BlockType must be namespace:identifier (lower-case, no spaces): '$key'"
        }
    }

    public val namespace: String get() = key.substringBefore(':')
    public val identifier: String get() = key.substringAfter(':')

    override fun toString(): String = key

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9_]*:[a-z][a-z0-9_]*$")
    }
}
