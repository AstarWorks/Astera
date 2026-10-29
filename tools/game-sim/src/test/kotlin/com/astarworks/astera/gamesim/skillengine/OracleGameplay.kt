package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.HitWindow
import com.astarworks.astera.core.domain.skillengine.table.LockEnd
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * The gameplay half of the skill oracle golden: what a skill does to the world, read statically from its expansion table so it does not
 * depend on a dummy being in reach. Every line is
 *
 *     g[.<handler>] @<tick> <kind> <value...> | <scope>
 *
 * The comparison key is everything before ` | `: the handler kind (`hit`, `block`, `expire`, ... = the event name after its last `.`; absent
 * for a state row), the tick (relative to the state entry for a state row, to the event for a handler row), the kind and the value. The
 * scope after the bar (state path / full event path) and op ids never enter the key, so a re-author that renames ops or moves a row
 * between equivalent states still compares equal. Kinds: `damage <attr>=<amount> [every=N] [to=..]`, `status <id> stacks=.. for=..`,
 * `heal` / `impulse` / `knockback` / `resource` / `modify_attribute` / `dash` / `reel` / ... (`<kind> k=v ...`), `world <op> ..`, `lock ..`,
 * `call skill:<id>`, `cancel <scope>`, `state-end dur=.. then=.. final=..`, `handler window=a..b max=n`, `cast-length min..max`.
 * Visual effects (body clips, held model, veil, flash, rig) are not here. Rows from the runtime replay (`g @T state-exit ...`, `g @T cast-end`)
 * are added per pose by the test.
 */
internal object OracleGameplay {
    /** Effect ops that only look; everything else on [Action.Effect] is gameplay. */
    private val visualEffects = setOf("body", "held_model", "screen_veil", "screen_flash", "stop_sound", "rig", "hurt", "camera_shake", "body_stop")

    fun lines(skill: CompiledSkill): List<String> {
        val t = skill.table
        val out = ArrayList<String>()
        out += "g @${t.bounds.path.min}..${t.bounds.path.max} cast-length | cast"
        for (st in t.states) {
            val dur = when (val d = st.duration) { is Duration.Fixed -> "${d.ticks}"; is Duration.Bounded -> "${d.range.min}..${d.range.max}" }
            out += "g @$dur state-end then=${st.then?.value ?: "-"} final=${st.isFinal} | state=${st.state.value}"
            st.rows.forEach { out += rowLines(null, it, "state=${st.state.value}") }
        }
        t.worldRows.forEach { out += rowLines(null, it, "world") }
        for (sub in t.subTables.values.sortedBy { it.event.value }) {
            val h = sub.event.value.substringAfterLast('.')
            // `exit[...]` subs are state-exit bookkeeping: their rows count, an empty one adds no header
            if (!h.startsWith("exit[")) out += "g.${h.substringBefore("[")} @${sub.window.min}..${sub.window.max} handler max=${sub.maxFirings} | sub=${sub.event.value}"
            sub.rows.forEach { out += rowLines(h.substringBefore("["), it, "sub=${sub.event.value}") }
        }
        return out
    }

    private fun rowLines(handler: String?, r: Row, scope: String): List<String> {
        val head = "g" + (handler?.let { ".$it" } ?: "") + " @${r.tick} "
        val tail = " | $scope"
        val guard = r.guard?.let { " when" } ?: ""
        return when (val a = r.action) {
            is Action.Hit -> {
                val every = (a.window as? HitWindow.Every)?.let { " every=${it.ticks}" } ?: ""
                a.damage.map { head + "damage ${it.attribute.name.lowercase()}=${num(it.amount)}$every$guard" + tail }
            }
            is Action.Damage -> a.components.map { head + "damage ${component(it)}${target(a.targets)}$guard" + tail }
            is Action.Status -> listOf(head + "status ${a.status} stacks=${num(a.stacks)} for=${a.forTicks}${target(a.targets)}$guard" + tail)
            is Action.Effect -> {
                // dash / reel / rig carry the author's op id as `op`; their kind rides in the payload
                val kind = (a.payload["kind"] as? Val.Known<*>)?.value?.toString() ?: a.op.value
                if (kind in visualEffects || kind.startsWith("dump:")) emptyList()
                else listOf(head + kind + a.payload.entries.filter { it.key != "kind" }.sortedBy { it.key }.joinToString("") { " ${it.key}=${v(it.value)}" } + guard + tail)
            }
            is Action.World -> listOf(head + "world ${a.op.value}" + a.payload.entries.sortedBy { it.key }.joinToString("") { " ${it.key}=${v(it.value)}" } + guard + tail)
            is Action.Lock -> listOf(head + "lock move=${a.move} pitch=${a.pitch} yaw=${a.yaw} until=" + (a.until.let { if (it is LockEnd.AtTick) "${it.tick}" else "cast" }) + tail)
            is Action.Call -> (a.callee as? com.astarworks.astera.core.domain.skillengine.table.Callee.SkillRef)?.let { listOf(head + "call skill:${it.id}" + tail) } ?: emptyList()
            is Action.Cancel -> listOf(head + "cancel ${a.scope.let { if (it is com.astarworks.astera.core.domain.skillengine.table.CancelScope.Instance) "instance" else it.toString().substringAfterLast('.').lowercase() }}" + tail)
            else -> emptyList()
        }
    }

    private fun component(c: DamageComponent): String {
        val base = c.base
        val mult = c.multiplier
        val amount = if (base is NumericValue.Literal && mult is NumericValue.Literal) OracleFmt.f(base.value * mult.value)
        else "cel(" + OracleFmt.s((base as? NumericValue.Cel)?.script ?: OracleFmt.f((base as NumericValue.Literal).value)) + ")" +
            (if (mult is NumericValue.Literal && mult.value == 1.0) "" else "*" + ((mult as? NumericValue.Cel)?.script?.let { OracleFmt.s(it) } ?: OracleFmt.f((mult as NumericValue.Literal).value)))
        val stack = c.perStack?.let { " perstack=${it.statusId.value}x${OracleFmt.f(it.amount)}/${it.on.name.lowercase()}" } ?: ""
        return "${c.attribute.name.lowercase()}=$amount$stack"
    }

    private fun target(t: TargetRule): String = when (t) {
        is TargetRule.Entity -> if (t.ref == "event.victim") "" else " to=${t.ref}"
        is TargetRule.Area -> " to=${t.kind}@${num(t.radius)}" + (t.maxCount?.let { "x$it" } ?: "") + (if (t.includeSelf) "+self" else "")
    }

    private fun num(n: Val<Number>): String = when (n) {
        is Val.Known -> OracleFmt.f(n.value.toDouble())
        is Val.Live -> "live(" + OracleFmt.s(n.name) + ")"
    }

    private fun v(x: Any?): String = when (x) {
        is Val.Known<*> -> v(x.value)
        is Val.Live<*> -> "live(" + OracleFmt.s(x.name) + ")"
        is Double -> OracleFmt.f(x)
        is Vector3d -> "${OracleFmt.f(x.x)},${OracleFmt.f(x.y)},${OracleFmt.f(x.z)}"
        is TargetRule -> target(x).removePrefix(" to=").ifEmpty { "victim" }
        is String -> OracleFmt.s(x)
        is List<*> -> x.joinToString(";") { v(it) }
        else -> OracleFmt.s(x.toString())
    }
}
