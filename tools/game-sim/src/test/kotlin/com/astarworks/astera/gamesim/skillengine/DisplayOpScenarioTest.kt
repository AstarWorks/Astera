package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.DisplayView
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
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

    @Test
    fun shippedGlowComparisonPlaysAllFourRoutesAndRemovesDisplays() {
        val loaded = loadInline(java.nio.file.Files.readString(contentDir.resolve("vfx/demo-glow-comparison.yaml")))
        val skill = (loaded as? Result.Success)?.value ?: throw AssertionError("glow demo did not load: $loaded")
        val calls = play(skill, 7L)
        val spawns = calls.filter { it.verb == "spawn" }
        assertThat(spawns).hasSize(18)
        assertThat(spawns.count { "glowing=true" in it.tokens }).isEqualTo(12)
        assertThat(spawns.count { "shader=emissive-pulse" in it.tokens }).isEqualTo(6)
        val particles = calls.filter { it.verb == "particle" }
        assertThat(particles).hasSize(2)
        assertThat(particles.all { "type=dust" in it.tokens && "count=40" in it.tokens }).isTrue()
        assertThat(calls.filter { it.verb == "remove" }).hasSize(18)
    }

    @Test
    fun displayGlowingAndColourReachRuntimeIndependently() {
        val s = compiled(
            "{id: a, op: display, kind: block, anchor: caster, block: minecraft:white_concrete, for: 20, glowing: true, glow_color: '#00ffff'}",
            "{id: b, op: display, kind: block, anchor: caster, block: minecraft:white_concrete, for: 20, glow_color: '#ff0000'}",
            "{id: c, op: display, kind: block, anchor: caster, block: minecraft:white_concrete, for: 20, glowing: true}",
        )
        val spawn = play(s, 7L).filter { it.verb == "spawn" }
        assertThat(spawn).hasSize(3)
        assertThat(spawn[0].payload).contains("glowing=true", "glow=#00ffff")
        assertThat(spawn[1].payload).contains("glow=#ff0000").doesNotContain("glowing=true")
        assertThat(spawn[2].payload).contains("glowing=true", "glow=-")
        for (bad in listOf("glowing: 1", "glowing: 'yes'", "glow_color: '#f00'", "glow_color: '#ff00ffff'")) {
            assertThat(loadInline(skill("{id: bad, op: display, kind: block, anchor: caster, block: minecraft:stone, for: 10, $bad}")))
                .describedAs(bad).isInstanceOf(Result.Failure::class.java)
        }
    }

    @Test
    fun framePlacedGlowKeepsSharedMetadataAndFollowsTheFrame() {
        val yaml = skill(
            "{id: a, op: display, kind: block, frame: glow, pos: {v: 1}, block: minecraft:white_concrete, for: 20, glowing: true, glow_color: '#00ffff'}",
            "{id: b, op: display, kind: text, frame: glow, pos: {v: 2}, text: glow, for: 20, glowing: true, glow_color: '#ff0000'}",
        ).replace("machine:", "frames:\n  - {id: glow, origin: caster, v: forward, w: up, s: v x w, move: [{at: 0, for: 10, to: {v: 2}}]}\nmachine:")
        val loaded = loadInline(yaml)
        val compiled = (loaded as? Result.Success)?.value ?: throw AssertionError("frame glow did not load: $loaded")
        val rec = GatewayRecorder()
        val textViews = mutableListOf<DisplayView>()
        val visual = object : ISkillVisualGateway by rec.visual {
            override fun spawnText(cast: CastRef, text: String, backgroundArgbHex: String, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView) =
                rec.visual.spawnText(cast, text, backgroundArgbHex, shadow, seeThrough, pose, brightness, view).also { textViews += view }
        }
        val player = playProbe(compiled, ticks = 102, rec = rec, visual = visual)
        assertThat(player.faults()).isEmpty()
        assertThat(textViews).containsExactly(DisplayView(glowing = true, glowColor = "#ff0000"))
        val spawns = rec.calls.filter { it.verb == "spawn" }
        assertThat(spawns).hasSize(2)
        assertThat(spawns.first().payload).contains("glowing=true", "glow=#00ffff")
        assertThat(rec.writtenPoses.filter { it.first in 1..10 }.map { it.third.frame.origin.z }.distinct().size).isGreaterThan(1)
        assertThat(rec.calls.filter { it.verb == "remove" }).hasSize(2)
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
    fun alignAcceptsNamedDirectionAndUp() {
        // `out` (0301 short name) is `out_quad` outside a keys segment (ADR-0303 3.7). R42: `to` may be a named direction and `up` a named direction; the lowering writes the live rotation with both
        val s = compiled(
            "{id: pose, op: frame, anchor: caster, for: 34, motion: [{law: align, axis: forward, to: world.up, up: caster.forward, at: 0, for: 6, ease: out_quad}]}",
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

}
