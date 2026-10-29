package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.skillengine.part.ParamType

/** The derived tree of a tier plus the files of its base (extra [ComposedSkill.sources], so a base edit recomposes the tier). */
internal data class Derived(val tree: Map<String, Any?>, val baseFiles: List<String>)

/**
 * Derived documents (ADR-0303 9.3): a tier is `extends: <base id>` + `item` + `meta` + `with:` (param -> literal). The derived tree is a copy
 * of the base's same-id merged tree (no Patch of the base) with the tier's `id` / `item` / `meta` and the `with` values as param defaults;
 * the decoder and compiler see a plain skill document.
 */
internal object Extends {
    private val TIER_KEYS = setOf("id", "extends", "item", "meta", "with")

    @Suppress("UNCHECKED_CAST")
    fun derive(id: String, tier: Map<String, Any?>, file: String, all: List<Doc>, errors: MutableList<ComposeError>): Derived? {
        fun fail(path: String, reason: String): Nothing? { errors += ComposeError.Extends(file, path, reason); return null }
        val baseId = tier["extends"] as? String ?: return fail("extends", "extends: expected a skill id, got ${describe(tier["extends"])}")
        val baseDocs = all.filter { it.kind == Doc.Kind.Skill && it.key == baseId }
        if (baseDocs.isEmpty()) {
            return fail("extends", "extends '$baseId': no skill document '$baseId' in content/skills (a tier extends a composed base id, never a Part, a Patch variant or a tier)")
        }
        var base: Map<String, Any?> = emptyMap()
        for (d in baseDocs) {
            val (merged, failure) = StrategicMerge.merge(base, d.body)
            if (failure != null) { errors += failure.toError(d.file); return null }
            base = merged ?: return null
        }
        base["extends"]?.let {
            return fail("extends", "extends '$baseId': a base must not itself extend ($id -> $baseId -> $it); extend '$it' directly")
        }
        tier.keys.firstOrNull { it !in TIER_KEYS }?.let {
            return fail(it, "extends: a tier document takes only apiVersion, kind, id, extends, item, meta, with; '$it' belongs to the base '$baseId' (make what differs a param there)")
        }
        val meta = tier["meta"] ?: return fail("meta", "extends '$baseId': 'meta' is required in a tier (it is not inherited)")
        if (base.containsKey("item") && !tier.containsKey("item")) {
            return fail("item", "extends '$baseId': 'item' is required in a tier (it is not inherited)")
        }
        val baseParams = (base["params"] as? Map<String, Any?>).orEmpty()
        val with = when (val w = tier["with"]) {
            null -> emptyMap()
            is Map<*, *> -> w as Map<String, Any?>
            else -> return fail("with", "with: expected a map of param -> value, got ${describe(w)}")
        }
        val derivedParams = LinkedHashMap<String, Any?>()
        baseParams.forEach { (k, v) -> derivedParams[k] = v }
        for ((name, value) in with) {
            val spec = baseParams[name] as? Map<String, Any?>
                ?: return fail("with.$name", "with '$name': base '$baseId' declares no param '$name' (params: ${baseParams.keys.joinToString().ifEmpty { "none" }})")
            if (value is String && (value.startsWith("= ") || value.contains("\${"))) {
                return fail("with.$name", "with '$name': a tier value is a literal; '$value' is not (write the number)")
            }
            val typeKey = spec["type"] as? String ?: return fail("with.$name", "with '$name': base param has no type")
            val type = ParamType.of(typeKey) ?: return fail("with.$name", "with '$name': base param has unknown type '$typeKey'")
            val ok = when (type) {
                ParamType.TICKS -> value is Number && value.toDouble() >= 0.0 && value.toDouble() == Math.floor(value.toDouble())
                ParamType.BLOCKS, ParamType.NUMBER -> value is Number
                ParamType.BOOL -> value is Boolean
                ParamType.STRING -> value is String
            }
            if (!ok) return fail("with.$name", "with '$name': expected ${type.key}, got ${describe(value)}")
            derivedParams[name] = linkedMapOf("type" to typeKey, "default" to value)
        }
        for ((name, spec) in baseParams) {
            if ((spec as? Map<*, *>)?.get("required") == true && name !in with) {
                return fail("with", "extends '$baseId': required param '$name' has no value in with:")
            }
        }
        val out = LinkedHashMap<String, Any?>()
        base.forEach { (k, v) -> out[k] = v }
        out["id"] = id
        if (tier.containsKey("item")) out["item"] = tier["item"] else out.remove("item")
        out["meta"] = meta
        if (derivedParams.isNotEmpty()) out["params"] = derivedParams
        return Derived(out, baseDocs.map { it.file })
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is String -> "string \"$v\""
        is Boolean -> "bool $v"
        is Number -> "number $v"
        is Map<*, *> -> "a map"
        is List<*> -> "a list"
        else -> v.toString()
    }
}
