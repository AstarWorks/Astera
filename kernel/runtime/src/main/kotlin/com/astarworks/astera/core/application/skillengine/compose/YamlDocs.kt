package com.astarworks.astera.core.application.skillengine.compose

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver

internal const val API_VERSION = "astera/v2"

/** One validated `astera/v2` document. [group] is the file stem (single-file skill) or directory name (split skill). */
internal data class Doc(
    val file: String,
    val group: String,
    val index: Int,
    val kind: Kind,
    /** Skill id (kind Skill) or patch target (kind Patch). */
    val baseId: String,
    val variant: String?,
    val patchKind: String?,
    /** The document minus apiVersion / kind / target / variant / patchKind. */
    val body: Map<String, Any?>,
) {
    enum class Kind { Skill, Patch }
}

/**
 * Parses the YAML text of one file into [Doc]s.
 *
 * Limits: YAML anchors / aliases / merge keys (`<<:`) work inside ONE document; YAML scopes anchors per document,
 * so an alias cannot reach across a `---` separator or across files. Use a patch (or a split skill) for sharing.
 * The YAML 1.1 words `on` / `off` / `yes` / `no` are NOT booleans here (so `on:` is a plain string key); only
 * `true` / `false` are. Timestamps stay strings. Duplicate map keys are an error.
 * Files without an `apiVersion: astera/v2` line are skipped without being parsed (old v1 files).
 */
internal object YamlDocs {
    private val v2Marker = Regex("""(?m)^apiVersion:\s*["']?astera/v2""")

    private class StrictResolver : Resolver() {
        override fun addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, Regex("^(?:true|false)$").toPattern(), "tf")
            addImplicitResolver(Tag.INT, INT, "-+0123456789")
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.")
            addImplicitResolver(Tag.MERGE, MERGE, "<")
            addImplicitResolver(Tag.NULL, NULL, "~nN\u0000")
            addImplicitResolver(Tag.NULL, EMPTY, null)
        }
    }

    private fun newYaml(): Yaml {
        val opts = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            setAllowRecursiveKeys(false)
            maxAliasesForCollections = 200
        }
        val dumper = DumperOptions()
        return Yaml(SafeConstructor(opts), Representer(dumper), dumper, opts, StrictResolver())
    }

    @Suppress("UNCHECKED_CAST")
    fun parse(file: String, group: String, text: String): Pair<List<Doc>, List<ComposeError>> {
        if (text.isBlank()) return emptyList<Doc>() to emptyList()
        if (!v2Marker.containsMatchIn(text)) {
            return emptyList<Doc>() to listOf(ComposeError.InvalidDocument(file, "apiVersion", "no 'apiVersion: astera/v2' document in this file (a skill file that is not v2 is an error, never skipped)"))
        }
        val raw = try {
            newYaml().loadAll(text).toList()
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
            listOf("apiVersion", "kind", "target", "variant", "patchKind").forEach { body.remove(it) }
            val where = "document #${i + 1}"
            when (val kind = tree["kind"]) {
                "Skill" -> {
                    val id = (tree["id"] as? String) ?: group
                    if (id.isBlank() || '@' in id) {
                        errors += ComposeError.InvalidDocument(file, "id", "$where: skill id '$id' must be non-blank and contain no '@'")
                    } else {
                        body["id"] = id
                        docs += Doc(file, group, i, Doc.Kind.Skill, id, null, null, body)
                    }
                }
                "Patch" -> {
                    val target = tree["target"] as? String
                    val patchKind = tree["patchKind"] as? String
                    val variant = tree["variant"]?.toString()
                    when {
                        target.isNullOrBlank() ->
                            errors += ComposeError.InvalidDocument(file, "target", "$where: Patch needs a string 'target'")
                        patchKind != "skin" && patchKind != "balance" ->
                            errors += ComposeError.InvalidDocument(file, "patchKind", "$where: patchKind must be skin or balance, was '$patchKind'")
                        variant != null && (variant.isBlank() || '@' in variant) ->
                            errors += ComposeError.InvalidDocument(file, "variant", "$where: variant '$variant' must be non-blank and contain no '@'")
                        else -> docs += Doc(file, group, i, Doc.Kind.Patch, target, variant, patchKind, body)
                    }
                }
                else -> errors += ComposeError.InvalidDocument(file, "kind", "$where: kind must be Skill or Patch, was '$kind'")
            }
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
