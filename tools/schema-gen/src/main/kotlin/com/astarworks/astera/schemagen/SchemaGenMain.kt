package com.astarworks.astera.schemagen

import com.astarworks.astera.combat.application.config.ElementMatrixYamlConfig
import com.astarworks.astera.combat.application.config.SkillYamlConfig
import com.astarworks.astera.combat.application.config.StatusEffectYamlConfig
import com.astarworks.astera.core.application.config.BlockDurabilityYamlConfig
import com.astarworks.astera.core.application.config.GuiYamlConfig
import com.astarworks.astera.core.application.config.ItemYamlConfig
import com.astarworks.astera.core.application.config.ParticleSkinYamlConfig
import com.astarworks.astera.core.application.config.ParticleYamlConfig
import com.astarworks.astera.core.application.config.SpriteYamlConfig
import com.astarworks.astera.core.application.config.SkinRigAnimationYamlConfig
import com.astarworks.astera.core.application.config.VfxYamlConfig
import com.astarworks.astera.economy.application.config.ShopYamlConfig
import com.astarworks.astera.match.application.config.AnomalyScheduleYamlConfig
import com.astarworks.astera.match.application.config.GeneratorYamlConfig
import com.astarworks.astera.match.application.config.StageYamlConfig
import com.astarworks.astera.stats.application.config.NormaYamlConfig
import kotlinx.serialization.serializer
import kotlinx.serialization.json.*
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * CLI entrypoint for schema generation.
 *
 * Usage (via Gradle task):
 *   ./gradlew :tools:schema-gen:generateSchemas
 *
 * Or directly:
 *   java -cp <classpath> com.astarworks.astera.schemagen.SchemaGenMainKt <outputDir>
 *
 * The single argument is the path to the output directory (e.g. `content/schemas`).
 * Each schema file is written as `<outputDir>/<name>.schema.json`.
 */
fun main(args: Array<String>) {
    val outputDir = Path.of(args.firstOrNull() ?: "content/schemas")
    outputDir.createDirectories()

    val schemas = listOf(
        SchemaEntry("block", serializer<com.astarworks.astera.core.application.config.block.CustomBlockYamlConfig>(),
            "https://astera.astarworks.com/schemas/block.schema.json"),
        SchemaEntry("chat", serializer<com.astarworks.astera.chat.application.config.ChatConfig>(),
            "https://astera.astarworks.com/schemas/chat.schema.json"),
        // Wave _items-1 (ADR-0073): WeaponYamlConfig was removed; Item is the
        // canonical content type now and carries skill bindings + attributes.
        SchemaEntry(
            name = "skill",
            serializer = serializer<SkillYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/skill.schema.json",
        ),
        SchemaEntry(
            name = "status-effect",
            serializer = serializer<StatusEffectYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/status-effect.schema.json",
        ),
        SchemaEntry(
            name = "item",
            serializer = serializer<ItemYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/item.schema.json",
        ),
        SchemaEntry(
            name = "element-matrix",
            serializer = serializer<ElementMatrixYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/element-matrix.schema.json",
        ),
        SchemaEntry(
            name = "stage",
            serializer = serializer<StageYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/stage.schema.json",
        ),
        SchemaEntry(
            name = "norma",
            serializer = serializer<NormaYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/norma.schema.json",
        ),
        SchemaEntry(
            name = "gui",
            serializer = serializer<GuiYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/gui.schema.json",
        ),
        SchemaEntry(
            name = "shop",
            serializer = serializer<ShopYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/shop.schema.json",
        ),
        SchemaEntry(
            name = "block-durability",
            serializer = serializer<BlockDurabilityYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/block-durability.schema.json",
        ),
        SchemaEntry(
            name = "generator",
            serializer = serializer<GeneratorYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/generator.schema.json",
        ),
        SchemaEntry(
            name = "anomaly",
            serializer = serializer<AnomalyScheduleYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/anomaly.schema.json",
        ),
        SchemaEntry(
            name = "particle",
            serializer = serializer<ParticleYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/particle.schema.json",
        ),
        SchemaEntry(
            name = "particle-skin",
            serializer = serializer<ParticleSkinYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/particle-skin.schema.json",
        ),
        SchemaEntry(
            name = "vfx",
            serializer = serializer<VfxYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/vfx.schema.json",
        ),
        SchemaEntry(
            name = "animation",
            serializer = serializer<SkinRigAnimationYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/animation.schema.json",
        ),
        SchemaEntry(
            name = "sprite",
            serializer = serializer<SpriteYamlConfig>(),
            schemaId = "https://astera.astarworks.com/schemas/sprite.schema.json",
        ),
    )

    for (entry in schemas) {
        val outputPath = outputDir.resolve("${entry.name}.schema.json")
        SchemaGenerator.generate(entry.serializer, outputPath, entry.schemaId)
        if (entry.name == "block") constrainPlacedBlockVisual(outputPath)
        println("Generated: $outputPath")
    }

    println("Done. ${schemas.size} schema(s) written to $outputDir")
}

/** Holds one schema generation job. Typed so [SchemaGenerator.generate] stays generic. */
private data class SchemaEntry<T>(
    val name: String,
    val serializer: kotlinx.serialization.KSerializer<T>,
    val schemaId: String,
)

/** Reserved carrier constraints exceed the structural serialization descriptor (ADR-0300). */
private fun constrainPlacedBlockVisual(path: Path) {
    val root = Json.parseToJsonElement(path.readText()).jsonObject.toMutableMap()
    val properties = root.getValue("properties").jsonObject.toMutableMap()
    properties["visual"] = Json.parseToJsonElement("""
        {"type":"object","additionalProperties":false,"required":["base","state"],"properties":{
          "base":{"const":"note_block"},"state":{"type":"object","additionalProperties":false,
          "required":["instrument","note","powered"],"properties":{"instrument":{"const":"harp"},
          "note":{"enum":["1","2","3","4","5","6","7","8","9"]},"powered":{"const":"false"}}}}}
    """.trimIndent())
    root["properties"] = JsonObject(properties)
    root["allOf"] = Json.parseToJsonElement("""
        [{"if":{"required":["placed_from"]},"then":{"required":["visual","texture"]}}]
    """.trimIndent())
    val definitions = root.getValue("definitions").jsonObject.filterKeys { !it.endsWith(".BlockVisualYamlConfig") }
    root["definitions"] = JsonObject(definitions)
    path.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), JsonObject(root)))
}
