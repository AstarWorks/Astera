package com.astarworks.astera.core.application.skillengine.expr.cel

import com.astarworks.astera.core.application.skillengine.compose.Occurrence
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.table.OpId

/**
 * Variables the v3 grammar adds to CEL (ADR-0303 3.5): `i`, `n`, the `parent.i` / `parent.n` chain (2.0a), `t` / `lt`,
 * `cast.t` / `state.t`, `event.i`, `event.victim` (feet point), `exit.reason` (class S, one value per exit sub-table, 10.2), and the `contact` / `target`
 * binding objects (`.pos`, `.forward`, `.eye`, plus `.normal` / `.point` on `contact`). Dotted names are CEL qualified
 * identifiers: the checker prefers the longest declared name, so `contact.point` is the variable and a bare `contact` stays the frame.
 */
public object V3Bindings {
    public const val MAX_PARENT_DEPTH: Int = 16

    /** Frames every expression may name (ADR-0303 3.5): the cast's own entities and the event's contact; declared anchors are added per document. */
    public val BUILTIN_FRAMES: Set<String> = setOf("caster", "target", "victim", "contact", "world", "enemy")

    private val BINDING_VEC3 = listOf("pos", "forward", "eye")
    private val CONTACT_ONLY_VEC3 = listOf("normal", "point")

    /** Entity state read live from the cast's caster / the event's target (3.8: `caster.on_ground`, `caster.health_ratio`); a neutral value until the host supplies it through `extra`. */
    private val ENTITY_ROOTS = listOf("caster", "target")

    /** Names whose value is only known when the event / hit happens (positions, C4c); never legal on a timing field. */
    internal val RUNTIME_POSITION_ROOTS: Set<String> = setOf("contact", "target", "caster")

    /** Single `event.` fields that are positions (`event.victim`); `event.i` and the other event fields stay static. */
    internal val RUNTIME_POSITION_FIELDS: Set<String> = setOf("event.victim", "event.attacker")

    /** True for a dotted identifier whose value is a position known only when the event happens. */
    internal fun isRuntimePosition(ident: String): Boolean =
        RUNTIME_POSITION_FIELDS.any { ident == it || ident.startsWith("$it.") } || (ident.contains('.') && ident.substringBefore('.') in RUNTIME_POSITION_ROOTS)

    /** [base] plus every v3 variable. [occurrence] decides how long the `parent.` chain is. */
    public fun spec(occurrence: Occurrence?, base: ExprEnvSpec = ExprEnvSpec()): ExprEnvSpec {
        val v = LinkedHashMap(base.variables)
        v["i"] = ExprType.NUM
        v["n"] = ExprType.NUM
        var depth = 0
        var p = occurrence?.parent
        var prefix = "parent."
        while (p != null && depth < MAX_PARENT_DEPTH) {
            v[prefix + "i"] = ExprType.NUM
            v[prefix + "n"] = ExprType.NUM
            prefix += "parent."
            p = p.parent
            depth++
        }
        for (name in listOf("t", "lt", "cast.t", "state.t", "event.i")) v[name] = ExprType.NUM
        v["exit.reason"] = ExprType.STR
        for (f in BINDING_VEC3) {
            v["contact.$f"] = ExprType.VEC3
            v["target.$f"] = ExprType.VEC3
        }
        for (f in CONTACT_ONLY_VEC3) v["contact.$f"] = ExprType.VEC3
        for (root in ENTITY_ROOTS) {
            v["$root.on_ground"] = ExprType.BOOL
            v["$root.health_ratio"] = ExprType.NUM
        }
        v["event.victim"] = ExprType.VEC3
        v["event.attacker"] = ExprType.VEC3
        return ExprEnvSpec(v, base.frames + BUILTIN_FRAMES)
    }

