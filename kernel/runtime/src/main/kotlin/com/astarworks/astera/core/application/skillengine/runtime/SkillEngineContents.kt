package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.application.skillengine.compose.ComposeOptions
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.load.SkillCatalog
import com.astarworks.astera.core.domain.Result
import java.nio.file.Files
import java.nio.file.Path
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill

/**
 * Hot reload of skill-engine v2 content (ADR-0301, design 7). `make dev-content` sends `astera admin reload all`;
 * the registry calls [reload] for every category, this one included.
 *
 * Flow: file diff since the last reload -> [SkillComposer.recompose] of only the changed files -> [SkillCatalog.reload]
 * of only the affected ids (ids that vanished are removed). Casts in progress hold their `CompiledSkill`, so a reload
 * affects the next cast only. A compose failure leaves composer, catalog and the file snapshot untouched; a skill
 * that fails to decode keeps its previous definition and is retried on the next reload.
 *
 * The first reload (or a changed [dir]) composes everything.
 */
public class SkillEngineContents(
    private val catalog: SkillCatalog,
    private val options: ComposeOptions = ComposeOptions(),
    override val categoryName: String = "skill-engine",
) : ReloadableContents {

    private var composer: SkillComposer? = null
    private var root: Path? = null
    private var snapshot: Map<String, FileStamp> = emptyMap()
    private var retry: Set<String> = emptySet()
    private var auxiliarySnapshot: Map<String, FileStamp> = emptyMap()
    private var effectIds: Set<String> = emptySet()
    private var demoIds: Set<String> = emptySet()

    /** Registry + playable VFX examples share the same content root as skills. */
    private fun auxiliary(dir: Path): Map<String, FileStamp> =
        scan(dir.parent.resolve("shader-patches/effects")).mapKeys { "effects/${it.key}" } +
            scan(dir.parent.resolve("vfx")).mapKeys { "vfx/${it.key}" }

    private fun loadEffectIds(dir: Path): Set<String> {
        val effects = dir.parent.resolve("shader-patches/effects")
        if (!Files.isDirectory(effects)) return emptySet()
        return Files.list(effects).use { paths -> paths.sorted().toList() }.mapNotNull { effect ->
            val raw = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false })).load<Map<String, Any>>(Files.readString(effect.resolve("effect.yaml")))
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
                        if (value["op"] != "mesh" || value["item"] == "main_hand" ||
                            (value["item"] as? String)?.startsWith("minecraft:") == true) {
                            errors += id to "shader_effect requires an Astera pack mesh (not main_hand/vanilla)"
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
        val invalidEffects = effectErrors(all.values)
        if (invalidEffects.isNotEmpty()) return composeFailure(dir, invalidEffects)
        demoIds = examples.keys
        composer = fresh
        root = dir
        snapshot = current
        val report = catalog.reload(all.values.toList())
        for (gone in catalog.ids() - all.keys) catalog.remove(gone)
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
        val duplicates = composed.keys.intersect(demoIds)
        if (duplicates.isNotEmpty()) {
            retry = affected
            return composeFailure(dir, duplicates.map { it to "duplicate skill/VFX demo id" })
        }
        val toLoad = affected.mapNotNull { composed[it] }
        val invalidEffects = effectErrors(toLoad)
        if (invalidEffects.isNotEmpty()) {
            retry = affected
            return composeFailure(dir, invalidEffects)
        }
        val report = catalog.reload(toLoad)
        for (id in affected) if (id !in composed) catalog.remove(id)
        retry = report.failed.keys
        return finish(dir, report.loaded.size, report.failed.map { (id, e) -> id to "${e.path}: ${e.message}" })
    }

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
