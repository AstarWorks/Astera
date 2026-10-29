package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.application.skillengine.compose.ComposeOptions
import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.load.EngineConfigError
import com.astarworks.astera.core.application.skillengine.load.SkillCatalog
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * Hot reload of skill-engine v3 content (ADR-0301, ADR-0303). `make dev-content` sends `astera admin reload all`;
 * the registry calls [reload] for every category, this one included.
 *
 * Flow: file diff since the last reload -> [SkillComposer.recompose] of only the changed files -> [SkillCatalog.reload]
 * of only the affected ids (ids that vanished are removed). Casts in progress hold their `CompiledSkill`, so a reload
 * affects the next cast only. A compose failure leaves composer, catalog and the file snapshot untouched; a skill
 * that fails to decode keeps its previous definition and is retried on the next reload.
 *
 * Every skill is decoded by `SkillDocumentDecoder` inside [catalog] (the composer only merges trees). [engineConfig] is the
 * outcome of loading `content/engine/skill-engine.yaml`: when it is a failure (absent file or key, ADR-0303 11.4) nothing
 * loads and the failure is the report. Null = no gate (game-sim builds its own config).
 *
 * The first reload (or a changed [dir]) composes everything.
 */
public class SkillEngineContents(
    private val catalog: SkillCatalog,
    private val options: ComposeOptions = ComposeOptions(),
    override val categoryName: String = "skill-engine",
    private val engineConfig: Result<EngineConfig, EngineConfigError>? = null,
) : ReloadableContents {

    private var composer: SkillComposer? = null
    private var root: Path? = null
    private var snapshot: Map<String, FileStamp> = emptyMap()
    private var retry: Set<String> = emptySet()

    private var auxiliarySnapshot: Map<String, FileStamp> = emptyMap()
    private var effectIds: Set<String> = emptySet()
    private var demos: Map<String, ComposedSkill> = emptyMap()

    /** Registry + playable VFX examples share the same content root as skills. */
    private fun auxiliary(dir: Path): Map<String, FileStamp> =
        scan(dir.parent.resolve("shader-patches/effects")).mapKeys { "effects/${it.key}" } +
            scan(dir.parent.resolve("vfx")).mapKeys { "vfx/${it.key}" }

    private fun loadEffectIds(dir: Path): Set<String> {
        val effects = dir.parent.resolve("shader-patches/effects")
        if (!Files.isDirectory(effects)) return emptySet()
        return Files.list(effects).use { paths -> paths.sorted().toList() }.mapNotNull { effect ->
            val raw = com.astarworks.astera.core.application.skillengine.load.StrictYaml.newYaml().load<Map<String, Any>>(Files.readString(effect.resolve("effect.yaml")))
            val id = raw["id"] as? String ?: error("$effect: missing effect id")
            require(id == effect.fileName.toString()) { "$effect: effect id differs from directory" }
            val markers = raw["markers"] as? List<*> ?: error("$effect: missing markers")
            if (markers.any { (it as? Map<*, *>)?.get("channel") == "vfx-tint" }) id else null
        }.toSet()
    }

    private fun effectErrors(composed: Collection<ComposedSkill>): List<Pair<String, String>> {
        val errors = mutableListOf<Pair<String, String>>()
        fun visit(id: String, value: Any?) {
            when (value) {
                is Map<*, *> -> {
                    if ("shader_effect" in value) {
                        val effect = value["shader_effect"]
                        if (effect !is String || effect !in effectIds) errors += id to "unknown shader_effect '$effect'"
                        if ((value["op"] != "display" || value["kind"] != "item") || value["item"] == "main_hand" ||
                            (value["item"] as? String)?.startsWith("minecraft:") == true) {
                            errors += id to "shader_effect requires an Astera pack item display (not main_hand/vanilla)"
                        }
                    }
                    value.values.forEach { visit(id, it) }
                }
                is List<*> -> value.forEach { visit(id, it) }
            }
        }
        composed.forEach { visit(it.id, it.tree) }
        return errors
    }

    private data class FileStamp(val mtime: Long, val size: Long)

    @Synchronized
    override fun reload(dir: Path): ContentLoader.LoadReport {
        val abs = dir.toAbsolutePath().normalize()
        (engineConfig as? Result.Failure)?.let { return composeFailure(abs, listOf(it.error.origin to it.error.message)) }
        val aux = auxiliary(abs)
        val loadedEffects = runCatching { loadEffectIds(abs) }
        if (loadedEffects.isFailure) return composeFailure(abs, listOf("shader-effects" to (loadedEffects.exceptionOrNull()?.message ?: "invalid registry")))
        effectIds = loadedEffects.getOrThrow()
        if (aux != auxiliarySnapshot) composer = null
        val current = scan(abs)
        val existing = composer
        return (if (existing == null || root != abs) fullReload(abs, current) else incremental(existing, abs, current)).also {
            if (it.failed == 0) auxiliarySnapshot = aux
        }
    }

    /** What a successful single-skill reload changed. */
    public data class SkillReload(
        /** Ids (including variants and other skills the same files feed) whose composed tree changed. */
        val recomposed: Set<String>,
        /** Ids that were decoded, compiled and swapped into the catalog. */
        val reloaded: Set<String>,
    )

    public sealed interface SkillReloadError {
        /** No full reload has run for this directory yet. */
        public data object NotLoaded : SkillReloadError

        /** No file under the directory belongs to the skill. */
        public data class UnknownSkill(val skillId: String) : SkillReloadError

        /** Compose or decode failed; composer state for compose failures and the old catalog entries are untouched. */
        public data class Rejected(val messages: List<String>) : SkillReloadError
    }

    /**
     * Reloads ONE skill (`/astera skill reload <id>`): only the files that feed [skillId] (its base id when a variant is
     * named) are re-read; only the skills those files feed are recomposed and swapped into the catalog, every other
     * skill keeps its compiled instance. A compose failure changes nothing; a decode / compile failure keeps the old
     * definition of that id and is retried on the next reload. Casts in progress finish on the old definition.
     */
    @Synchronized
    public fun reloadSkill(dir: Path, skillId: String): Result<SkillReload, SkillReloadError> {
        val abs = dir.toAbsolutePath().normalize()
        (engineConfig as? Result.Failure)?.let { return Result.failure(SkillReloadError.Rejected(listOf(it.error.message))) }
        val c = composer
        if (c == null || root != abs) return Result.failure(SkillReloadError.NotLoaded)
        val loadedEffects = runCatching { loadEffectIds(abs) }
        if (loadedEffects.isFailure) return Result.failure(SkillReloadError.Rejected(listOf(loadedEffects.exceptionOrNull()?.message ?: "invalid shader registry")))
        effectIds = loadedEffects.getOrThrow()
        val base = skillId.substringBefore('@')
        val current = scan(abs)
        val files = LinkedHashSet<String>(c.filesOf(base))
        current.keys.filterTo(files) { it.startsWith("$base/") || it == "$base.yaml" || it == "$base.yml" }
        if (files.isEmpty()) return Result.failure(SkillReloadError.UnknownSkill(skillId))
        val recomposed = when (val r = c.recompose(files.map { abs.resolve(it) })) {
            is Result.Success -> r.value
            is Result.Failure -> return Result.failure(SkillReloadError.Rejected(r.error.map { "${it.file}: ${it.path}: ${it.reason}" }))
        }
        val next = HashMap(snapshot)
        for (f in files) { val stamp = current[f]; if (stamp != null) next[f] = stamp else next.remove(f) }
        snapshot = next
        val composed = c.composed()
        val affected = LinkedHashSet<String>(recomposed)
        retry.filterTo(affected) { it.substringBefore('@') == base }
        val toLoad = affected.mapNotNull { resolve(c, composed, it) }
        val invalidEffects = effectErrors(toLoad)
        if (invalidEffects.isNotEmpty()) {
            retry = retry + affected
            return Result.failure(SkillReloadError.Rejected(invalidEffects.map { "${it.first}: ${it.second}" }))
        }
        val report = catalog.reload(toLoad)
        for (id in affected) if (id !in composed && id !in demos) catalog.remove(id)
        retry = (retry - affected) + report.failed.keys
        if (report.failed.isNotEmpty()) {
            return Result.failure(SkillReloadError.Rejected(report.failed.map { (id, e) -> "$id: ${e.path}: ${e.message}" }))
        }
        return Result.success(SkillReload(recomposed, report.loaded.toSet()))
    }

    private fun fullReload(dir: Path, current: Map<String, FileStamp>): ContentLoader.LoadReport {
        val fresh = SkillComposer(dir, options)
        val composed = when (val r = fresh.composeAll()) {
            is Result.Success -> r.value
            is Result.Failure -> return composeFailure(dir, r.error.map { it.file to "${it.path}: ${it.reason}" })
        }
        val examples = when (val r = SkillComposer(dir.parent.resolve("vfx"), options).composeAll()) {
            is Result.Success -> r.value
            is Result.Failure -> return composeFailure(dir, r.error.map { it.file to "${it.path}: ${it.reason}" })
        }
        val duplicates = composed.keys.intersect(examples.keys)
        if (duplicates.isNotEmpty()) return composeFailure(dir, duplicates.map { it to "duplicate skill/VFX demo id" })
        val all = composed + examples
        val invalidEffects = effectErrors(all.values + fresh.composedParts().values)
        if (invalidEffects.isNotEmpty()) return composeFailure(dir, invalidEffects)
        demos = examples
        composer = fresh
        root = dir
        snapshot = current
        val parts = fresh.composedParts()
        val report = catalog.reload(all.values + parts.values)
        for (gone in catalog.ids() - all.keys - parts.keys.map { "part:$it" }.toSet()) catalog.remove(gone)
        retry = report.failed.keys
        return finish(dir, report.loaded.size, report.failed.map { (id, e) -> id to "${e.path}: ${e.message}" })
    }

    private fun incremental(c: SkillComposer, dir: Path, current: Map<String, FileStamp>): ContentLoader.LoadReport {
        val changed = (current.keys + snapshot.keys).filter { current[it] != snapshot[it] }.map { dir.resolve(it) }
        if (changed.isEmpty() && retry.isEmpty()) return ContentLoader.LoadReport(loaded = 0, failed = 0)
        val affected = LinkedHashSet<String>(retry)
        if (changed.isNotEmpty()) {
            when (val r = c.recompose(changed)) {
                is Result.Success -> affected += r.value
                is Result.Failure -> return composeFailure(dir, r.error.map { it.file to "${it.path}: ${it.reason}" })
            }
            snapshot = current
        }
        val composed = c.composed()
        val toLoad = affected.mapNotNull { resolve(c, composed, it) }
        val invalidEffects = effectErrors(toLoad)
        val duplicates = composed.keys.intersect(demos.keys)
        if (invalidEffects.isNotEmpty() || duplicates.isNotEmpty()) {
            retry = affected
            return composeFailure(dir, invalidEffects + duplicates.map { it to "duplicate skill/VFX demo id" })
        }
        val report = catalog.reload(toLoad)
        for (id in affected) if (id !in composed && id !in demos) catalog.remove(id)
        retry = report.failed.keys
        return finish(dir, report.loaded.size, report.failed.map { (id, e) -> id to "${e.path}: ${e.message}" })
    }

    /** A `part:<id>` key resolves to the composed part (the catalog recompiles skills by `usedParts`), anything else to a composed skill. */
    private fun resolve(c: SkillComposer, composed: Map<String, ComposedSkill>, key: String): ComposedSkill? =
        composed[key] ?: demos[key] ?: if (key.startsWith("part:")) c.composedParts()[key.removePrefix("part:")] else null

    private fun composeFailure(dir: Path, errors: List<Pair<String, String>>): ContentLoader.LoadReport =
        ContentLoader.LoadReport(
            loaded = 0,
            failed = errors.size,
            failures = errors.map { (file, msg) -> ContentLoader.LoadFailure(dir.resolve(file), msg) },
        )

    private fun finish(dir: Path, loaded: Int, failures: List<Pair<String, String>>): ContentLoader.LoadReport =
        ContentLoader.LoadReport(
            loaded = loaded,
            failed = failures.size,
            failures = failures.map { (id, msg) -> ContentLoader.LoadFailure(dir.resolve("$id.yaml"), msg) },
        )

    private fun scan(dir: Path): Map<String, FileStamp> {
        if (!Files.isDirectory(dir)) return emptyMap()
        return Files.walk(dir).use { s ->
            s.filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".yaml") || it.toString().endsWith(".yml") }
                .toList()
                .associate {
                    dir.relativize(it).toString().replace('\\', '/') to
                        FileStamp(Files.getLastModifiedTime(it).toMillis(), Files.size(it))
                }
        }
    }
}
