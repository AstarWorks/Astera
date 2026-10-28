package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillEngineSpecs
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
 * `kind: Patch` documents may live anywhere under [root] and are applied in (file, document) order:
 * no `variant` modifies the base in place (overlay); a `variant` derives `<target>@<variant>` from the overlaid base.
 * Lists of maps merge by `id` (`machine.states.<name>.do[<id>]`), a state map merges by state name. A `skin` patch may
 * touch only the fields of presentation ops inside `enter` / `do` / `exit` lists (and add / delete such ops); anything
 * else is [ComposeError.PatchError.ForbiddenPath].
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
            val merged = mergeOrFail(base, p, errors) ?: continue
            if (!checkAllowed(p, base, merged, errors)) continue
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
            val merged = mergeOrFail(current, p, errors) ?: continue
            if (!checkAllowed(p, current, merged, errors)) continue
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
            val merged = mergeOrFail(base, p, errors) ?: continue
            if (!checkAllowed(p, base, merged, errors, isPart = true)) continue
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
     * for no patch); `touched` is the sorted set of dotted paths the applied patch bodies name.
     */
    private fun metaOf(applied: List<Doc>): PatchMeta {
        val kind = when {
            applied.any { it.patchKind == "balance" } -> "balance"
            applied.any { it.patchKind == "skin" } -> "skin"
            else -> "base"
        }
        return PatchMeta(kind, applied.flatMap { touchedPaths(it.body, "") }.distinct().sorted())
    }

    /** Dotted paths named by a patch body; a delete / replace directive records the path of its own entry. */
    private fun touchedPaths(body: Map<*, *>, prefix: String): List<String> {
        val out = ArrayList<String>()
        for ((k, v) in body) {
            val key = k as? String ?: continue
            if (key == StrategicMerge.DIRECTIVE) continue
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            when {
                v is Map<*, *> && v[StrategicMerge.DIRECTIVE] != null -> out += path
                v is Map<*, *> -> out += touchedPaths(v, path)
                v is List<*> && v.any { it is Map<*, *> } -> v.forEach { e ->
                    val m = e as? Map<*, *>
                    val entryId = m?.get("id") as? String
                    when {
                        m == null || entryId == null -> out += path
                        m[StrategicMerge.DIRECTIVE] != null -> out += "$path[$entryId]"
                        else -> out += touchedPaths(m.filterKeys { it != "id" }, "$path[$entryId]")
                    }
                }
                else -> out += path
            }
        }
        return out
    }

    private fun mergeOrFail(base: Map<String, Any?>, doc: Doc, errors: MutableList<ComposeError>): Map<String, Any?>? {
        val (merged, failure) = StrategicMerge.merge(base, doc.body)
        if (failure != null) { errors += failure.toError(doc.file); return null }
        return merged
    }

    private val opLists = listOf("enter", "do", "exit")

    /**
     * Skin patches may touch only presentation ops inside states: `machine.states.<s>.{enter,do,exit}[<id>]` whose
     * base and merged op are both in [ComposeOptions.presentationOps], and groups made only of such ops. Anything
     * else (gameplay or control ops, `for`, `then`, `on`, `final`, new states) is a [ComposeError.PatchError.ForbiddenPath].
     */
    private fun checkAllowed(p: Doc, base: Map<String, Any?>, merged: Map<String, Any?>, errors: MutableList<ComposeError>, isPart: Boolean = false): Boolean {
        if (p.patchKind != "skin") return true
        val bad = skinViolation(p.body, base, merged, isPart)
        if (bad == null) return true
        errors += ComposeError.PatchError.ForbiddenPath(p.file, bad)
        return false
    }

    private fun skinViolation(body: Map<*, *>, base: Map<*, *>, merged: Map<*, *>, isPart: Boolean): String? {
        val allowedTop = if (isPart) setOf("machine") else setOf("machine", "presentation")
        body.keys.firstOrNull { it !in allowedTop }?.let { return firstLeafPath(it.toString(), body[it]) }
        if ("machine" in body) statesViolation(body["machine"], base["machine"], merged["machine"], "machine")?.let { return it }
        if ("presentation" in body) presentationViolation(body["presentation"], base["presentation"], merged["presentation"])?.let { return it }
        return null
    }

    /** `presentation:` may only carry `timeline`, an op list patched like a state list. */
    private fun presentationViolation(patch: Any?, base: Any?, merged: Any?): String? {
        val pm = patch as? Map<*, *> ?: return "presentation"
        pm.keys.firstOrNull { it != "timeline" }?.let { return firstLeafPath("presentation.$it", pm[it]) }
        return opsViolation(pm["timeline"], (base as? Map<*, *>)?.get("timeline"), (merged as? Map<*, *>)?.get("timeline"), "presentation.timeline")
    }

    private fun statesViolation(patch: Any?, base: Any?, merged: Any?, path: String): String? {
        val pm = patch as? Map<*, *> ?: return path
        pm.keys.firstOrNull { it != "states" }?.let { return firstLeafPath("$path.$it", pm[it]) }
        val states = pm["states"] as? Map<*, *> ?: return "$path.states"
        val baseStates = (base as? Map<*, *>)?.get("states") as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val mergedStates = (merged as? Map<*, *>)?.get("states") as? Map<*, *> ?: emptyMap<Any?, Any?>()
        for ((name, st) in states) {
            val sp = "$path.states.$name"
            val sm = st as? Map<*, *> ?: return sp
            if (sm[StrategicMerge.DIRECTIVE] != null || name !in baseStates) return sp
            for ((k, v) in sm) {
                val kp = "$sp.$k"
                when (k) {
                    in opLists -> opsViolation(v, (baseStates[name] as? Map<*, *>)?.get(k), (mergedStates[name] as? Map<*, *>)?.get(k), kp)?.let { return it }
                    "machine" -> statesViolation(v, (baseStates[name] as? Map<*, *>)?.get("machine"), (mergedStates[name] as? Map<*, *>)?.get("machine"), kp)?.let { return it }
                    else -> return firstLeafPath(kp, v)
                }
            }
        }
        return null
    }

    /**
     * One op list (a state list, `presentation.timeline`, a group's `do`, an op's `on.<event>`). Per entry: a delete / replace
     * needs presentation ops on both sides; a new entry must be presentation; a group is patched child by child (only `id` and
     * `do`); any other op may change its own fields only when it is presentation before and after, and its `on.<event>` lists
     * (existing events only) are descended into whatever the op is.
     */
    private fun opsViolation(patch: Any?, base: Any?, merged: Any?, path: String): String? {
        val list = patch as? List<*> ?: return path
        val baseById = (base as? List<*>)?.filterIsInstance<Map<*, *>>()?.associateBy { it["id"] } ?: emptyMap()
        val mergedById = (merged as? List<*>)?.filterIsInstance<Map<*, *>>()?.associateBy { it["id"] } ?: emptyMap()
        for (e in list) {
            val m = e as? Map<*, *> ?: return path
            val id = m["id"] as? String ?: return path
            val ep = "$path[$id]"
            val b = baseById[id]
            val r = mergedById[id]
            if (m[StrategicMerge.DIRECTIVE] != null) {
                val ok = (b == null || isPresentation(b)) && (r == null || isPresentation(r))
                if (!ok) return ep
                continue
            }
            if (r == null) return ep
            if (b == null) {
                if (!isPresentation(r)) return firstLeafPath(ep, m.filterKeys { it != "id" })
                continue
            }
            if (isGroup(b)) {
                m.keys.firstOrNull { it != "id" && it != "do" }?.let { return firstLeafPath("$ep.$it", m[it]) }
                opsViolation(m["do"], b["do"], r["do"], "$ep.do")?.let { return it }
                continue
            }
            val own = m.filterKeys { it != "id" && it != "on" }
            if (own.isNotEmpty() && !(isPresentation(b) && isPresentation(r))) return firstLeafPath(ep, own)
            if ("on" in m) onViolation(m["on"], b["on"], r["on"], "$ep.on")?.let { return it }
        }
        return null
    }

    /** `on.<event>` handler lists of an op: the events must already exist, the lists are patched like any op list. */
    private fun onViolation(patch: Any?, base: Any?, merged: Any?, path: String): String? {
        val pm = patch as? Map<*, *> ?: return path
        val bm = base as? Map<*, *> ?: return path
        val mm = merged as? Map<*, *> ?: emptyMap<Any?, Any?>()
        for ((event, v) in pm) {
            val ep = "$path.$event"
            if (event !in bm || v !is List<*>) return ep
            opsViolation(v, bm[event], mm[event], ep)?.let { return it }
        }
        return null
    }

    private fun isGroup(node: Map<*, *>): Boolean = node["op"] == null && node["do"] is List<*>

    /** A presentation op, or a group whose children are all presentation. `use` calls are never presentation. */
    private fun isPresentation(node: Map<*, *>): Boolean {
        val op = node["op"] as? String
        if (op != null) return op in options.presentationOps
        val children = node["do"] as? List<*> ?: return false
        return children.all { it is Map<*, *> && isPresentation(it) }
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
