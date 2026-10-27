package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.application.skillengine.compose.ComposeOptions
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.load.SkillCatalog
import com.astarworks.astera.core.domain.Result
import java.nio.file.Files
import java.nio.file.Path

/**
 * Hot reload of skill-engine v2 content (ADR-0299, design 7). `make dev-content` sends `astera admin reload all`;
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

    private data class FileStamp(val mtime: Long, val size: Long)

    @Synchronized
    override fun reload(dir: Path): ContentLoader.LoadReport {
        val abs = dir.toAbsolutePath().normalize()
        val current = scan(abs)
        val existing = composer
        return if (existing == null || root != abs) fullReload(abs, current) else incremental(existing, abs, current)
    }

    private fun fullReload(dir: Path, current: Map<String, FileStamp>): ContentLoader.LoadReport {
        val fresh = SkillComposer(dir, options)
        val composed = when (val r = fresh.composeAll()) {
            is Result.Success -> r.value
            is Result.Failure -> return composeFailure(dir, r.error.map { it.file to "${it.path}: ${it.reason}" })
        }
        composer = fresh
        root = dir
        snapshot = current
        val report = catalog.reload(composed.values.toList())
        for (gone in catalog.ids() - composed.keys) catalog.remove(gone)
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
        val toLoad = affected.mapNotNull { composed[it] }
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
