package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.CastTrace
import com.astarworks.astera.core.application.skillengine.runtime.player.EventBinding
import com.astarworks.astera.core.application.skillengine.runtime.player.LiveValueSource
import com.astarworks.astera.core.application.skillengine.runtime.player.RowScope
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0303 1.1 `frame` op (W1-07). Runs at the wave 1b gate (needs the document decoder, unroller and compiler). */
class FrameOpScenarioTest : SkillEngineScenario() {

    // Instance ids are state-qualified (ADR-0303 C2; section 9): the `throw` state emits `throw/<path>`.

    /** [ops] are flow-style op lines; they become the `enter:` list of the single state. */
    private fun skill(vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: frame-probe", "item: couture-rapier",
            "anchors:", "  - {id: shears, anchor: caster.hand_r}",
            "machine:", "  start: throw", "  states:", "    throw:", "      for: 40", "      enter:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private fun compiled(vararg ops: String): CompiledSkill =
        (loadInline(skill(*ops)) as Result.Success).value

    private fun rows(s: CompiledSkill): List<Row> = s.table.states.flatMap { it.rows }

    /** Frame half of TimingBlockScenarioTest#familyTimesCreatesIndexedInstances: `times: 3` is three instances, indexed, with their own `i`. */
    @Test
    fun familyTimesCreatesIndexedInstances() {
        val s = compiled(
            "{id: blade, op: frame, times: 3, anchor: shears, turn: [{axis: up, deg: \"= i * 120\"}], for: 40}",
        )
        val spawns = rows(s).filter { (it.action as? Action.Spawn)?.kind == SpawnKind.FRAME }
        assertThat(spawns.map { it.instance.text }).containsExactly("throw/blade[0]", "throw/blade[1]", "throw/blade[2]")
        assertThat(spawns.map { it.tick }.toSet()).containsExactly(0)
        assertThat(spawns.map { (it.action as Action.Spawn).pose.rotation }.toSet()).hasSize(3)
        val ends = rows(s).filter { it.action is Action.End }
        assertThat(ends.map { it.instance.text to it.tick }).containsExactlyInAnyOrder("throw/blade[0]" to 40, "throw/blade[1]" to 40, "throw/blade[2]" to 40)
    }

    @Test
    fun frameAnchoredToFamilyMemberBindsSameIndex() {
        val s = compiled(
            "{id: blade, op: frame, times: 3, anchor: shears, for: 40}",
            "{id: tip, op: frame, times: 3, anchor: \"blade[i]\", offset: {forward: 1.0}, for: 40}",
        )
        val tips = rows(s).filter { (it.action as? Action.Spawn)?.kind == SpawnKind.FRAME && it.instance.text.startsWith("throw/tip") }
        assertThat(tips.map { it.instance.text to (it.action as Action.Spawn).anchor?.text })
            .containsExactly("throw/tip[0]" to "throw/blade[0]", "throw/tip[1]" to "throw/blade[1]", "throw/tip[2]" to "throw/blade[2]")
    }

    @Test
    fun bareFamilyNameAsParentIsLoadError() {
        val r = loadInline(
            skill(
                "{id: blade, op: frame, times: 3, anchor: shears, for: 40}",
                "{id: tip, op: frame, anchor: blade, for: 40}",
            ),
        )
        assertThat((r as Result.Failure).error).isInstanceOf(SkillDecodeError.Invalid::class.java)
    }

    @Test
    fun indexOutOfRangeIsLoadError() {
        val r = loadInline(
            skill(
                "{id: blade, op: frame, times: 3, anchor: shears, for: 40}",
                "{id: tip, op: frame, anchor: \"blade[3]\", for: 40}",
            ),
        )
        assertThat((r as Result.Failure).error).isInstanceOf(SkillDecodeError.UndefinedReference::class.java)
    }

    /** Motion laws of a frame read the skill's `${param}` numbers and the item-part axes (`edge`, `long`) at load; the table holds plain numbers. */
    private fun withParams(vararg ops: String): CompiledSkill =
        when (val r = loadInline(skill(*ops).replace("anchors:", "params:\n  spin_dpt: {type: number, default: 54}\n  open_deg: {type: number, default: 35}\nanchors:"))) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("load failed: ${r.error}")
        }

