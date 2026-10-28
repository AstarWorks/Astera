package com.astarworks.astera.schemagen

import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * CLI entrypoint for the v3 skill engine schema. A named object (not a top-level `main`) because
 * [SchemaGenMainKt] already owns the package-level `main`.
 *
 * Usage (via Gradle task):
 *   ./gradlew :tools:schema-gen:generateSkillSchema
 *
 * The single argument is the output directory (default `content/schemas`).
 */
object SkillEngineSchemaMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val outputDir = Path.of(args.firstOrNull() ?: "content/schemas")
        outputDir.createDirectories()
        println("Generated: ${SkillEngineSchemaGenerator.generate(outputDir)}")
    }
}
