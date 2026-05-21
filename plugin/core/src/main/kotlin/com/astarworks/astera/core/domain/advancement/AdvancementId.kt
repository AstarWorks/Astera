package com.astarworks.astera.core.domain.advancement

/**
 * Stable identifier for a [CustomAdvancement] (Wave 5p, ADR-0088).
 *
 * Replaces Vanilla Minecraft's advancement system end-to-end: Astera ships
 * its own progression UI, criteria evaluation, and reward dispatch so the
 * same advancement tree works across cross-server proxy hops and is stored
 * in our own Postgres tables (no NBT round-trip with Mojang's schema).
 *
 * Naming rule: lowercase kebab-case (`first-kill`, `master-of-blades`).
 */
@JvmInline
public value class AdvancementId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "AdvancementId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
