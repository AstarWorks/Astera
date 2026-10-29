package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaw
import com.astarworks.astera.core.domain.skillengine.model.AnchorDecl
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Pose

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

    /**
     * The pose chain of a MOVING frame (a `frame` with laws, or riding one); null for every other anchor. A rider's rows are written absolute,
     * so a rider of such a frame skips the frame (its Spawn anchor is the chain's [AnchorChain.parent]) and composes base + laws into its own rows.
     */
    public fun chain(anchor: String): AnchorChain? = null

    /** N when [anchor] names a frame family of N members (`times: N`), else null. */
    public fun familySize(anchor: String): Int? = null

    /** The document `anchors:` entry [id] (ADR-0303 1); null for any other name. Document anchors are not frame instances, so [resolve] does not answer for them. */
    public fun document(id: String): AnchorDecl? = null
}

public data class ResolvedAnchor(val instance: InstanceId, val basis: ExprClass)

/**
 * What a rider of a moving frame composes: the frame's own authored [base] pose (per family copy), the [laws] that move it (its own after the
 * laws of the frames it rides) and the [parent] the chain is relative to (the first non-moving anchor: an instance, or the host name such as `caster`).
 */
public data class AnchorChain(val base: Pose, val parent: InstanceId?, val parentName: String, val laws: List<MotionLaw>)

/**
 * An [AnchorScope] that frame-like ops (`frame`, and later `projectile` bodies) declare into while the compiler walks a state in
 * declaration order. [at] re-binds `i` / `parent.i` to another occurrence (a family member, a group occurrence) over the same declarations.
 */
public interface AnchorRegistry : AnchorScope {
    /** [instance] is the instance of an op WITHOUT its family index; [family] = N for `times: N` (instances `id[0..N-1]`), null for a single one. [laws] are the closed-form laws that move the anchor (a projectile body's `motion:`); [copy] is the family index they were baked for (a law number may read `i`), so a rider addressing `id[k]` composes copy k's laws: riders compose them into their own Key rows (item 16). */
    public fun declare(instance: InstanceId, family: Int?, basis: ExprClass, laws: List<MotionLaw> = emptyList(), copy: Int? = null, chain: AnchorChain? = null)

    public fun at(occurrence: Occurrence, family: Int? = null): AnchorRegistry
}

/** `blade`, `blade[2]`, `blade[i]`, `blade[parent.i]`, `blade[1].tip`. */
public object AnchorNames {
    /** [state] is the state path of a state-qualified name (`/throw/volley` -> `throw`, ADR-0303 10.1 / 11.3); null for a name of the current scope. */
    public data class Parts(val base: String, val index: String?, val point: String?, val state: String? = null)

    private val NAME = Regex("""^(?:/([A-Za-z_][A-Za-z0-9_-]*(?:/[A-Za-z_][A-Za-z0-9_-]*)*)/)?([A-Za-z_][A-Za-z0-9_-]*)(?:\[([A-Za-z0-9_.]+)])?(?:\.([A-Za-z_][A-Za-z0-9_]*))?$""")

    /** The frames every op and expression may name without declaring them: the cast's own entities and the handler's contact (ADR-0303 anchors table). */
    public val BUILTIN_FRAMES: Set<String> = setOf("caster", "target", "victim", "enemy", "contact", "world", "attacker", "aim", "cast")

    /** Names no declaration may take: the builtin frames plus the expression variables and `event` / `self` / `parent`. */
    public val RESERVED: Set<String> = BUILTIN_FRAMES + setOf("event", "self", "i", "n", "t", "lt", "parent")

    /** The load error text when [name] (declared as [what], e.g. `anchors[0]`) is a reserved name, else null. */
    public fun reservedError(what: String, name: String): String? {
        if (name !in RESERVED) return null
        val meaning = when (name) {
            "contact" -> "the hit point of the current handler"
            "caster", "target", "victim", "enemy", "attacker", "aim", "cast", "world" -> "a frame every op may name"
            else -> "a name the engine reads in expressions and paths"
        }
        return "$what: '$name' is a builtin name ($meaning) and cannot be declared"
    }

