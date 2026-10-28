package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaw
import com.astarworks.astera.core.domain.skillengine.model.AnchorDecl
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

    /** The document `anchors:` entry [id] (ADR-0303 1); null for any other name. Document anchors are not frame instances, so [resolve] does not answer for them. */
    public fun document(id: String): AnchorDecl? = null
}

public data class ResolvedAnchor(val instance: InstanceId, val basis: ExprClass)

/**
 * An [AnchorScope] that frame-like ops (`frame`, and later `projectile` bodies) declare into while the compiler walks a state in
 * declaration order. [at] re-binds `i` / `parent.i` to another occurrence (a family member, a group occurrence) over the same declarations.
 */
public interface AnchorRegistry : AnchorScope {
    /** [instance] is the instance of an op WITHOUT its family index; [family] = N for `times: N` (instances `id[0..N-1]`), null for a single one. [laws] are the closed-form laws that move the anchor (a projectile body's `motion:`): riders compose them into their own Key rows (item 16). */
    public fun declare(instance: InstanceId, family: Int?, basis: ExprClass, laws: List<MotionLaw> = emptyList())

    public fun at(occurrence: Occurrence): AnchorScope
}

/** `blade`, `blade[2]`, `blade[i]`, `blade[parent.i]`, `blade[1].tip`. */
public object AnchorNames {
    /** [state] is the state path of a state-qualified name (`/throw/volley` -> `throw`, ADR-0303 10.1 / 11.3); null for a name of the current scope. */
    public data class Parts(val base: String, val index: String?, val point: String?, val state: String? = null)

    private val NAME = Regex("""^(?:/([A-Za-z_][A-Za-z0-9_-]*(?:/[A-Za-z_][A-Za-z0-9_-]*)*)/)?([A-Za-z_][A-Za-z0-9_-]*)(?:\[([A-Za-z0-9_.]+)])?(?:\.([A-Za-z_][A-Za-z0-9_]*))?$""")

    /** Null when [name] is not of the shape `id`, `id[index]`, `id.point` or `id[index].point` (e.g. `caster.hand_r` splits as base `caster`, point `hand_r`). */
    public fun split(name: String): Parts? =
        NAME.matchEntire(name.trim())?.let { Parts(it.groupValues[2], it.groupValues[3].ifEmpty { null }, it.groupValues[4].ifEmpty { null }, it.groupValues[1].ifEmpty { null }) }

    /** The name without its state qualifier (`/throw/volley[1].tip` -> `volley[1].tip`): the spelling an expression uses. */
    public fun bare(name: String): String = split(name)?.takeIf { it.state != null }?.let { name.trim().substringAfterLast('/') } ?: name

    /** True when the name addresses a family member (`blade[i]`); builtin anchors never have an index. */
    public fun isIndexed(name: String): Boolean = split(name)?.index != null
}

public class DefaultAnchorScope private constructor(
    private val occurrence: Occurrence,
    private val entries: MutableMap<String, Entry>,
    private val documents: Map<String, AnchorDecl>,
    private val foreign: (String) -> AnchorRegistry?,
) : AnchorRegistry {
    /** [foreign] answers the registry of an earlier state for a state-qualified name (`/throw/volley`); a name without a state never reads it. */
    public constructor(occurrence: Occurrence, documents: Map<String, AnchorDecl> = emptyMap(), foreign: (String) -> AnchorRegistry? = { null }) :
        this(occurrence, LinkedHashMap(), documents, foreign)

    private fun other(parts: AnchorNames.Parts): AnchorScope? = parts.state?.let { st -> foreign(st)?.at(occurrence) }
    private fun bareOf(parts: AnchorNames.Parts): String =
        parts.base + (parts.index?.let { "[$it]" } ?: "") + (parts.point?.let { ".$it" } ?: "")

    override fun document(id: String): AnchorDecl? = documents[id]

    private data class Entry(val instance: InstanceId, val family: Int?, val basis: ExprClass, val laws: List<MotionLaw>)

    override fun declare(instance: InstanceId, family: Int?, basis: ExprClass, laws: List<MotionLaw>) {
        val id = instance.path.last().op
        require(family == null || family >= 1) { "family of '${id.value}' must be >= 1 (was $family)" }
        entries[id.value.substringAfterLast('/')] = Entry(instance, family, basis, laws)
    }

    override fun laws(anchor: String): List<MotionLaw> {
        val parts = AnchorNames.split(anchor) ?: return emptyList()
        if (parts.state != null) return other(parts)?.laws(bareOf(parts)) ?: emptyList()
        return entries[parts.base]?.laws ?: emptyList()
    }

    override fun at(occurrence: Occurrence): AnchorScope = DefaultAnchorScope(occurrence, entries, documents, foreign)

    override fun resolve(name: String): ResolvedAnchor? {
        val parts = AnchorNames.split(name) ?: return null
        if (parts.state != null) return other(parts)?.resolve(bareOf(parts))
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
