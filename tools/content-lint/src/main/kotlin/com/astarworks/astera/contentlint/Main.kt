package com.astarworks.astera.contentlint

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.reader
import kotlin.system.exitProcess

/**
 * `tools/content-lint` CLI entrypoint (ADR-0067).
 *
 * Walks every entry in [SchemaMapping.all] and, for each YAML target,
 * validates against the JSON Schema sibling under `content/schemas/`.
 *
 * Exit codes:
 *   - 0 — every file passed (or the target dir is missing / empty, which is
 *         not an error since not every content type has authored examples yet).
 *   - 1 — at least one validation failure, missing schema, or YAML parse error.
 *
 * Wired into the root `./gradlew check` since WP15 (supersedes ADR-0067 §Decision (3)).
 */
fun main(args: Array<String>) {
    val parsed = CliArgs.parse(args)
    val report = LintRunner(parsed).run()
    report.print(System.out)
    val vfx = com.astarworks.astera.core.application.contents.VfxContents()
    val vfxLoad = vfx.bootstrap(parsed.contentDir.resolve("vfx"))
    val particles = com.astarworks.astera.core.application.contents.ParticleContents()
    val particleLoad = particles.bootstrap(parsed.contentDir.resolve("particles"))
    val skills = com.astarworks.astera.combat.application.contents.SkillContents(vfx = vfx, particles = particles)
    val skillLoad = skills.bootstrap(parsed.contentDir.resolve("skills"))
    println("Top 10 skill lifetime cost estimates (unbounded includes unconstrained candidate queries / dependent fan-out):")
    println(com.astarworks.astera.combat.application.contents.SkillCostReport.format(
        com.astarworks.astera.combat.application.contents.SkillCostReport.top(skills, vfx, particles),
    ))
    val runtime = RuntimeDecode.run(parsed.contentDir, listOf(
        "vfx" to vfxLoad, "particles" to particleLoad, "skills" to skillLoad,
    ))
    exitProcess(if (report.failed == 0 && runtime == 0) 0 else 1)
}

// -----------------------------------------------------------------------------
// CLI argument parsing
// -----------------------------------------------------------------------------

