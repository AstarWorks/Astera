package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillEngineSpecs
import com.astarworks.astera.core.application.skillengine.part.PatchApplier
import com.astarworks.astera.core.application.skillengine.part.PatchOp
import com.astarworks.astera.core.application.skillengine.part.PatchSeg
import com.astarworks.astera.core.application.skillengine.part.PatchVerb
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import java.nio.file.Files
import java.nio.file.Path

/**
 * [allowBalancePatches]: `patchKind: balance` documents are applied only when true (dev flag); otherwise skipped.
 * [presentationOps]: the op names a `skin` patch may touch; default = every registered op spec whose section is PRESENTATION.
 */
data class ComposeOptions(
    val allowBalancePatches: Boolean = false,
    val presentationOps: Set<String> = SkillEngineSpecs.all().filter { it.section == OpSection.PRESENTATION }.mapTo(LinkedHashSet()) { it.name },
)

/**
 * kustomize-like composition of `astera/v3` YAML into generic trees, one [ComposedSkill] per skill id and variant (plus
 * one per `kind: Part` id, see [composedParts]). No typed decoding happens here: the trees go to `SkillDocumentDecoder`.
 *
 * Layout under [root]: `<id>.yaml` (one or more `---` documents) or `<id>/…yaml` (several files). Skill documents with
 * the same id inside one file / directory are strategic-merged in (file, document) order; the same id in two different
 * files / directories is [ComposeError.DuplicateSkill]. A Skill document without `id` takes the file stem / directory name.
 * `kind: Patch` documents (R35: `ops: [{path, $patch: delete|replace|merge, value}]`, applied by [PatchApplier], the only patch
 * path) may live anywhere under [root] and are applied in (file, document) order: no `variant` modifies the base in place
 * (overlay); a `variant` derives `<target>@<variant>` from the overlaid base. A path that does not resolve is
 * [ComposeError.PatchError.UnresolvedPath]. A `skin` patch may touch only presentation ops inside `enter` / `do` / `exit` rows
 * (and add / delete such ops); anything else is [ComposeError.PatchError.ForbiddenPath].
 *
 * The composer is stateful (it caches parsed documents) so [recompose] only redoes the skills a changed file feeds.
 * All methods are synchronized.
 */
class SkillComposer(private val root: Path, private val options: ComposeOptions = ComposeOptions()) {
    private var docsByFile: Map<String, List<Doc>> = emptyMap()
    private var composedByBase: Map<String, List<ComposedSkill>> = emptyMap()

    /** file (relative to root, `/`) -> keys of the composed documents that file feeds (base and variants; parts as `part:<id>`). */
    private var dependencyIndex: Map<String, Set<String>> = emptyMap()

    /** Every composed skill (base and variants) by id. */
    @Synchronized
    fun composed(): Map<String, ComposedSkill> = composedByBase.values.flatten().filterNot { it.isPart }.associateBy { it.id }

    /** Every composed `kind: Part` document by part id. */
    @Synchronized
    fun composedParts(): Map<String, ComposedSkill> = composedByBase.values.flatten().filter { it.isPart }.associateBy { it.id }

    private fun everything(): Map<String, ComposedSkill> = composedByBase.values.flatten().associateBy { it.key }

    /** Keys (skill ids, `part:<id>` for parts) of the composed documents that [file] (relative to root or absolute under root) contributes to. */
    @Synchronized
    fun dependents(file: String): Set<String> = dependencyIndex[relativize(file) ?: file].orEmpty()

