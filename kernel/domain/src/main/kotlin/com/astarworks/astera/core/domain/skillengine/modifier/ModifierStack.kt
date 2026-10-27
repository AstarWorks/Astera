package com.astarworks.astera.core.domain.skillengine.modifier

/** `expose:` entry: the only parameters runtime modifiers may touch (design 3.11). */
public data class ExposedParam(
    val name: String,
    /** Name in `gameplay.params` this exposed value is derived from. */
    val param: String,
    val min: Double? = null,
    val max: Double? = null,
)

public enum class ModOp { ADD, MULTIPLY, SET }

/** One modifier from a status / item / mode, targeting an exposed name. */
public data class ParamModifier(
    val sourceId: String,
    val target: String,
    val op: ModOp,
    val value: Double,
)

/**
 * Immutable modifier set, snapshotted at cast start (F-12). Order: ADD (sum) -> MULTIPLY (product)
 * -> SET (last one wins, applied after the others) -> clamp to the exposed range.
 */
public data class ModifierStack(val modifiers: List<ParamModifier> = emptyList()) {
    public fun plus(m: ParamModifier): ModifierStack = ModifierStack(modifiers + m)

    public fun apply(exposed: ExposedParam, base: Double): Double {
        val mine = modifiers.filter { it.target == exposed.name }
        var v = base + mine.filter { it.op == ModOp.ADD }.sumOf { it.value }
        for (m in mine) if (m.op == ModOp.MULTIPLY) v *= m.value
        mine.lastOrNull { it.op == ModOp.SET }?.let { v = it.value }
        exposed.min?.let { v = maxOf(v, it) }
        exposed.max?.let { v = minOf(v, it) }
        return v
    }

    public companion object {
        public val EMPTY: ModifierStack = ModifierStack()
    }
}