    private fun keysOf(s: CompiledSkill, id: String) =
        rows(s).filter { it.instance.text == id }.mapNotNull { r -> (r.action as? Action.Key)?.let { r.tick to it } }

    @Test
    fun frameSpinReadsParamAndKeysPoseResolvesItemEdgeAxis() {
        val s = withParams(
            "{id: spun, op: frame, anchor: shears, for: 40, motion: [{law: spin, axis: forward, deg_per_tick: \"\${spin_dpt}\", at: 0, for: 1}]}",
            "{id: open, op: frame, anchor: shears, part: blade_a, for: 40, motion: [{law: keys, keys: [{t: 0, pose: [{axis: edge, deg: 0}]}, {t: 6, pose: [{axis: edge, deg: 35}]}]}]}",
        )
        val tol = org.assertj.core.data.Offset.offset(1e-6)
        // spin: 54 deg about forward (0, 0, 1) after one tick, from ${spin_dpt}
        val spun = keysOf(s, "throw/spun").maxBy { it.first }.second.leftRotation as com.astarworks.astera.core.domain.skillengine.table.Val.Known
        assertThat(spun.value.z).isCloseTo(Math.sin(Math.toRadians(27.0)), tol)
        assertThat(spun.value.w).isCloseTo(Math.cos(Math.toRadians(27.0)), tol)
        // keys: 35 deg about blade_a.edge = (0.8087, -0.5882, 0) (content/frames/couture-rapier.yaml)
        val open = keysOf(s, "throw/open").maxBy { it.first }.second.leftRotation as com.astarworks.astera.core.domain.skillengine.table.Val.Known
        val h = Math.toRadians(17.5)
        val len = Math.sqrt(0.8087 * 0.8087 + 0.5882 * 0.5882)
        assertThat(open.value.x).isCloseTo(0.8087 / len * Math.sin(h), tol)
        assertThat(open.value.y).isCloseTo(-0.5882 / len * Math.sin(h), tol)
        assertThat(open.value.w).isCloseTo(Math.cos(h), tol)
    }

    /** The s2 blades: a spin frame carries the open pose of a part child (a spin and a keys pose on ONE frame overlap on the turn channel and fail the load). */
    @Test
    fun spinFrameCarriesPartChildWithKeysPose() {
        val s = withParams(
            "{id: spun, op: frame, anchor: shears, for: 40, motion: [{law: spin, axis: forward, deg_per_tick: \"\${spin_dpt}\", at: 0, for: 30}]}",
            "{id: open, op: frame, anchor: spun, part: blade_a, for: 40, motion: [{law: keys, keys: [{t: 0, pose: [{axis: edge, deg: 0}]}, {t: 4, pose: [{axis: edge, deg: 35}]}]}]}",
        )
        assertThat(keysOf(s, "throw/open")).isNotEmpty()
    }

    @Test
    fun itemAxisWithoutPartIsLoadError() {
        val r = loadInline(
            skill("{id: open, op: frame, anchor: shears, for: 40, motion: [{law: keys, keys: [{t: 0, pose: [{axis: edge, deg: 5}]}]}]}"),
        )
        assertThat((r as Result.Failure).error).isInstanceOf(SkillDecodeError.Invalid::class.java)
    }

    // ---- expressions name frame / projectile ids and their part points (ADR-0303 3.5, 1.1) ----

    private class Trace : CastTrace {
        override fun enter(cast: CastHandle, state: StatePath, tick: Int) = Unit
        override fun exit(cast: CastHandle, state: StatePath, reason: ExitReason, tick: Int) = Unit
        override fun event(cast: CastHandle, path: EventPath, tick: Int, index: Int?) = Unit
        override fun call(cast: CastHandle, callId: InstanceId, tick: Int) = Unit
    }

    /** Evaluates `"= ..."` cells with the row's frame lookup, as the Paper source does; everything else is the recorder's. */
    private class ExprLive(val rec: GatewayRecorder, val frames: Set<String>) : LiveValueSource by rec.live {
        private val cel = CelExprCompiler()
        private val vars = emptyMap<String, ExprValue>()

