package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaw
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.OpId

/**
 * Resolves `anchor: ball` / `anchor: "blade[i]"` to this occurrence's InstanceId and basis class (S/T/L).
 *
 * A name may carry a model point after the instance (`blade[1].tip`); [resolve] answers for the INSTANCE and ignores the point, use
 * [AnchorNames.split] to read the point. Builtin anchors (`caster`, `world`, `contact`, ...) and anchors declared in `anchors:` are not
 * frame instances: they resolve to null.
 */
public interface AnchorScope {
    public fun resolve(name: String): ResolvedAnchor?

    /** The motion laws bound to a frame-like anchor, in declaration order; empty for live anchors (caster, contact, ...) and unknown names. */
    public fun laws(anchor: String): List<MotionLaw> = emptyList()
}

public data class ResolvedAnchor(val instance: InstanceId, val basis: ExprClass)

/**
 * An [AnchorScope] that frame-like ops (`frame`, and later `projectile` bodies) declare into while the compiler walks a state in
 * declaration order. [at] re-binds `i` / `parent.i` to another occurrence (a family member, a group occurrence) over the same declarations.
 */
public interface AnchorRegistry : AnchorScope {
    /** [instance] is the instance of an op WITHOUT its family index; [family] = N for `times: N` (instances `id[0..N-1]`), null for a single one. */
    public fun declare(instance: InstanceId, family: Int?, basis: ExprClass)

    public fun at(occurrence: Occurrence): AnchorScope
}

/** `blade`, `blade[2]`, `blade[i]`, `blade[parent.i]`, `blade[1].tip`. */
public object AnchorNames {
    public data class Parts(val base: String, val index: String?, val point: String?)

    private val NAME = Regex("""^([A-Za-z_][A-Za-z0-9_-]*)(?:\[([A-Za-z0-9_.]+)])?(?:\.([A-Za-z_][A-Za-z0-9_]*))?$""")

    /** Null when [name] is not of the shape `id`, `id[index]`, `id.point` or `id[index].point` (e.g. `caster.hand_r` splits as base `caster`, point `hand_r`). */
    public fun split(name: String): Parts? =
        NAME.matchEntire(name.trim())?.let { Parts(it.groupValues[1], it.groupValues[2].ifEmpty { null }, it.groupValues[3].ifEmpty { null }) }

    /** True when the name addresses a family member (`blade[i]`); builtin anchors never have an index. */
    public fun isIndexed(name: String): Boolean = split(name)?.index != null
}

public class DefaultAnchorScope private constructor(
    private val occurrence: Occurrence,
    private val entries: MutableMap<String, Entry>,
) : AnchorRegistry {
    public constructor(occurrence: Occurrence) : this(occurrence, LinkedHashMap())

    private data class Entry(val instance: InstanceId, val family: Int?, val basis: ExprClass)

    override fun declare(instance: InstanceId, family: Int?, basis: ExprClass) {
        val id = instance.path.last().op
        require(family == null || family >= 1) { "family of '${id.value}' must be >= 1 (was $family)" }
        entries[id.value] = Entry(instance, family, basis)
    }

    override fun at(occurrence: Occurrence): AnchorScope = DefaultAnchorScope(occurrence, entries)

    override fun resolve(name: String): ResolvedAnchor? {
        val parts = AnchorNames.split(name) ?: return null
        val e = entries[parts.base] ?: return null
        val prefix = e.instance.path.dropLast(1)
        val op: OpId = e.instance.path.last().op
        val index = parts.index
        if (e.family == null) {
            if (index != null) return null
            return ResolvedAnchor(e.instance, e.basis)
        }
        // A family is addressed by an index only: a bare `blade` is ambiguous (which of N?), so it does not resolve.
        val k = indexValue(index ?: return null) ?: return null
        if (k !in 0 until e.family) return null
        return ResolvedAnchor(InstanceId(prefix + InstanceId.Segment(op, k)), e.basis)
    }

    /** `3`, `i`, `parent.i`, `parent.parent.i` (the occurrence chain of 2.0a). */
    private fun indexValue(text: String): Int? {
        text.toIntOrNull()?.let { return it.takeIf { v -> v >= 0 } }
        var occ: Occurrence? = occurrence
        var rest = text
        while (rest.startsWith("parent.")) {
            occ = occ?.parent
            rest = rest.removePrefix("parent.")
        }
        return if (rest == "i") occ?.i else null
    }
}
