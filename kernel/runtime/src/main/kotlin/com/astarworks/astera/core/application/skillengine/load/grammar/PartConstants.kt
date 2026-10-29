package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.application.skillengine.load.ItemFrames

/**
 * The model constants of a frame with `part:` (ADR-0303 3.5): `<frame>.length` and `<frame>.<point>.<axis>` (the point's coordinate along the part's axis)
 * are class S, so [fold] writes their numbers into an expression source when the file loads. `content/frames/<item>.yaml` stays the one place the numbers live.
 * A name on a frame op that has no `part:` is refused with the reason, never left to the evaluator.
 */
public class PartConstants(private val values: Map<String, Double>, private val partless: Set<String>, private val stand: Set<String> = emptySet()) {
    private val pattern: Regex? = (values.keys.map { it.substringBefore('.') } + partless + stand).toSet().takeIf { it.isNotEmpty() }?.let { names ->
        val ids = names.map { Regex.escape(it) }.joinToString("|")
        Regex("(?<![\\w.])($ids)\\.(length|[a-z_]+\\.[a-z_]+)(?![\\w(.])")
    }

    /** [source] with every `<frame>.length` / `<frame>.<point>.<axis>` replaced by its number; null when [source] names none. */
    public fun fold(source: String, fail: (String) -> Nothing): String {
        val re = pattern ?: return source
        return re.replace(source) { m ->
            val id = m.groupValues[1]
            val key = id + "." + m.groupValues[2]
            if (id in partless) fail("'$key' needs a frame with part: ('$id' is a frame op without a part:)")
            if (id in stand) return@replace "1.0"
            val v = values[key] ?: return@replace m.value
            if (v == Math.floor(v) && Math.abs(v) < 1e9) v.toLong().toString() + ".0" else v.toString()
        }
    }

    public companion object {
        /** The document decoder has no item frame table: every frame with a `part:` reads 1.0 there (the compile-time decode folds the real numbers). */
        public fun standIn(parts: Map<String, String?>): PartConstants =
            PartConstants(emptyMap(), parts.filterValues { it == null }.keys, parts.filterValues { it != null }.keys)

        /** From the machine's `id -> part` ([ExprFrames.partsOf]) and the item's frame table. */
        public fun of(parts: Map<String, String?>, frames: ItemFrames?): PartConstants {
            val values = LinkedHashMap<String, Double>()
            val partless = LinkedHashSet<String>()
            for ((id, part) in parts) {
                val model = if (part == null) null else frames?.partFor(part)
                if (model == null) { partless += id; continue }
                values["$id.length"] = model.length
                for ((pn, pv) in model.points) for ((an, av) in model.axes) values["$id.$pn.$an"] = pv.x * av.x + pv.y * av.y + pv.z * av.z
            }
            return PartConstants(values, partless)
        }
    }
}
