package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.CancelScope
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * W1b-02i (R40, R9): every outbound port call carries the [com.astarworks.astera.core.application.skillengine.runtime.port.CastRef]
 * of its cast, the dash and reel drivers move the caster through the movement port one step per tick, and a cancelled cast
 * hands its pending world edits back.
 */
class PortsScenarioTest : SkillEngineScenario() {
    private val end = WorldId("the_end")

    private fun compile(yaml: String): CompiledSkill =
        when (val r = loadInline(yaml)) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("did not load: ${r.error}")
        }

    private class Run(val player: TablePlayer, val cast: CastHandle, val rec: GatewayRecorder) {
        fun tick(now: Int) {
            rec.tick = now
            player.tick(now)
        }
    }

    private fun start(skill: CompiledSkill, rec: GatewayRecorder, world: WorldId): Run {
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
        val request = CastRequest(skill, rec.caster, world, Vec3(0.0, 0.0, 0.0), 0.0, 0.0, 1L, startTick = 0)
        val cast = when (val s = player.start(request, skill)) {
            is Result.Success -> s.value
            is Result.Failure -> throw AssertionError("start failed: ${s.error}")
        }
        return Run(player, cast, rec)
    }

    private val dashSkill = """
        apiVersion: astera/v3
        kind: Skill
        id: ports-dash
        item: couture-rapier
        machine:
          start: go
          states:
            go:
              for: 12
              do:
                - {id: dash, op: dash, at: 1, speed: 1.5, for: 6, follow_pitch: false, stop_on_block: true, invulnerable: true, face_movement: true}
                - {id: bang, op: sound, sound: block.note_block.hat, anchor: caster, at: 0}
                - {id: mote, op: particle, particle: crit, anchor: caster, count: 2, at: 0}
                - {id: disc, op: display, kind: sprite, sprite: couture-s2-petal-spark, anchor: caster, billboard: center, size: [0.5, 0.5], for: 6, at: 0}
    """.trimIndent()

    @Test
    fun everyPortCallCarriesCastWorld() {
        val rec = GatewayRecorder()
        val run = start(compile(dashSkill), rec, end)
        for (now in 0..10) run.tick(now)
        val refs = rec.castRefs
        assertThat(refs.map { it.first }.toSet()).contains(
            "visual.spawn.sprite", "particles.emit", "sounds.play", "query.rootOf",
            "movement.teleport", "movement.probeBlock", "movement.invulnerable", "movement.faceMovement",
        )
        assertThat(refs.map { it.second.world }.toSet()).containsExactly(end)
        assertThat(refs.map { it.second.caster }.toSet()).containsExactly(rec.caster)
        assertThat(refs.map { it.second.castId }.toSet()).hasSize(1)
        assertThat(run.player.faults()).isEmpty()
    }

    @Test
    fun dashDriverTeleportsPerTick() {
        val rec = GatewayRecorder()
        val run = start(compile(dashSkill), rec, WorldId("world"))
        for (now in 0..10) run.tick(now)
        val moves = rec.calls.filter { it.verb == "move" && it.payload.startsWith("to=") }
        // speed 1.5 for 6 ticks starting at tick 1: one teleport per tick, ticks 1..6, ending 9 blocks ahead (+z at yaw 0)
        assertThat(moves.map { it.tick }).containsExactly(1, 2, 3, 4, 5, 6)
        assertThat(moves.last().payload).isEqualTo("to=0,0,9")
        assertThat(rec.calls.filter { it.verb == "move" }.map { it.payload }).contains("invulnerable=8", "face", "invulnerable=0")
        assertThat(run.player.faults()).isEmpty()
    }

    private val reelSkill = """
        apiVersion: astera/v3
        kind: Skill
        id: ports-reel
        item: couture-rapier
        anchors:
          - {id: pulled, anchor: caster}
          - {id: goal, anchor: caster, offset: {forward: 4}}
        machine:
          start: go
          states:
            go:
              for: 16
              do:
                - {id: reel, op: reel, anchor: pulled, to: goal, speed: 1.0, at: 1}
    """.trimIndent()

    @Test
    fun reelDriverEndsOnArrive() {
        val rec = GatewayRecorder()
        val run = start(compile(reelSkill), rec, WorldId("world"))
        for (now in 0..12) run.tick(now)
        assertThat(rec.trace.filter { it.startsWith("event:reel.end") }).hasSize(1)
        assertThat(run.player.faults()).isEmpty()
    }

    private val worldSkill = """
        apiVersion: astera/v3
        kind: Skill
        id: ports-world
        anchors:
          - {id: body, anchor: caster}
        gameplay:
          timeline:
            - id: t0
              at: 0
              do:
                - {id: b, op: break_area, anchor: body, shape: {sphere: {radius: 1.5, ahead: 0.0}}, tool: none, block_damage: 100, ripple: 0, revert: 200, outside_arena: false}
    """.trimIndent()

    @Test
    fun worldRevertAllOnCancel() {
        val rec = GatewayRecorder()
        val cells = (-2..2).flatMap { x -> (-2..3).flatMap { y -> (-2..2).map { z -> BlockCell(x, y, z) } } }
        cells.forEach { rec.world.setBlock(it, "minecraft:stone") }
        val run = start(compile(worldSkill), rec, WorldId("world"))
        run.tick(0)
        val broken = cells.filter { rec.world.block(it) == null }
        assertThat(broken).describedAs("blocks broken by the cast").isNotEmpty()
        run.player.cancel(run.cast, CancelScope.Cast)
        assertThat(cells).allSatisfy { assertThat(rec.world.block(it)).isEqualTo("minecraft:stone") }
        assertThat(run.player.faults()).isEmpty()
    }
}
