package com.astarworks.astera.core.application.skillengine.load.motion

import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.domain.animation.Easing

/**
 * One property law on a point kind (particle, sound), ADR-0303 3.4: `{law: grow|fade, prop, ...}`.
 * Particles and sounds are fire-and-forget, so a law is evaluated once per occurrence at `tau = i * every` (ticks since the op started)
 * and the occurrence uses that value (3.4 "How each kind samples a law"). Numbers are literal: the value of an occurrence is fixed at compile time.
 */
public data class PointLaw(
    val law: String,
    val prop: String,
    val at: Int,
    val forTicks: Int?,
    val perTick: List<Double>?,
    val accel: Double,
    val to: List<Double>?,
    val ease: String?,
    val exponent: Double,
) {
    /** The property value of an occurrence at [tau], given the value [base] before this law (laws apply in list order, rates add, `fade` multiplies). */
    public fun apply(base: List<Double>, tau: Int): List<Double> {
        val local = (tau - at).coerceAtLeast(0).let { if (forTicks != null) it.coerceAtMost(forTicks) else it }
        return when (law) {
            "fade" -> {
                val u = if (forTicks == null || forTicks == 0) 1.0 else local.toDouble() / forTicks
                val k = Math.pow(1.0 - u, exponent)
                base.map { it * k }
            }
            else -> when {
                to != null -> {
                    val u = if (forTicks == null || forTicks == 0) 1.0 else local.toDouble() / forTicks
                    val e = ease?.let { Easing.fromKey(it) }?.apply(u) ?: u
                    base.indices.map { base[it] + (to[if (to.size == 1) 0 else it] - base[it]) * e }
                }
                else -> {
                    val r = perTick!!
                    base.indices.map { base[it] + r[if (r.size == 1) 0 else it] * local + accel * local * local / 2.0 }
                }
            }
        }
    }
}

/** The `motion:` list of a point op, read into [PointLaw]s. */
public class PointLawsType(private val props: Set<String>, private val defaultProp: String) : FieldType<List<PointLaw>> {
    override val label: String = "list of laws {law: grow|fade, prop: ${props.joinToString("|")}, ...}"

    override fun decode(raw: Any?, ctx: GrammarContext, path: String): List<PointLaw> {
        val list = raw as? List<*> ?: ctx.mismatch(path, label, raw)
        if (list.isEmpty()) ctx.fail(path, "motion must not be empty (omit it for a still emitter)")
        return list.mapIndexed { i, item -> one(item, ctx, "$path[$i]") }
    }

    private fun one(raw: Any?, ctx: GrammarContext, path: String): PointLaw {
        val m = raw as? Map<*, *> ?: ctx.mismatch(path, "law map {law: grow|fade, prop, ...}", raw)
        val law = m["law"] as? String ?: ctx.fail("$path.law", "every motion entry names its kind: law: grow|fade")
        if (law != "grow" && law != "fade") {
            ctx.fail("$path.law", "law '$law' is not defined on a point kind (particle, sound): grow and fade act on its properties (ADR-0303 3.4)")
        }
        val allowed = if (law == "grow") setOf("law", "prop", "at", "for", "per_tick", "accel", "to", "ease")
        else setOf("law", "prop", "at", "for", "exponent")
        for (k in m.keys) if (k !in allowed) ctx.fail("$path.$k", "unknown key '$k' for a $law law on a point kind (known: ${allowed.joinToString()})")
        val prop = (m["prop"] as? String) ?: defaultProp
        val legal = if (law == "fade") props.intersect(setOf("count", "volume")) else props
        if (prop !in legal) ctx.fail("$path.prop", "$law cannot act on '$prop' here (known: ${legal.joinToString()})")
        fun ticks(key: String): Int? = m[key]?.let { v ->
            val n = (v as? Number)?.toDouble() ?: ctx.mismatch("$path.$key", "a whole number of ticks", v)
            if (n % 1.0 != 0.0 || n < 0.0) ctx.fail("$path.$key", "$key is a whole number of ticks >= 0")
            n.toInt()
        }
        fun nums(key: String): List<Double>? = m[key]?.let { v ->
            when (v) {
                is Number -> listOf(v.toDouble())
                is List<*> -> v.map { (it as? Number)?.toDouble() ?: ctx.mismatch("$path.$key", "number or [x, y, z] of numbers", v) }
                    .also { if (it.size != 3) ctx.fail("$path.$key", "a vector is [x, y, z]") }
                else -> ctx.mismatch("$path.$key", "number or [x, y, z] of numbers (point laws are literal)", v)
            }
        }
        if (law == "fade" && ticks("for") == null) ctx.fail("$path.for", "fade needs for: the ticks until the property reaches 0")
        val perTick = nums("per_tick")
        val to = nums("to")
        if (law == "grow") {
            if ((perTick == null) == (to == null)) ctx.fail(path, "grow needs exactly one of per_tick, to")
            if (to != null && ticks("for") == null) ctx.fail("$path.for", "grow to needs for")
            if (to != null && m["accel"] != null) ctx.fail("$path.accel", "accel excludes to")
        }
        val vectorLegal = prop == "offset"
        if (!vectorLegal && (perTick?.size == 3 || to?.size == 3)) ctx.fail(path, "'$prop' is a number, not a vector")
        val ease = m["ease"]?.let { e ->
            (e as? String)?.also { if (Easing.fromKey(it) == null) ctx.fail("$path.ease", "unknown ease '$it'") } ?: ctx.mismatch("$path.ease", "ease name", e)
        }
        return PointLaw(
            law = law,
            prop = prop,
            at = ticks("at") ?: 0,
            forTicks = ticks("for"),
            perTick = perTick,
            accel = (m["accel"] as? Number)?.toDouble() ?: 0.0,
            to = to,
            ease = ease,
            exponent = (m["exponent"] as? Number)?.toDouble() ?: 1.0,
        )
    }

    override fun schema(): Map<String, Any?> = linkedMapOf(
        "type" to "array",
        "minItems" to 1,
        "items" to linkedMapOf("type" to "object", "required" to listOf("law"), "properties" to linkedMapOf(
            "law" to linkedMapOf("enum" to listOf("grow", "fade")),
            "prop" to linkedMapOf("enum" to props.toList()),
        )),
    )
}

/** Evaluation of [PointLaw]s on the property values of one occurrence (the occurrence's `tau` is [com.astarworks.astera.core.application.skillengine.compose.LowerContext.tau]). */
public object PointTau {
    /** The occurrence value of scalar property [prop]: [base] with the laws on it applied at [tau]; [base] itself when no law names the property. Null = [base] is not known at compile time. */
    public fun scalar(base: Val<Double>, laws: List<PointLaw>, prop: String, tau: Int): Val<Double>? {
        val mine = laws.filter { it.prop == prop }
        if (mine.isEmpty()) return base
        val b = (base as? Val.Known<Double>)?.value ?: return null
        return Val.Known(mine.fold(listOf(b)) { acc, law -> law.apply(acc, tau) }[0])
    }

    /** As [scalar] for a vector property. */
    public fun vector(base: Val<Vector3d>, laws: List<PointLaw>, prop: String, tau: Int): Val<Vector3d>? {
        val mine = laws.filter { it.prop == prop }
        if (mine.isEmpty()) return base
        val b = (base as? Val.Known<Vector3d>)?.value ?: return null
        val r = mine.fold(listOf(b.x, b.y, b.z)) { acc, law -> law.apply(acc, tau) }
        return Val.Known(Vector3d(r[0], r[1], r[2]))
    }
}
