package com.astarworks.astera.schemagen

import com.astarworks.astera.combat.application.config.ElementMatrixYamlConfig
import com.astarworks.astera.combat.application.config.SkillYamlConfig
import com.astarworks.astera.combat.application.config.StatusEffectYamlConfig
import com.astarworks.astera.core.application.config.BlockDurabilityYamlConfig
import com.astarworks.astera.core.application.config.GuiYamlConfig
import com.astarworks.astera.core.application.config.ItemYamlConfig
import com.astarworks.astera.core.application.config.ParticleYamlConfig
import com.astarworks.astera.economy.application.config.ShopYamlConfig
import com.astarworks.astera.match.application.config.AnomalyScheduleYamlConfig
import com.astarworks.astera.match.application.config.GeneratorYamlConfig
import com.astarworks.astera.match.application.config.StageYamlConfig
import com.astarworks.astera.stats.application.config.NormaYamlConfig
import kotlinx.serialization.serializer
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
    )

    for (entry in schemas) {
        val outputPath = outputDir.resolve("${entry.name}.schema.json")
        SchemaGenerator.generate(entry.serializer, outputPath, entry.schemaId)
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