        private fun eval(name: String, scope: RowScope): ExprValue {
            val spec = com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings.spec(null, ExprEnvSpec(vars.mapValues { ExprType.VEC3 }, frames))
            val c = when (val r = cel.compile(name.removePrefix("= "), spec, null)) { is Result.Success -> r.value; is Result.Failure -> error("${r.error}") }
            return c.eval(ExprEnv(vars, scope.frames!!, scope.seed, null, scope.castSeed))
        }

        override fun num(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Double =
            if (name.startsWith("= ") && "caster" !in name) (eval(name, scope) as ExprValue.Num).value else rec.live.num(cast, name, binding, scope)

        override fun vec(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Vector3d =
            if (name.startsWith("= ") && "caster" !in name) (eval(name, scope) as ExprValue.Vec3).value.let { Vector3d(it.x, it.y, it.z) } else rec.live.vec(cast, name, binding, scope)
    }

    /** (tick, volume) of every sound the player plays up to [until]. */
    private fun volumes(s: CompiledSkill, frames: Set<String>, until: Int): List<Pair<Int, Double>> {
        val rec = GatewayRecorder()
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, ExprLive(rec, frames), Trace(), rec.movement, rec.items, rec.world)
        val request = CastRequest(s, rec.caster, WorldId("world"), Vec3(0.0, 0.0, 0.0), 0.0, 0.0, 1L, startTick = 0)
        player.start(request, s)
        val out = ArrayList<Pair<Int, Double>>()
        for (t in 0..until) {
            rec.tick = t
            val before = rec.calls.size
            player.tick(t)
            rec.calls.drop(before).filter { it.verb == "sound" }.forEach { out += t to Regex("vol=(\\S+)").find(it.payload)!!.groupValues[1].toDouble() }
        }
        assertThat(player.faults()).isEmpty()
        return out
    }

    private fun sound(id: String, at: Int, volume: String) = "{id: $id, op: sound, sound: block.note_block.hat, anchor: shears, volume: \"$volume\", at: $at}"

    @Test
    fun expressionNamesFrameOpIdAndItsPartPoint() {
        val s = withParams(
            "{id: spun, op: frame, anchor: shears, for: 40, motion: [{law: spin, axis: forward, deg_per_tick: \"\${spin_dpt}\", at: 0, for: 30}]}",
            "{id: piece_a, op: frame, anchor: spun, part: blade_a, for: 40}",
            sound("snd0", 0, "= speed(piece_a.tip)"),
            sound("snd5", 5, "= speed(piece_a.tip)"),
        )
        val v = volumes(s, setOf("spun", "piece_a", "piece_a.tip"), 6).toMap()
        // nothing has moved on the spawn tick; later the tip sweeps a chord of the 54 degree turn per tick around the spin axis (the hand)
        assertThat(v[0]).isEqualTo(0.0)
        assertThat(v[5]).isGreaterThan(0.1)
    }

    @Test
    fun expressionNamesProjectileIdVelocity() {
        val s = compiled(
            "{id: volley, op: projectile, origin: shears, velocity: \"= vec(0.0, 0.0, 1.0)\", radius: 0.4, hit: {targets: enemies, once: true}, block: {mode: despawn}, for: 20}",
            sound("snd", 3, "= speed(volley)"),
        )
        val v = volumes(s, setOf("volley"), 4)
        assertThat(v).hasSize(1)
        assertThat(v.single().second).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6))
    }

    @Test
    fun projectileMotionSpinReadsParam() {
        val s = withParams("{id: volley, op: projectile, origin: shears, velocity: \"= vec(0.0, 0.0, 1.0)\", radius: 0.4, hit: {targets: enemies, once: true}, block: {mode: despawn}, for: 20, motion: [{law: spin, axis: forward, deg_per_tick: \"\${spin_dpt}\", at: 0, for: 5}]}")
        assertThat(rows(s).any { it.action is Action.Spawn }).isTrue()
    }

    @Test
    fun partPointOnPartlessFrameIsLoadError() {
        val r = loadInline(skill("{id: f, op: frame, anchor: shears, for: 40}", sound("snd", 0, "= speed(f.tip)")))
        assertThat(r).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun unknownFrameIdInExpressionIsLoadError() {
        val r = loadInline(skill("{id: f, op: frame, anchor: shears, for: 40}", sound("snd", 0, "= speed(nosuch)")))
        assertThat(r).isInstanceOf(Result.Failure::class.java)
    }
}
