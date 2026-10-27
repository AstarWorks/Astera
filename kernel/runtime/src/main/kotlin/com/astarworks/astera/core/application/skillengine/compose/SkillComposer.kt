package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.Result
import java.nio.file.Files
import java.nio.file.Path

/** [allowBalancePatches]: `patchKind: balance` documents are applied only when true (dev flag); otherwise skipped. */
data class ComposeOptions(val allowBalancePatches: Boolean = false)

/**
 * kustomize-like composition of `astera/v2` YAML into generic trees, one [ComposedSkill] per skill id and variant.
 * No typed decoding happens here.
 *
 * Layout under [root]: `<id>.yaml` (one or more `---` documents) or `<id>/…yaml` (several files). Skill documents with
 * the same id inside one file / directory are strategic-merged in (file, document) order; the same id in two different
 * files / directories is [ComposeError.DuplicateSkill]. A Skill document without `id` takes the file stem / directory name.
 * `kind: Patch` documents may live anywhere under [root] and are applied in (file, document) order:
 * no `variant` modifies the base in place (overlay); a `variant` derives `<target>@<variant>` from the overlaid base.
 *
 * The composer is stateful (it caches parsed documents) so [recompose] only redoes the skills a changed file feeds.
 * All methods are synchronized.
 */
class SkillComposer(private val root: Path, private val options: ComposeOptions = ComposeOptions()) {
    private var docsByFile: Map<String, List<Doc>> = emptyMap()
    private var composedByBase: Map<String, List<ComposedSkill>> = emptyMap()

    /** file (relative to root, `/`) -> ids of the composed skills that file feeds (base and variants). */
    private var dependencyIndex: Map<String, Set<String>> = emptyMap()

    @Synchronized
    fun composed(): Map<String, ComposedSkill> = composedByBase.values.flatten().associateBy { it.id }

    /** Ids of the composed skills that [file] (relative to root or absolute under root) contributes to. */
    @Synchronized
    fun dependents(file: String): Set<String> = dependencyIndex[relativize(file) ?: file].orEmpty()

    /** Reads everything under the root, composes it and replaces the cached state. State is untouched on failure. */
    @Synchronized
    fun composeAll(): Result<Map<String, ComposedSkill>, List<ComposeError>> {
        val errors = ArrayList<ComposeError>()
        val docs = LinkedHashMap<String, List<Doc>>()
        scanFiles().forEach { rel -> loadFile(rel, errors)?.let { docs[rel] = it } }
        if (errors.isNotEmpty()) return Result.failure(errors)
        val byBase = composeBases(docs, baseIds(docs), errors)
        if (errors.isNotEmpty()) return Result.failure(errors)
        commit(docs, byBase)
        return Result.success(composed())
    }

    /**
     * Re-reads [changedFiles] (absolute, or relative to root; deleted files count) and recomposes only the skills they
     * feed, found through the file -> skill dependency index plus the ids the new file content declares.
     * Returns the ids (including variants) that were added, removed or whose tree / sources changed.
     * Atomic: on failure nothing changes and the errors are returned.
     */
    @Synchronized
    fun recompose(changedFiles: Collection<Path>): Result<Set<String>, List<ComposeError>> {
        val errors = ArrayList<ComposeError>()
        val docs = LinkedHashMap(docsByFile)
        val affected = LinkedHashSet<String>()
        for (path in changedFiles) {
            val rel = relativize(path.toString()) ?: continue
            if (!rel.endsWith(".yaml") && !rel.endsWith(".yml")) continue
            docs[rel]?.forEach { affected += it.baseId }
            val fresh = if (Files.isRegularFile(root.resolve(rel))) loadFile(rel, errors) else null
            if (fresh == null) docs.remove(rel) else docs[rel] = fresh
            fresh?.forEach { affected += it.baseId }
        }
        if (errors.isNotEmpty()) return Result.failure(errors)
        val recomposed = composeBases(docs, affected, errors)
        if (errors.isNotEmpty()) return Result.failure(errors)
        val before = composed()
        val next = LinkedHashMap(composedByBase)
        affected.forEach { next.remove(it) }
        next.putAll(recomposed)
        commit(docs, next)
        val after = composed()
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
        return Result.success(changed)
    }

    private fun commit(docs: Map<String, List<Doc>>, byBase: Map<String, List<ComposedSkill>>) {
        docsByFile = docs
        composedByBase = byBase
        val index = HashMap<String, MutableSet<String>>()
        byBase.values.flatten().forEach { skill -> skill.sources.forEach { index.getOrPut(it) { LinkedHashSet() } += skill.id } }
        dependencyIndex = index
    }

    private fun baseIds(docs: Map<String, List<Doc>>): Set<String> = docs.values.flatten().mapTo(LinkedHashSet()) { it.baseId }

    private fun composeBases(docs: Map<String, List<Doc>>, ids: Set<String>, errors: MutableList<ComposeError>): Map<String, List<ComposedSkill>> {
        val ordered = docs.entries.sortedBy { it.key }.flatMap { (_, list) -> list.sortedBy { it.index } }
        val out = LinkedHashMap<String, List<ComposedSkill>>()
        for (id in ids) {
            val mine = ordered.filter { it.baseId == id }
            composeBase(id, mine, errors)?.let { out[id] = it }
        }
        return out
    }