    /** Values for [spec]; any argument left null is bound to a neutral value (0, "", ZERO), which only the classifier-checked S paths read. */
    public fun values(
        occurrence: Occurrence?,
        castTick: Int = 0,
        stateTick: Int = 0,
        instanceTick: Int = 0,
        lawTick: Int = 0,
        eventI: Int = 0,
        exitReason: String = "",
        contact: Binding = Binding(),
        target: Binding = Binding(),
        extra: Map<String, ExprValue> = emptyMap(),
    ): Map<String, ExprValue> {
        val out = LinkedHashMap<String, ExprValue>(extra)
        out["i"] = ExprValue.Num((occurrence?.i ?: 0).toDouble())
        out["n"] = ExprValue.Num((occurrence?.n ?: 1).toDouble())
        var p = occurrence?.parent
        var prefix = "parent."
        var depth = 0
        while (p != null && depth < MAX_PARENT_DEPTH) {
            out[prefix + "i"] = ExprValue.Num(p.i.toDouble())
            out[prefix + "n"] = ExprValue.Num(p.n.toDouble())
            prefix += "parent."
            p = p.parent
            depth++
        }
        out["t"] = ExprValue.Num(instanceTick.toDouble())
        out["lt"] = ExprValue.Num(lawTick.toDouble())
        out["cast.t"] = ExprValue.Num(castTick.toDouble())
        out["state.t"] = ExprValue.Num(stateTick.toDouble())
        out["event.i"] = ExprValue.Num(eventI.toDouble())
        out["exit.reason"] = ExprValue.Str(exitReason)
        for ((root, b) in listOf("contact" to contact, "target" to target)) {
            out["$root.pos"] = ExprValue.Vec3(b.pos)
            out["$root.forward"] = ExprValue.Vec3(b.forward)
            out["$root.eye"] = ExprValue.Vec3(b.eye)
        }
        // `event.victim` is the victim's feet (its Ref form stays `target`/`victim`); `dist(event.victim, caster)` reads it as a point
        out["event.victim"] = ExprValue.Vec3(target.pos)
        // `event.attacker` (a `hurt` handler) is likewise read as the attacker's feet; only the runtime knows it, so the neutral value is the target's
        out["event.attacker"] = ExprValue.Vec3(target.pos)
        out["contact.normal"] = ExprValue.Vec3(contact.normal)
        out["contact.point"] = ExprValue.Vec3(contact.point)
        for (root in ENTITY_ROOTS) {
            out.putIfAbsent("$root.on_ground", ExprValue.Bool(false))
            out.putIfAbsent("$root.health_ratio", ExprValue.Num(1.0))
        }
        return out
    }

    /** Snapshot of a `contact` / `target` binding object (3.6 rev4.1: contact outlives the body). */
    public data class Binding(
        val pos: Vec3 = Vec3.ZERO,
        val forward: Vec3 = Vec3.ZERO,
        val eye: Vec3 = Vec3.ZERO,
        val normal: Vec3 = Vec3.ZERO,
        val point: Vec3 = Vec3.ZERO,
    )

    /**
     * `ExprEnv.seed` of one row at play time: `rand()` is seeded by (cast, the row's content seed, call site). [rowSeed] is `Row.seed`, a hash of
     * the row's own content (kind, tick, copy indices, resolved cells; see `RowSeeds`), never of an op id, so renaming or reordering ops keeps every
     * spread. The call site is the order of the `rand()` inside the source (the compiler turns the k-th `rand()` into the stream `@k`), so two
     * `rand()` in one field draw independently and a replay draws the same values.
     */
    public fun rowSeed(castSeed: Long, rowSeed: Long): Long = mix(castSeed xor (rowSeed * -0x61c8864680b583ebL))

    /**
     * Seed of a compile-time fold that has no row yet (a `use` argument): (cast, the text of the cell, copy index). Content only, like [rowSeed].
     */
    public fun contentSeed(castSeed: Long, text: String, i: Int): Long = mix(castSeed xor (fnv1a(text) * -0x61c8864680b583ebL) xor (i.toLong() * 0x2545F4914F6CDD1DL))

    /** FNV-1a over the UTF-16 units: stable across JVMs (unlike `hashCode` of a non-String). */
    public fun fnv1a(text: String, basis: Long = -0x340d631b7bdddcdbL): Long {
        var h = basis
        for (c in text) h = (h xor c.code.toLong()) * 0x100000001b3L
        return h
    }

    private fun mix(z0: Long): Long {
        var h = (z0 xor (z0 ushr 30)) * -0x40a7b892e31b1a47L
        h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
        return h xor (h ushr 31)
    }
}
