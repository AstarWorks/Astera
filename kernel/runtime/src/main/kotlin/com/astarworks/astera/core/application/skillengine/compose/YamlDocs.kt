package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.StrictYaml
import com.astarworks.astera.core.application.skillengine.part.PatchApplier
import com.astarworks.astera.core.application.skillengine.part.PatchKind
import com.astarworks.astera.core.application.skillengine.part.PatchOp
import com.astarworks.astera.core.domain.Result
import org.yaml.snakeyaml.error.YAMLException

internal const val API_VERSION = "astera/v3"

/** One validated `astera/v3` document. [group] is the file stem (single-file skill) or directory name (split skill). */
internal data class Doc(
    val file: String,
    val group: String,
    val index: Int,
    val kind: Kind,
    /** Skill / part id (kind Skill / Part) or patch target (kind Patch). */
    val baseId: String,
    val variant: String?,
    val patchKind: PatchKind?,
    /** The document minus apiVersion / kind (Skill / Part); empty for a Patch. */
    val body: Map<String, Any?>,
    /** The ops of a Patch document (R35), decoded by `PatchApplier.decode`; empty for Skill / Part. */
    val ops: List<PatchOp> = emptyList(),
) {
    /** Composition key: the id, or `part:<id>` for a part (skills and parts have separate id spaces). */
    val key: String get() = if (kind == Kind.Part) "part:$baseId" else baseId

    enum class Kind { Skill, Part, Patch }
}

/**
 * Parses the YAML text of one file into [Doc]s.
 *
 * Limits: YAML anchors / aliases / merge keys (`<<:`) work inside ONE document; YAML scopes anchors per document,
 * so an alias cannot reach across a `---` separator or across files. Use a patch (or a split skill) for sharing.
 * The YAML 1.1 words `on` / `off` / `yes` / `no` are NOT booleans here (so `on:` is a plain string key); only
 * `true` / `false` are. Timestamps stay strings. Duplicate map keys are an error.
 * A file without an `apiVersion: astera/v3` line is an error, never skipped (no old-schema support).
 */
internal object YamlDocs {
    private val v3Marker = Regex("""(?m)^apiVersion:\s*["']?astera/v3""")

    @Suppress("UNCHECKED_CAST")
    fun parse(file: String, group: String, text: String): Pair<List<Doc>, List<ComposeError>> {
        if (text.isBlank()) return emptyList<Doc>() to emptyList()
        if (!v3Marker.containsMatchIn(text)) {
            return emptyList<Doc>() to listOf(ComposeError.InvalidDocument(file, "apiVersion", "no 'apiVersion: astera/v3' document in this file (a skill file that is not v3 is an error, never skipped)"))
        }
        val raw = try {
            StrictYaml.newYaml().loadAll(text).toList()
        } catch (e: YAMLException) {
            val first = (e.message?.lineSequence()?.firstOrNull() ?: "invalid YAML").let {
                if (it.contains("undefined alias") || it.contains("undefined anchor")) {
                    "$it (YAML anchors and aliases work inside ONE document only; an alias cannot reach into another file or across a '---' separator. Share values through params or a patch instead)"
                } else {
                    it
                }
            }
            return emptyList<Doc>() to listOf(ComposeError.ParseError(file, "", first))
        }
        val docs = ArrayList<Doc>()
        val errors = ArrayList<ComposeError>()
        raw.forEachIndexed { i, any ->
            if (any == null) return@forEachIndexed
            val m = any as? Map<*, *>
            if (m == null) {
                errors += ComposeError.InvalidDocument(file, "", "document #${i + 1}: expected a mapping at the document root")
                return@forEachIndexed
            }
            if (m["apiVersion"] != API_VERSION) {
                errors += ComposeError.InvalidDocument(file, "apiVersion", "document #${i + 1}: apiVersion must be $API_VERSION, was '${m["apiVersion"]}'")
                return@forEachIndexed
            }
            val tree = normalize(m) as Map<String, Any?>
            val body = LinkedHashMap(tree)
            body.remove("apiVersion")
            body.remove("kind")
            val where = "document #${i + 1}"
            when (val kind = tree["kind"]) {
                "Skill", "Part" -> {
                    val id = (tree["id"] as? String) ?: group
                    if (id.isBlank() || '@' in id) {
                        errors += ComposeError.InvalidDocument(file, "id", "$where: $kind id '$id' must be non-blank and contain no '@'")
                    } else {
                        body["id"] = id
                        docs += Doc(file, group, i, if (kind == "Part") Doc.Kind.Part else Doc.Kind.Skill, id, null, null, body)
                    }
                }
                "Patch" -> when (val r = PatchApplier.decode(tree, where)) {
                    is Result.Failure -> errors += ComposeError.InvalidDocument(file, r.error.path, r.error.message)
                    is Result.Success -> r.value.let { p -> docs += Doc(file, group, i, Doc.Kind.Patch, p.target, p.variant, p.patchKind, emptyMap(), p.ops) }
                }
                else -> errors += ComposeError.InvalidDocument(file, "kind", "$where: kind must be Skill, Part or Patch, was '$kind'")
            }
        }
        // one skill id per file (a tier lives in its own `<id>/skill.yaml`): `---` documents of one file are same-id fragments or Patches
        val skills = docs.filter { it.kind == Doc.Kind.Skill }
        val first = skills.firstOrNull()
        val stray = if (first == null) null else skills.firstOrNull { it.baseId != first.baseId }
        if (stray != null) {
            errors += ComposeError.InvalidDocument(
                file, "id",
                "document ${stray.index + 1} has id '${stray.baseId}'; a file holds one skill id (plus its same-id fragments and Patches); put a tier in content/skills/${stray.baseId}/skill.yaml",
            )
        }
        return docs to errors
    }

    /** Maps get stringified keys and become insertion-ordered LinkedHashMaps; collections become Lists. */
    private fun normalize(v: Any?): Any? = when (v) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> v.forEach { (k, x) -> out[k.toString()] = normalize(x) } }
        is Collection<*> -> v.map { normalize(it) }
        else -> v
    }
}