    private fun composeBase(id: String, docs: List<Doc>, errors: MutableList<ComposeError>): List<ComposedSkill>? {
        val skillDocs = docs.filter { it.kind == Doc.Kind.Skill }
        val patches = docs.filter { it.kind == Doc.Kind.Patch && (options.allowBalancePatches || it.patchKind != "balance") }
        val errorsBefore = errors.size
        if (skillDocs.isEmpty()) {
            docs.filter { it.kind == Doc.Kind.Patch }.forEach {
                errors += ComposeError.PatchError.TargetNotFound(it.file, "target", "no kind: Skill document with id '$id'")
            }
            return null
        }
        skillDocs.filter { it.group != skillDocs.first().group }.forEach {
            errors += ComposeError.DuplicateSkill(
                it.file, "id", "skill '$id' is also defined in '${skillDocs.first().file}'; merge only happens inside one file or directory",
            )
        }
        if (errors.size > errorsBefore) return null

        var base: Map<String, Any?> = emptyMap()
        for (d in skillDocs) base = mergeOrFail(base, d, errors) ?: return null
        val sources = skillDocs.map { it.file }.toMutableList()

        for (p in patches) {
            if (p.variant != null) continue
            if (!checkAllowed(p, errors)) continue
            run {
                base = mergeOrFail(base, p, errors) ?: continue
                sources += p.file
            }
        }
        val variants = LinkedHashMap<String, Map<String, Any?>>()
        val variantSources = LinkedHashMap<String, MutableList<String>>()
        for (p in patches) {
            val v = p.variant ?: continue
            if (!checkAllowed(p, errors)) continue
            val current = variants[v] ?: base
            variants[v] = mergeOrFail(current, p, errors) ?: continue
            variantSources.getOrPut(v) { ArrayList() } += p.file
        }
        if (errors.size > errorsBefore) return null

        val result = ArrayList<ComposedSkill>()
        result += ComposedSkill(id, withId(base, id), sources.distinct())
        for ((v, tree) in variants) result += ComposedSkill("$id@$v", withId(tree, "$id@$v"), (sources + variantSources.getValue(v)).distinct())
        return result
    }

    private fun withId(tree: Map<String, Any?>, id: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["id"] = id
        tree.forEach { (k, v) -> if (k != "id") out[k] = v }
        return out
    }

    private fun mergeOrFail(base: Map<String, Any?>, doc: Doc, errors: MutableList<ComposeError>): Map<String, Any?>? {
        val (merged, failure) = StrategicMerge.merge(base, doc.body)
        if (failure != null) { errors += failure.toError(doc.file); return null }
        return merged
    }

    /** Skin patches may touch only `presentation.*` (everything but the meta keys is the patch body). */
    private fun checkAllowed(p: Doc, errors: MutableList<ComposeError>): Boolean {
        if (p.patchKind != "skin") return true
        val bad = p.body.entries.firstOrNull { it.key != "presentation" } ?: return true
        errors += ComposeError.PatchError.ForbiddenPath(p.file, firstLeafPath(bad.key, bad.value))
        return false
    }

    private fun firstLeafPath(path: String, v: Any?): String = when {
        v is Map<*, *> && v.keys.any { it != StrategicMerge.DIRECTIVE } -> {
            val e = v.entries.first { it.key != StrategicMerge.DIRECTIVE }
            firstLeafPath("$path.${e.key}", e.value)
        }
        v is List<*> && v.firstOrNull() is Map<*, *> -> {
            val m = v.first() as Map<*, *>
            val id = m["id"]?.toString() ?: "0"
            val e = m.entries.firstOrNull { it.key != "id" && it.key != StrategicMerge.DIRECTIVE }
            if (e == null) "$path[$id]" else firstLeafPath("$path[$id].${e.key}", e.value)
        }
        else -> path
    }

    private fun scanFiles(): List<String> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .filter { it.endsWith(".yaml") || it.endsWith(".yml") }
                .sorted()
                .toList()
        }
    }

    private fun relativize(file: String): String? {
        val p = Path.of(file)
        val abs = if (p.isAbsolute) p.normalize() else root.resolve(p).normalize()
        val rootAbs = root.toAbsolutePath().normalize()
        val target = if (abs.isAbsolute) abs else abs.toAbsolutePath()
        if (!target.startsWith(rootAbs)) return null
        return rootAbs.relativize(target).toString().replace('\\', '/')
    }

    private fun groupOf(rel: String): String =
        if ('/' in rel) rel.substringBefore('/') else rel.substringBeforeLast('.')

    private fun loadFile(rel: String, errors: MutableList<ComposeError>): List<Doc>? {
        val text = runCatching { Files.readString(root.resolve(rel)) }.getOrElse {
            errors += ComposeError.ParseError(rel, "", "cannot read file: ${it.message}")
            return null
        }
        val (docs, errs) = YamlDocs.parse(rel, groupOf(rel), text)
        errors += errs
        return docs.takeIf { it.isNotEmpty() }
    }
}
