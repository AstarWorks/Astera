package com.astarworks.astera.core.domain.item

/**
 * How an item wears (Wave _items-1, ADR-0073).
 *
 * - [maxDurability] — `null` means use the vanilla material's default
 *   (e.g., iron sword = 250). A non-null value overrides it. Persistence is
 *   handled by the adapter layer via Bukkit's `Damageable` API.
 * - [unbreakable] — true sets the vanilla NBT `Unbreakable` flag, also
 *   hiding the durability bar. When `unbreakable = true`, [maxDurability]
 *   is informational only (the bar won't tick down).
 *
 * Astera deliberately does not surface "auto-repair" or "repair cost" here;
 * those live in the Phase 4 economy / shop layer.
 */
public data class DurabilityPolicy(
    val maxDurability: Int? = null,
    val unbreakable: Boolean = false,
) {
    public companion object {
        public val DEFAULT: DurabilityPolicy = DurabilityPolicy()
    }
}
