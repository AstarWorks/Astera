package com.astarworks.astera.core.domain.inventory

/**
 * Stable identifier for a pre-filled loadout / kit referenced by
 * [com.astarworks.astera.core.application.port.outbound.inventory.IBulkInventoryOps.issueKit]
 * (Wave 5world-events, ADR-0111).
 *
 * Kits themselves are *not* defined in Core — the Match plugin (Wave 5e
 * `PreFillKit`) and any Phase 3+ admin-overlay tooling own the actual
 * loadout shape. Core only ships the id type so the inventory port can take
 * a vendor-neutral handle. Adapter / use-case wiring resolves the id against
 * whichever registry knows the kit at runtime.
 *
 * Naming rule: namespaced lower-kebab-case (`astera:pvp-starter`,
 * `astera:annihilation-class-mage`). Validated at construction so YAML typos
 * surface immediately.
 */
@JvmInline
public value class KitId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "KitId must be namespaced lower-kebab-case (e.g., astera:pvp-starter): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9_]*:[a-z][a-z0-9-]*$")
    }
}
