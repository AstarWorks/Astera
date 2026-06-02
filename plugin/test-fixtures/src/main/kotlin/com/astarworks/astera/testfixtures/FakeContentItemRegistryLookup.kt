package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.item.IContentItemRegistryLookup
import java.util.concurrent.ConcurrentHashMap

/**
 * Hand-controlled [IContentItemRegistryLookup] for tests
 * (Wave 6c §C, ADR-0160).
 *
 * Tests register `("astera:example-sword" → "minecraft:diamond_sword")`
 * pairs via [register] and assert the
 * [com.astarworks.astera.core.adapter.paper.inventory
 * .PaperInventorySnapshotSerializer] decode path picks up the right
 * material key.
 *
 * Misses (unregistered ids) follow the port contract — `null`, no
 * exception. Thread-safe via [ConcurrentHashMap] so scenario harnesses
 * can share one instance across concurrent decode calls.
 */
public class FakeContentItemRegistryLookup : IContentItemRegistryLookup {

    private val store: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    /**
     * Register a `rawItemId → materialKey` mapping. Subsequent
     * [bukkitMaterialKeyFor] calls for the same [rawItemId] return
     * [materialKey].
     */
    public fun register(rawItemId: String, materialKey: String) {
        store[rawItemId] = materialKey
    }

    /** Drop every registered mapping. Used between test cases. */
    public fun clear() {
        store.clear()
    }

    override fun bukkitMaterialKeyFor(rawItemId: String): String? = store[rawItemId]
}
