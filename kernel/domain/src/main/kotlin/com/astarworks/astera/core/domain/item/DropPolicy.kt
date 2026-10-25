package com.astarworks.astera.core.domain.item

/**
 * What happens to an item when its owner dies (Wave _items-1, ADR-0073).
 *
 * The two flags are mutually exclusive — `dropOnDeath = true` and
 * `keepOnDeath = true` is nonsensical (the item can't both drop AND stay in
 * the inventory). The constructor rejects that combination so a YAML typo
 * surfaces as a parse-time failure rather than as confused runtime behavior.
 *
 * The default mirrors vanilla: drop on death, do not keep.
 */
public data class DropPolicy(
    val dropOnDeath: Boolean = true,
    val keepOnDeath: Boolean = false,
) {
    init {
        require(!(dropOnDeath && keepOnDeath)) {
            "DropPolicy: dropOnDeath and keepOnDeath cannot both be true"
        }
    }

    public companion object {
        public val DEFAULT: DropPolicy = DropPolicy()
    }
}
