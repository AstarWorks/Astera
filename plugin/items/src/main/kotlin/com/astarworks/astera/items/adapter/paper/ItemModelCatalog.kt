package com.astarworks.astera.items.adapter.paper

import java.nio.file.Files
import java.nio.file.Path

/**
 * Which items have art in the resource pack (ADR-0193): an item id has an `astera:<id>` item definition
 * when `content/models/generated/items/<id>.model.json` exists, the file `:tools:item-art` writes and
 * the pack builder turns into `assets/astera/items/<id>.json`. The materializer puts that key in the
 * `item_model` data component; items without art keep the plain vanilla look of their base material.
 */
public class ItemModelCatalog(private val modelIds: Set<String>) {

    /** The `item_model` key (`astera:<id>`) for [itemId], or null when the pack has no art for it. */
    public fun modelKey(itemId: String): String? = if (itemId in modelIds) "$NAMESPACE:$itemId" else null

    public companion object {
        public const val NAMESPACE: String = "astera"
        private const val GENERATED_MODELS: String = "models/generated/items"
        private const val SUFFIX: String = ".model.json"

        public val NONE: ItemModelCatalog = ItemModelCatalog(emptySet())

        /** Scans the content dir; a missing directory means no art. */
        public fun fromContentDir(contentDir: Path): ItemModelCatalog {
            val dir = contentDir.resolve(GENERATED_MODELS)
            if (!Files.isDirectory(dir)) return NONE
            val ids = Files.list(dir).use { stream ->
                stream.map { it.fileName.toString() }
                    .filter { it.endsWith(SUFFIX) }
                    .map { it.removeSuffix(SUFFIX) }
                    .toList()
            }
            return ItemModelCatalog(ids.toSet())
        }
    }
}
