package com.astarworks.astera.core.domain.shop

/**
 * Stable identifier for a [ShopSpec].
 *
 * Naming rule: lowercase kebab-case (`example-shop`, `siege-merchant`,
 * `gem-store`). Enforced at construction so authoring typos surface
 * immediately instead of as silent "shop not found".
 */
@JvmInline
public value class ShopId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "ShopId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
