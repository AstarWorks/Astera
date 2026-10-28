package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0303 W2-08 (R19 R23 R27 R29 R30 R33): display `flipbook` / `color` / `see_through`, keys `alpha` / `color`, `frame` `axes` and
 * `caster.look`, `rig` `of` / `reset`. Needs the Wave 1b document decoder + compiler and a `SpriteCatalog` with `frames()` (sprite
 * `arcane-slash`, 6 frames) in the harness.
 */
class DisplayOpScenarioTest : SkillEngineScenario() {

    private fun skill(vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: display-probe", "item: couture-rapier",
            "machine:", "  start: s", "  states:", "    s:", "      for: 100", "      enter:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private fun compiled(vararg ops: String): CompiledSkill =
        when (val r = loadInline(skill(*ops))) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("did not load: ${r.error}")
        }

    private fun rows(s: CompiledSkill): List<Row> = s.table.states.flatMap { it.rows }

    private fun known(v: Val<*>?): Any? = (v as? Val.Known<*>)?.value

    @Test
    fun flipbookLowersSetRows() {
        val s = compiled("{id: fx, op: display, kind: sprite, sprite: arcane-slash, anchor: caster, size: [1, 1], flipbook: {frames: 3, every: 10, loop: true}, for: 40}")
        val sets = rows(s).filter { (it.action as? Action.Set)?.field == "text" }
        assertThat(sets.map { it.tick }).containsExactly(10, 20, 30)
    }

    @Test
    fun keysAlphaOneWritePerTick() {
        val s = compiled("{id: t, op: display, kind: text, text: hi, anchor: caster, motion: [{law: keys, keys: [{t: 0, alpha: 0}, {t: 5, alpha: 1}]}], for: 20}")
        val fades = rows(s).filter { (it.action as? Action.Set)?.field == "text_opacity" }
        assertThat(fades.map { it.tick }).doesNotHaveDuplicates()
        assertThat(fades.size).isGreaterThanOrEqualTo(5)
    }

    @Test
    fun keysColorLowersToColorSetPerTick() {
        val s = compiled("{id: t, op: display, kind: text, text: hi, anchor: caster, motion: [{law: keys, keys: [{t: 0, color: \"#000000\"}, {t: 2, color: \"#ff0000\"}]}], for: 20}")
        val colors = rows(s).filter { (it.action as? Action.Set)?.field == "color" }
        assertThat(colors.map { it.tick }).containsExactly(0, 1, 2)
        assertThat(known((colors.last().action as Action.Set).value)).isEqualTo("#ff0000")
    }