    /** The caster points that were guessed geometry and are gone: a hand is a point of the skill's own body clip (`<body op id>.hand_r`). */
    public val REMOVED_CASTER_POINTS: Set<String> = setOf("hand_r", "hand_l", "palm")

    /** The load error text when [name] is a removed `caster.<point>`, else null. */
    public fun removedCasterPoint(name: String): String? {
        val bare = name.trim().removePrefix("anchor:")
        if (!bare.startsWith("caster.")) return null
        val point = bare.removePrefix("caster.")
        if (point !in REMOVED_CASTER_POINTS) return null
        return "'$bare' no longer exists: the engine does not guess a hand. Name a point of the skill's own body clip " +
            "(a `body` op with an `id`; the points are declared once in content/frames/body.yaml), e.g. '<body id>.$point'"
    }

    /**
     * The load error text when [name] is a bare frame family that [scope] cannot bind to a member (the op is not in a `times:` of the same size), else null.
     * [what] is `display 'spark'`.
     */
    public fun bareFamilyError(scope: AnchorScope, name: String, what: String): String? {
        val parts = split(name) ?: return null
        if (parts.index != null || parts.state != null || scope.resolve(name) != null) return null
        val n = scope.familySize(name) ?: return null
        return "$what: anchor '${parts.base}' is a family of $n; write ${parts.base}[k] (k = 0..${n - 1}), or give this op the same `times: $n` so `${parts.base}` means ${parts.base}[i]"
    }

    /** Null when [name] is not of the shape `id`, `id[index]`, `id.point` or `id[index].point` (e.g. `body_anim.hand_r` splits as base `body_anim`, point `hand_r`). */
    public fun split(name: String): Parts? =
        NAME.matchEntire(name.trim())?.let { Parts(it.groupValues[2], it.groupValues[3].ifEmpty { null }, it.groupValues[4].ifEmpty { null }, it.groupValues[1].ifEmpty { null }) }

    /** The name without its state qualifier (`/throw/volley[1].tip` -> `volley[1].tip`): the spelling an expression uses. */
    public fun bare(name: String): String = split(name)?.takeIf { it.state != null }?.let { name.trim().substringAfterLast('/') } ?: name

    /**
     * The name the table player resolves for [name] in this scope: a frame a part call bound (`frames: {origin: caster}`) is spelled by the
     * caller's frame (`caster`, `blade`) with the same point suffix, because the part's own name does not exist outside the part.
     */
    public fun host(scope: AnchorScope, name: String): String {
        val parts = split(name) ?: return name
        if (parts.state != null || parts.index != null) return name
        val inst = scope.resolve(name)?.instance?.text ?: return name
        if (inst == parts.base || inst.contains('[') || inst.contains('/')) return name
        return inst + (parts.point?.let { ".$it" } ?: "")
    }

    /** True when the name addresses a family member (`blade[i]`); builtin anchors never have an index. */
    public fun isIndexed(name: String): Boolean = split(name)?.index != null
}

