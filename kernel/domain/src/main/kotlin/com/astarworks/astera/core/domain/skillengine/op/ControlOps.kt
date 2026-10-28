package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Flow, time, events, cooldowns, items. Family owner: cast runner worker. */
public sealed interface ControlOp : GameplayOp {

    /** Stop the clock of the listed frame subtrees (hit-stop). */
    public data class Freeze(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val ticks: Value<Double>,
        val scope: List<String>,
    ) : ControlOp

    /** Raise [event] after a delay. */
    public data class Schedule(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val afterTicks: Value<Double>,
        val event: String,
    ) : ControlOp

    /** Raise [event] now. */
    public data class RaiseEvent(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val event: String,
    ) : ControlOp

    public data class CastSkill(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val skill: String,
    ) : ControlOp

    public data class If(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val condition: Value<Boolean>,
        val then: List<GameplayOp>,
        val otherwise: List<GameplayOp>,
    ) : ControlOp

    public data class Loop(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val count: Value<Double>,
        val everyTicks: Int,
        val body: List<GameplayOp>,
    ) : ControlOp

    /** Run [body] once, [afterTicks] later, in the scope (victim binding) the op ran in. */
    public data class Delay(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val afterTicks: Value<Double>,
        val body: List<GameplayOp>,
    ) : ControlOp

    /**
     * Event-sourced window: for [ticks] ticks, [listen] on the caster is watched. Each occurrence is passed through
     * [filter] (CEL, `incoming.amount` and `victim` = the attacker bound); on a pass the incoming damage is
     * multiplied by [scale] (0 = negated, [absorb]) and [body] runs once with the attacker as the current victim on the
     * next cast tick. [once] closes the window after the first pass. The cast stays alive while a window is open.
     */
    public data class Window(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val listen: WindowEvent,
        val ticks: Value<Double>,
        val once: Boolean,
        val absorb: Boolean,
        val scale: Double,
        val filter: Value<Boolean>?,
        val body: List<GameplayOp>,
    ) : ControlOp

    public data class Choose(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val cases: List<Pair<Value<Boolean>, List<GameplayOp>>>,
        val otherwise: List<GameplayOp>,
    ) : ControlOp

    public data class WeightedBranch(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val branches: List<Pair<Value<Double>, List<GameplayOp>>>,
    ) : ControlOp

    public data class ForEach(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val targets: TargetRef,
        val body: List<GameplayOp>,
    ) : ControlOp

    public data class Counter(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val key: String,
        val delta: Value<Double>,
    ) : ControlOp

    public data class SetCooldown(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val skill: String,
        val ticks: Value<Double>,
    ) : ControlOp

    public data class GiveItem(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val item: String,
        val count: Value<Double>,
    ) : ControlOp

    public data class ConsumeItem(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val item: String,
        val count: Value<Double>,
    ) : ControlOp
}

/** What a [ControlOp.Window] listens for on the caster. */
public enum class WindowEvent(public val key: String) {
    /** A hit from another entity reaches the caster through the damage pipeline. */
    INCOMING_HIT("incoming_hit"),
    ;

    public companion object {
        public fun of(key: String): WindowEvent? = entries.firstOrNull { it.key == key }
    }
}
