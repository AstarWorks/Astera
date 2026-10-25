package com.astarworks.astera.core.domain.shop

/**
 * Stable identifier for a [ShopOffer] within a [ShopSpec].
 *
 * Unique per-shop, not globally. Two shops may both have offer id `iron-sword`
 * and that is fine — the addressing key is the pair `(ShopId, ShopOfferId)`.
 */
@JvmInline
public value class ShopOfferId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "ShopOfferId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