public class DefaultAnchorScope private constructor(
    private val occurrence: Occurrence,
    private val entries: MutableMap<String, Entry>,
    private val documents: Map<String, AnchorDecl>,
    private val foreign: (String) -> AnchorRegistry?,
    private val published: (String) -> Entry?,
    private val ownFamily: Int? = null,
) : AnchorRegistry {
    /** [foreign] answers the registry of an earlier state for a state-qualified name (`/throw/volley`); a name without a state never reads it. */
    public constructor(
        occurrence: Occurrence, documents: Map<String, AnchorDecl> = emptyMap(), foreign: (String) -> AnchorRegistry? = { null },
        published: (String) -> Any? = { null },
    ) : this(occurrence, LinkedHashMap(), documents, foreign, { n -> published(n) as? Entry })

    /** The entry [name] of this scope that a PART published to its caller (ADR-0303 9 `frames_out`): declared by an op inside a called part's state (id `call/state/op`; a plain top-level op is `state/op` and is never read by bare name from another state). */
    internal fun publishedEntry(name: String): Any? = entries[name]?.takeIf { it.instance.path.last().op.value.count { c -> c == '/' } >= 2 }

    /** The entry [name] declared in this scope, whatever its id shape (a machine `world:` body, read from another state). */
    internal fun entryOf(name: String): Any? = entries[name]

    private fun other(parts: AnchorNames.Parts): AnchorScope? = parts.state?.let { st -> foreign(st)?.at(occurrence) }
    private fun bareOf(parts: AnchorNames.Parts): String =
        parts.base + (parts.index?.let { "[$it]" } ?: "") + (parts.point?.let { ".$it" } ?: "")

    override fun document(id: String): AnchorDecl? = documents[id]

    internal data class Entry(
        val instance: InstanceId, val family: Int?, val basis: ExprClass, val laws: List<MotionLaw>, val copies: Map<Int, List<MotionLaw>> = emptyMap(),
        val chain: AnchorChain? = null, val chains: Map<Int, AnchorChain> = emptyMap(),
    )

    override fun declare(instance: InstanceId, family: Int?, basis: ExprClass, laws: List<MotionLaw>, copy: Int?, chain: AnchorChain?) {
        val id = instance.path.last().op
        require(family == null || family >= 1) { "family of '${id.value}' must be >= 1 (was $family)" }
        val key = id.value.substringAfterLast('/')
        val prior = entries[key]?.takeIf { copy != null && it.instance == instance }
        entries[key] = Entry(
            instance, family, basis, laws, if (copy != null) prior?.copies.orEmpty() + (copy to laws) else emptyMap(),
            chain, if (copy != null && chain != null) prior?.chains.orEmpty() + (copy to chain) else emptyMap(),
        )
    }

    override fun chain(anchor: String): AnchorChain? {
        val parts = AnchorNames.split(anchor) ?: return null
        if (parts.state != null) return other(parts)?.chain(bareOf(parts))
        val e = entries[parts.base] ?: published(parts.base) ?: return null
        val k = (parts.index ?: sameFamily(e))?.let { indexValue(it) }
        return (if (k != null) e.chains[k] else null) ?: e.chain
    }

    override fun laws(anchor: String): List<MotionLaw> {
        val parts = AnchorNames.split(anchor) ?: return emptyList()
        if (parts.state != null) return other(parts)?.laws(bareOf(parts)) ?: emptyList()
        val e = entries[parts.base] ?: published(parts.base) ?: return emptyList()
        val k = (parts.index ?: sameFamily(e))?.let { indexValue(it) }
        return (if (k != null) e.copies[k] else null) ?: e.laws
    }

    override fun at(occurrence: Occurrence, family: Int?): AnchorRegistry = DefaultAnchorScope(occurrence, entries, documents, foreign, published, family)

    override fun resolve(name: String): ResolvedAnchor? {
        val parts = AnchorNames.split(name) ?: return null
        if (parts.state != null) return other(parts)?.resolve(bareOf(parts))
        val e = entries[parts.base] ?: published(parts.base) ?: return null
        val prefix = e.instance.path.dropLast(1)
        val op: OpId = e.instance.path.last().op
        val index = parts.index ?: sameFamily(e)
        if (e.family == null) {
            if (index != null) return null
            return ResolvedAnchor(e.instance, e.basis)
        }
        // A family is addressed by an index only: a bare `blade` is ambiguous (which of N?), so it does not resolve.
        val k = indexValue(index ?: return null) ?: return null
        if (k !in 0 until e.family) return null
        return ResolvedAnchor(InstanceId(prefix + InstanceId.Segment(op, k)), e.basis)
    }

    /** A bare family name inside an op of the SAME `times: N` means the member of this copy (`blade` = `blade[i]`); null for any other op. */
    private fun sameFamily(e: Entry): String? = if (e.family != null && e.family == ownFamily) "i" else null

    override fun familySize(anchor: String): Int? {
        val parts = AnchorNames.split(anchor) ?: return null
        if (parts.state != null) return other(parts)?.familySize(bareOf(parts))
        return (entries[parts.base] ?: published(parts.base))?.family
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