    @Test
    fun spriteSeeThrough() {
        val s = compiled("{id: q, op: display, kind: sprite, sprite: arcane-slash, anchor: caster, size: [1, 1], see_through: true, color: \"#d9ff3b2f\", for: 10}")
        val look = (rows(s).first { (it.action as? Action.Spawn)?.kind == SpawnKind.SPRITE }.action as Action.Spawn).look.fields
        assertThat(known(look["see_through"])).isEqualTo(true)
        assertThat(known(look["color"])).isEqualTo("#d9ff3b2f")
        val bad = loadInline(skill("{id: b, op: display, kind: block, block: stone, anchor: caster, see_through: true, for: 10}"))
        assertThat(bad).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun frameAxesCaster() {
        val s = compiled("{id: f, op: frame, anchor: world, axes: caster, for: 10}")
        val look = (rows(s).first { (it.action as? Action.Spawn)?.kind == SpawnKind.FRAME }.action as Action.Spawn).look.fields
        assertThat(known(look["axes"])).isEqualTo("caster")
        assertThat(loadInline(skill("{id: f, op: frame, anchor: world, axes: sideways, for: 10}"))).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun casterLookCarriesPitch() {
        // lowering keeps the `look` point on the anchor name; the Paper LiveValueSource resolves it to a feet-origin yaw+pitch frame
        val s = compiled("{id: f, op: frame, anchor: caster.look, for: 10}")
        val spawn = rows(s).first { (it.action as? Action.Spawn)?.kind == SpawnKind.FRAME }.action as Action.Spawn
        assertThat(spawn.anchorName).isEqualTo("caster.look")
    }

    @Test
    fun rigOfFrameDrivesDisplays() {
        val s = compiled(
            "{id: slam, op: frame, anchor: caster, for: 40}",
            "{id: r, op: rig, parent: caster, of: slam, for: 40}",
        )
        assertThat(rows(s).any { it.action is Action.Effect }).isTrue()
        val missing = loadInline(skill("{id: r, op: rig, parent: caster, of: nowhere, for: 40}"))
        assertThat((missing as Result.Failure).error).isInstanceOf(SkillDecodeError.UndefinedReference::class.java)
    }

    @Test
    fun rigResetIsZeroDurationWrite() {
        val s = compiled(
            "{id: r, op: rig, parent: caster, for: 80, swing: [" +
                "{at: 4, for: 6, from: {left: -1, up: 1, forward: 1}, to: {left: 1, up: -1, forward: 1}, edge: leading}, {at: 67, reset: true}]}",
        )
        assertThat(rows(s).any { it.action is Action.Effect }).isTrue()
    }

    @Test
    fun rigWithoutOfPlaysTheHeldItemThroughTheRealPlayer() {
        // v2 RigPerformer: no `of` = the rig is its own frame and the main-hand item rides it; it is posed every tick and removed with the rig
        val s = compiled(
            "{id: r, op: rig, parent: caster, pivot: {left: 0.0, up: 1.4, forward: 0.3}, for: 12, " +
                "swing: [{at: 2, for: 3, from: {left: -1, up: 1, forward: 1}, to: {left: 1, up: -1, forward: 1}, edge: leading}]}",
        )
        val rec = GatewayRecorder()
        val player = playProbe(s, ticks = 14, rec = rec)
        assertThat(player.faults()).isEmpty()
        assertThat(rec.calls.filter { it.verb == "spawn" }.map { it.payload }).containsExactly("kind=item")
        assertThat(rec.calls.count { it.verb == "write" }).isGreaterThanOrEqualTo(12)
        assertThat(rec.calls.single { it.verb == "remove" }.tick).isEqualTo(12)
    }

    @Test
    fun rigCarriesPivotTiltFreezesAndSampleTicks() {
        // the player plays these 1:1 as v2 RigMotion (chunked slerp, hit-stop clock); the lowering must hand every field over
        val s = compiled(
            "{id: r, op: rig, parent: caster, pivot: {left: 0.0, up: 1.4, forward: 0.3}, sample_ticks: 1, respect_hit_stop: true, freezes: [{at: 7, ticks: 2}], for: 20, " +
                "swing: [{at: 2, for: 3, from: {left: -1, up: 1, forward: 1}, to: {left: 1, up: -1, forward: 1}, edge: leading}]}",
        )
        val payload = (rows(s).first { it.action is Action.Effect }.action as Action.Effect).payload
        assertThat(payload.keys).contains("pivot", "tilt", "rest", "sample_ticks", "freezes", "respect_hit_stop", "swing")
        assertThat(((payload.getValue("freezes") as Val.Known).value as List<*>)).hasSize(1)
    }

    @Test
    fun alignAcceptsNamedDirectionAndUp() {
        // `out` (0301 short name) is `out_quad` outside a keys segment (ADR-0303 3.7). R42: `to` may be a named direction and `up` a named direction; the lowering writes the live rotation with both
        val s = compiled(
            "{id: pose, op: frame, anchor: caster, for: 34, motion: [{law: align, axis: forward, to: world.up, up: caster.forward, step: 1, at: 0, for: 6, ease: out_quad}]}",
        )
        val live = rows(s).mapNotNull { (it.action as? Action.Key)?.leftRotation as? Val.Live }
        assertThat(live).isNotEmpty()
        assertThat(live.first().name).contains("world.up").contains("caster.forward")
        // velocity form: `up` other than world.up is a load error
        val bad = loadInline(
            skill("{id: p, op: frame, anchor: caster, for: 20, motion: [{law: move, velocity: {forward: 1}, at: 0, for: 10}, {law: align, axis: forward, to: velocity, up: caster.up, at: 0, for: 10}]}"),
        )
        assertThat(bad).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun frameSnapshotAtTick() {
        val s = compiled("{id: tele, op: frame, at: 20, anchor: caster, mode: snapshot, for: 20}")
        val spawn = rows(s).first { (it.action as? Action.Spawn)?.kind == SpawnKind.FRAME }
        assertThat(spawn.tick).isEqualTo(20)
        assertThat(rows(s).first { it.action is Action.End }.tick).isEqualTo(40)
    }
}
