package com.astarworks.astera.core.application.service.item

import java.nio.file.Files
import java.nio.file.Path

/**
 * Which items have art in the resource pack (ADR-0193): an item id has an `astera:<id>` item definition
 * when `content/models/generated/items/<id>.model.json` exists, the file `:tools:item-art` writes and
 * the pack builder turns into `assets/astera/items/<id>.json`. The materializer puts that key in the
 * `item_model` data component; visual-backed `content/blocks` placement items use pack-generated cube
 * definitions too (ADR-0299). Items without art keep the plain vanilla look of their base material.
 */
public class ItemModelCatalog(
    modelIds: Set<String>,
    private val contentDir: Path? = null,
) {
    @Volatile private var modelIds: Set<String> = modelIds

    /** Re-read the generated model list (F3-9): a pushed `content/models/` takes effect without a restart. */
    public fun rescan() {
        contentDir?.let { modelIds = idsIn(it) }
    }

    /** The `item_model` key (`astera:<id>`) for [itemId], or null when the pack has no art for it. */
    public fun modelKey(itemId: String): String? = if (itemId in modelIds) "$NAMESPACE:$itemId" else null

    public companion object {
        public const val NAMESPACE: String = "astera"
        private const val GENERATED_MODELS: String = "models/generated/items"
        private const val SUFFIX: String = ".model.json"

        public val NONE: ItemModelCatalog = ItemModelCatalog(emptySet())

        /** Scans the content dir; a missing directory means no art. */
        public fun fromContentDir(contentDir: Path): ItemModelCatalog {
            return ItemModelCatalog(idsIn(contentDir), contentDir)
        }

        private fun idsIn(contentDir: Path): Set<String> {
            val dir = contentDir.resolve(GENERATED_MODELS)
            val blocksDir = contentDir.resolve("blocks")
            val blockItems = if (Files.isDirectory(blocksDir)) Files.list(blocksDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".yaml") }.toList().mapNotNull { file ->
                    val doc = org.yaml.snakeyaml.Yaml().load<Map<String, Any?>>(Files.readString(file))
                    if (doc["visual"] != null) doc["placed_from"]?.toString() else null
                }
            }.toSet() else emptySet()
            if (!Files.isDirectory(dir)) return blockItems
            return blockItems + Files.list(dir).use { stream ->
                stream.map { it.fileName.toString() }
                    .filter { it.endsWith(SUFFIX) }
                    .map { it.removeSuffix(SUFFIX) }
                    .toList()
            }.toSet()
        }
    }
}
