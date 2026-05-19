package com.astarworks.astera.items.testfixtures

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec

/**
 * Test-only in-memory [IItemRegistry] for the Items plugin (Wave _items-1).
 *
 * Lives in this plugin's `src/test/` rather than `:plugin:test-fixtures`
 * because the fixtures module is Core-only (sibling-isolation matrix) and
 * `ItemSpec` is a Core-domain type that's now exercised at the item-plugin
 * boundary. Once Worker 1's `ItemContents` lands, production code uses that
 * concrete; tests stay on this minimal in-memory shim.
 */
public class InMemoryItemRegistry : IItemRegistry {
    private val store: MutableMap<ItemId, ItemSpec> = mutableMapOf()

    public fun register(spec: ItemSpec) {
        store[spec.id] = spec
    }

    override fun find(id: ItemId): ItemSpec? = store[id]

    override fun all(): Collection<ItemSpec> = store.values.toList()
}