internal data class CliArgs(
    val contentDir: Path,
    val schemasDir: Path,
    /**
     * Reserved for future schema warnings. Skill cost estimates remain advisory,
     * including with --strict (T8a does not enforce the cost cap yet).
     */
    @Suppress("unused") val strict: Boolean,
) {
    companion object {
        fun parse(args: Array<String>): CliArgs {
            var contentDir: Path = Paths.get("content")
            var schemasDir: Path = Paths.get("content", "schemas")
            var strict = false
            var i = 0
            while (i < args.size) {
                when (val flag = args[i]) {
                    "--content-dir" -> {
                        contentDir = Paths.get(requireValue(args, i, flag))
                        i += 2
                    }
                    "--schemas-dir" -> {
                        schemasDir = Paths.get(requireValue(args, i, flag))
                        i += 2
                    }
                    "--strict" -> {
                        // Skill cost warnings remain advisory even in strict mode.
                        strict = true
                        i += 1
                    }
                    "--help", "-h" -> {
                        printUsage()
                        exitProcess(0)
                    }
                    else -> {
                        System.err.println("contentLint: unknown argument: $flag")
                        printUsage()
                        exitProcess(2)
                    }
                }
            }
            return CliArgs(contentDir.toAbsolutePath(), schemasDir.toAbsolutePath(), strict)
        }

        private fun requireValue(args: Array<String>, idx: Int, flag: String): String {
            if (idx + 1 >= args.size) {
                System.err.println("contentLint: $flag requires a value")
                exitProcess(2)
            }
            return args[idx + 1]
        }

        private fun printUsage() {
            System.err.println(
                """
                Usage: contentLint [--content-dir <path>] [--schemas-dir <path>] [--strict]

                Validates every content YAML file against its JSON Schema at
                content/schemas/. See ADR-0067 for the schema
                ↔ directory mapping table.
                """.trimIndent(),
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Schema ↔ content directory mapping
// -----------------------------------------------------------------------------

/**
 * The hard-coded mapping from each schema file under content/schemas/ to the
 * content directory (or single file) it governs.
 *
 * Hard-coded for Phase 1 simplicity (per ADR-0067 §Decision (4)). When a new
 * schema is added under `content/schemas/`, add a row here in the same
 * commit; a contentLint failure would otherwise be silent.
 */
internal data class SchemaTarget(
    val schemaFile: String,
    val targetDir: String,
    /** If non-null, validate ONLY this filename under [targetDir]. */
    val singleFile: String? = null,
    /**
     * When true, the YAML root is expected to be a JSON array and the schema
     * describes ONE element. The validator iterates `root[i]` and applies the
     * schema element-wise. Use this for "list-of-entries" content files such
     * as `content/generators/<stage>.yaml` whose schema documents a single
     * entry (per the schema's own description).
     */
    val arrayOfEntries: Boolean = false,
    /** Filenames under [targetDir] that belong to another schema and are skipped here. */
    val excludeFiles: Set<String> = emptySet(),
)

internal object SchemaMapping {
    val all: List<SchemaTarget> = listOf(
        SchemaTarget("chat.schema.json", "chat", singleFile = "chat.yaml"),
        SchemaTarget("skill.schema.json", "skills"),
        SchemaTarget("block.schema.json", "blocks"),
        SchemaTarget("block-durability.schema.json", "balance", singleFile = "block-durability.yaml"),
        SchemaTarget("status-effect.schema.json", "status"),
        SchemaTarget("stage.schema.json", "stages"),
        SchemaTarget("item.schema.json", "items"),
        SchemaTarget("gui.schema.json", "gui"),
        SchemaTarget("norma.schema.json", "norma"),
        SchemaTarget("generator.schema.json", "generators"),
        SchemaTarget("anomaly.schema.json", "anomaly"),
        SchemaTarget("shop.schema.json", "shops", excludeFiles = setOf("match-shop.yaml")),
        // match-arsenal: the in-match ore shop catalog sits beside the lobby shops.
        SchemaTarget("match-shop.schema.json", "shops", singleFile = "match-shop.yaml"),
        // Wave 5f (ADR-0035, RTM #21): particles are per-file definitions
        // mirroring the weapon / skill shape.
        SchemaTarget("particle.schema.json", "particles"),
        // ADR-0220: display choreographies, one definition per file.
        SchemaTarget("vfx.schema.json", "vfx"),
        // ADR-0250: keyframed skin-rig player animations, one per file.
        SchemaTarget("animation.schema.json", "animations"),
        // ADR-0239: generated particle sprite metadata (written by :tools:item-art).
        SchemaTarget("sprite.schema.json", "sprites"),
        // ADR-0274: retextured vanilla particle types (frames generated by :tools:item-art).
        SchemaTarget("particle-skin.schema.json", "particle-skins"),
        // element-matrix is a balance singleton, not a directory of authored
        // entries — validate only the one file the schema describes.
        SchemaTarget("element-matrix.schema.json", "balance", singleFile = "element-matrix.yaml"),
    )
}

// -----------------------------------------------------------------------------
// Validator core
// -----------------------------------------------------------------------------

internal class LintRunner(private val cli: CliArgs) {

    private val factory: JsonSchemaFactory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
    private val jsonMapper = ObjectMapper()

    fun run(): LintReport {
        val results = mutableListOf<FileResult>()
        var missingSchemas = 0

        for (target in SchemaMapping.all) {
            val schemaPath = cli.schemasDir.resolve(target.schemaFile)
            if (!schemaPath.isRegularFile()) {
                System.err.println("contentLint: missing schema $schemaPath — skipping ${target.targetDir}/")
                missingSchemas += 1
                continue
            }
            val schema: JsonSchema = try {
                loadSchema(schemaPath)
            } catch (ex: Exception) {
                results += FileResult(
                    file = schemaPath,
                    errors = listOf("failed to load schema: ${ex.message ?: ex.javaClass.simpleName}"),
                )
                continue
            }

            val targets = collectYamlFiles(target)
            for (yaml in targets) {
                results += validate(yaml, schema, target.arrayOfEntries)
            }
        }

        results += semanticResults()

        return LintReport(results, missingSchemas, skinNotices())
    }

    /**
     * ADR-0274: a reskin is server-wide per vanilla type, so list every skinned type with the particle files that
     * emit it. Informational (never fails the lint).
     */
    private fun skinNotices(): List<String> {
        val skins = yamlFilesIn("particle-skins").mapNotNull { f ->
            val node = runCatching { f.reader().use { YamlToJson.parse(it) } }.getOrNull() ?: return@mapNotNull null
            node.get("type")?.asText()?.let { it to (node.get("channel")?.asText() ?: "?") }
        }
        if (skins.isEmpty()) return emptyList()
        val users = yamlFilesIn("particles").mapNotNull { f ->
            val node = runCatching { f.reader().use { YamlToJson.parse(it) } }.getOrNull() ?: return@mapNotNull null
            node.get("vanilla_particle")?.asText()?.removePrefix("minecraft:")?.let { it to f.name }
        }
        return skins.map { (type, channel) ->
            val using = users.filter { it.first == type }.map { it.second }
            "particle-skin: vanilla '$type' is reskinned as '$channel' for every particle of that type; used by content/particles: " +
                if (using.isEmpty()) "(none)" else using.joinToString(", ")
        }
    }

    /**
     * Runtime-support and cross-reference checks for skills + items (see
     * [SemanticChecks]). Reported as a separate result per file so schema
     * and semantic failures are both visible.
     */
    private fun semanticResults(): List<FileResult> {
        val known = SemanticChecks.KnownIds(
            skills = idsIn("skills"),
            statuses = idsIn("status"),
            particles = idsIn("particles"),
            vfx = idsIn("vfx"),
            sprites = idsIn("sprites"),
            animations = idsIn("animations"),
        )
        val out = mutableListOf<FileResult>()
        for (dir in listOf("skills", "items", "vfx")) {
            for (yaml in yamlFilesIn(dir)) {
                val node = try {
                    yaml.reader().use { YamlToJson.parse(it) }
                } catch (_: Exception) {
                    continue // already reported by the schema pass
                }
                val errors = SemanticChecks.check(node, known) + if (dir == "items") SemanticChecks.checkItemText(node) else emptyList()
                if (errors.isNotEmpty()) out += FileResult(file = yaml, errors = errors)
            }
        }
        return out
    }

    private fun yamlFilesIn(dir: String): List<Path> {
        val d = cli.contentDir.resolve(dir)
        if (!d.isDirectory()) return emptyList()
        return d.listDirectoryEntries()
            .filter { it.isRegularFile() && (it.name.endsWith(".yaml") || it.name.endsWith(".yml")) }
            .sortedBy { it.name }
    }

    private fun idsIn(dir: String): Set<String> = yamlFilesIn(dir).mapNotNull { f ->
        runCatching { f.reader().use { YamlToJson.parse(it) }.get("id")?.asText() }.getOrNull()
    }.toSet()

    private fun loadSchema(path: Path): JsonSchema {
        // Read schema as a raw JSON string; networknt resolves $ref's
        // relative to the schema's URI, so passing the file URI keeps cross-
        // schema references working if we add them later.
        val schemaJson = path.toUri().toURL().openStream().use { stream ->
            jsonMapper.readTree(stream)
        }
        return factory.getSchema(path.toUri(), schemaJson)
    }

    private fun collectYamlFiles(target: SchemaTarget): List<Path> {
        val dir = cli.contentDir.resolve(target.targetDir)
        if (!dir.isDirectory()) return emptyList()
        return when (val single = target.singleFile) {
            null -> dir.listDirectoryEntries()
                .filter { it.isRegularFile() }
                .filter { it.name.endsWith(".yaml") || it.name.endsWith(".yml") }
                .filter { it.name !in target.excludeFiles }
                .sortedBy { it.name }
            else -> listOf(dir.resolve(single)).filter { it.isRegularFile() }
        }
    }

    private fun validate(yamlPath: Path, schema: JsonSchema, arrayOfEntries: Boolean): FileResult {
        val node: JsonNode = try {
            yamlPath.reader().use { YamlToJson.parse(it) }
        } catch (ex: Exception) {
            return FileResult(
                file = yamlPath,
                errors = listOf("YAML parse failed: ${ex.message ?: ex.javaClass.simpleName}"),
            )
        }
        if (node.isNull) {
            return FileResult(
                file = yamlPath,
                errors = listOf("YAML document is empty or contains only comments"),
            )
        }
        val errors = mutableListOf<String>()
        if (arrayOfEntries) {
            if (!node.isArray) {
                errors += "expected a YAML array of entries at the document root, got ${node.nodeType.name.lowercase()}"
            } else {
                for ((idx, element) in node.withIndex()) {
                    val violations = schema.validate(element)
                    for (v in violations) {
                        errors += "[$idx] ${v.message}"
                    }
                }
            }
        } else {
            val violations: Set<ValidationMessage> = schema.validate(node)
            violations.mapTo(errors) { it.message }
        }
        return FileResult(file = yamlPath, errors = errors)
    }
}

// -----------------------------------------------------------------------------
// Report types
// -----------------------------------------------------------------------------

internal data class FileResult(
    val file: Path,
    val errors: List<String>,
) {
    val passed: Boolean get() = errors.isEmpty()
}

internal class LintReport(
    val results: List<FileResult>,
    val missingSchemas: Int,
    val notices: List<String> = emptyList(),
) {
    val passed: Int = results.count { it.passed }
    val failed: Int = results.count { !it.passed } + missingSchemas

    fun print(out: java.io.PrintStream) {
        for (r in results) {
            if (r.passed) {
                out.println("${relativize(r.file)}: OK")
            } else {
                out.println("${relativize(r.file)}: FAIL")
                for (err in r.errors) {
                    out.println("  - $err")
                }
            }
        }
        for (n in notices) out.println(n)
        out.println()
        out.println("contentLint: $passed passed, $failed failed (${results.size} files validated, $missingSchemas schemas missing)")
    }

    private fun relativize(p: Path): String {
        val cwd = Paths.get("").toAbsolutePath()
        return runCatching { cwd.relativize(p).toString() }.getOrDefault(p.toString())
    }
}