    /** Files (relative to root) that feed the skill [baseId] or its variants: skill documents and the patches targeting it. */
    @Synchronized
    fun filesOf(baseId: String): Set<String> =
        docsByFile.filterValues { docs -> docs.any { it.key == baseId } }.keys.toSet()

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
     * Returns the keys (skill ids including variants, `part:<id>` for parts) that were added, removed or whose tree / sources / patch changed.
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
            docs[rel]?.forEach { affected += it.key }
            val fresh = if (Files.isRegularFile(root.resolve(rel))) loadFile(rel, errors) else null
            if (fresh == null) docs.remove(rel) else docs[rel] = fresh
            fresh?.forEach { affected += it.key }
        }
        if (errors.isNotEmpty()) return Result.failure(errors)
        val recomposed = composeBases(docs, affected, errors)
        if (errors.isNotEmpty()) return Result.failure(errors)
        val before = everything()
        val next = LinkedHashMap(composedByBase)
        affected.forEach { next.remove(it) }
        next.putAll(recomposed)
        commit(docs, next)
        val after = everything()
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
        return Result.success(changed)
    }

    private fun commit(docs: Map<String, List<Doc>>, byBase: Map<String, List<ComposedSkill>>) {
        docsByFile = docs
        composedByBase = byBase
        val index = HashMap<String, MutableSet<String>>()
        byBase.values.flatten().forEach { doc -> doc.sources.forEach { index.getOrPut(it) { LinkedHashSet() } += doc.key } }
        dependencyIndex = index
    }

    private fun baseIds(docs: Map<String, List<Doc>>): Set<String> = docs.values.flatten().mapTo(LinkedHashSet()) { it.key }

    private fun composeBases(docs: Map<String, List<Doc>>, ids: Set<String>, errors: MutableList<ComposeError>): Map<String, List<ComposedSkill>> {
        val ordered = docs.entries.sortedBy { it.key }.flatMap { (_, list) -> list.sortedBy { it.index } }
        val out = LinkedHashMap<String, List<ComposedSkill>>()
        for (id in ids) {
            val mine = ordered.filter { it.key == id }
            composeBase(id, mine, errors)?.let { out[id] = it }
        }
        return out
    }

    private fun composeBase(id: String, docs: List<Doc>, errors: MutableList<ComposeError>): List<ComposedSkill>? {
        val skillDocs = docs.filter { it.kind != Doc.Kind.Patch }
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
        if (skillDocs.first().kind == Doc.Kind.Part) return composePart(id.removePrefix("part:"), base, sources, patches, errors, errorsBefore)

        val baseApplied = ArrayList<Doc>()
        for (p in patches) {
            if (p.variant != null) continue
            val merged = applyPatch(base, p, errors) ?: continue
            base = merged
            baseApplied += p
            sources += p.file
        }
        val variants = LinkedHashMap<String, Map<String, Any?>>()
        val variantApplied = LinkedHashMap<String, MutableList<Doc>>()
        val variantSources = LinkedHashMap<String, MutableList<String>>()
        for (p in patches) {
            val v = p.variant ?: continue
            val current = variants[v] ?: base
            val merged = applyPatch(current, p, errors) ?: continue
            variants[v] = merged
            variantApplied.getOrPut(v) { ArrayList() } += p
            variantSources.getOrPut(v) { ArrayList() } += p.file
        }
        if (errors.size > errorsBefore) return null

        val result = ArrayList<ComposedSkill>()
        result += ComposedSkill(id, header(base, "Skill", id), sources.distinct(), metaOf(baseApplied))
        for ((v, tree) in variants) {
            val applied = baseApplied + variantApplied.getValue(v)
            result += ComposedSkill("$id@$v", header(tree, "Skill", "$id@$v"), (sources + variantSources.getValue(v)).distinct(), metaOf(applied))
        }
        return result
    }

    /** A part takes overlay patches only (`target: part:<id>`); a `variant` on a part patch is an error. */
    private fun composePart(
        partId: String,
        start: Map<String, Any?>,
        startSources: List<String>,
        patches: List<Doc>,
        errors: MutableList<ComposeError>,
        errorsBefore: Int,
    ): List<ComposedSkill>? {
        var base = start
        val sources = startSources.toMutableList()
        val applied = ArrayList<Doc>()
        for (p in patches) {
            if (p.variant != null) {
                errors += ComposeError.InvalidDocument(p.file, "variant", "a patch of part '$partId' takes no variant (parts are patched in place)")
                continue
            }
            val merged = applyPatch(base, p, errors) ?: continue
            base = merged
            applied += p
            sources += p.file
        }
        if (errors.size > errorsBefore) return null
        return listOf(ComposedSkill(partId, header(base, "Part", partId), sources.distinct(), metaOf(applied)))
    }

    /** `apiVersion`, `kind` and `id` first, then the rest of the tree: the shape the v3 document decoder reads. */
    private fun header(tree: Map<String, Any?>, kind: String, id: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["apiVersion"] = API_VERSION
        out["kind"] = kind
        out["id"] = id
        tree.forEach { (k, v) -> if (k != "apiVersion" && k != "kind" && k != "id") out[k] = v }
        return out
    }

    /**
     * Patch metadata kept beside the tree: `kind` is the strongest patch kind applied (`balance` > `skin` > `base`
     * for no patch); `touched` is the sorted set of op paths the applied patches name.
     */
    private fun metaOf(applied: List<Doc>): PatchMeta {
        val kind = when {
            applied.any { it.patchKind == "balance" } -> "balance"
            applied.any { it.patchKind == "skin" } -> "skin"
            else -> "base"
        }
        return PatchMeta(kind, applied.flatMap { d -> d.ops.map { it.path } }.distinct().sorted())
    }

    private fun mergeOrFail(base: Map<String, Any?>, doc: Doc, errors: MutableList<ComposeError>): Map<String, Any?>? {
        val (merged, failure) = StrategicMerge.merge(base, doc.body)
        if (failure != null) { errors += failure.toError(doc.file); return null }
        return merged
    }

    /** Applies the ops of patch [p] to [base]: the skin gate on the paths first, then [PatchApplier], then the gate on the result; null (and an error) on failure. */
    private fun applyPatch(base: Map<String, Any?>, p: Doc, errors: MutableList<ComposeError>): Map<String, Any?>? {
        val skin = p.patchKind == "skin"
        if (skin) {
            val bad = p.ops.firstNotNullOfOrNull { skinViolationBefore(it, base) }
            if (bad != null) { errors += ComposeError.PatchError.ForbiddenPath(p.file, bad); return null }
        }
        val merged = when (val r = PatchApplier.apply(base, p.ops)) {
            is Result.Success -> r.value
            is Result.Failure -> { errors += ComposeError.PatchError.UnresolvedPath(p.file, r.error.path, r.error.message); return null }
        }
        if (skin) {
            val bad = p.ops.firstNotNullOfOrNull { skinViolationAfter(it, merged) }
            if (bad != null) { errors += ComposeError.PatchError.ForbiddenPath(p.file, bad); return null }
        }
        return merged
    }

    private val opLists = setOf("enter", "do", "exit")

    /**
     * Skin patches may touch only presentation ops inside states: a path `machine.states.<s>.{enter,do,exit}[<id>]...` whose deepest
     * `[id]` row is a presentation op (or, for a whole-row add / replace / delete, a group of them). Inside such a row only the row's own
     * fields change: a group takes whole-child operations only (`do[g].do[c]`), and an op's `on.<event>` lists are reached through their
     * rows (`do[shot].on.hit[boom]`), never as a whole. Anything else (gameplay or control rows, `for`, `then`, `on`, `final`, `use`, new
     * states, params, anchors) returns its path as the violation. Checked before applying (path shape and the row as it is in [base]) and
     * after (the row as it became), so a path that does not resolve is still reported as the unresolved path it is.
     */
    private fun skinViolationBefore(op: PatchOp, base: Map<String, Any?>): String? {
        val segs = op.segs
        val head = segs.getOrNull(0)
        val states = segs.getOrNull(1)
        val state = segs.getOrNull(2)
        val section = segs.getOrNull(3)
        val ok = head?.name == "machine" && head.id == null && states?.name == "states" && states.id == null &&
            state != null && state.id == null && section != null && section.name in opLists && section.id != null
        if (!ok) return op.path
        val deepest = segs.indices.last { segs[it].id != null }
        val row = rowAt(base, segs, deepest)
        if (deepest == segs.lastIndex) return if (row == null || isPresentation(row)) null else op.path
        if (row == null) return null // unresolved: the applier reports it
        if (isGroup(row) || !isPresentation(row) || segs[deepest + 1].name == "on") return op.path
        return null
    }

    private fun skinViolationAfter(op: PatchOp, merged: Map<String, Any?>): String? {
        val deepest = op.segs.indices.last { op.segs[it].id != null }
        if (op.verb == PatchVerb.DELETE && deepest == op.segs.lastIndex) return null
        val row = rowAt(merged, op.segs, deepest) ?: return null
        return if (isPresentation(row)) null else op.path
    }

    /** The row selected by `segs[0..upTo]` in [tree], or null when any step is missing. */
    private fun rowAt(tree: Map<String, Any?>, segs: List<PatchSeg>, upTo: Int): Map<*, *>? {
        var node: Any? = tree
        for (i in 0..upTo) {
            val m = node as? Map<*, *> ?: return null
            val child = m[segs[i].name]
            node = if (segs[i].id == null) child else (child as? List<*>)?.firstOrNull { (it as? Map<*, *>)?.get("id") == segs[i].id }
        }
        return node as? Map<*, *>
    }

    private fun isGroup(node: Map<*, *>): Boolean = node["op"] == null && node["do"] is List<*>

    /** A presentation op, or a group whose children are all presentation. `use` calls are never presentation. */
    private fun isPresentation(node: Map<*, *>): Boolean {
        val op = node["op"] as? String
        if (op != null) return op in options.presentationOps
        val children = node["do"] as? List<*> ?: return false
        return children.all { it is Map<*, *> && isPresentation(it) }
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
