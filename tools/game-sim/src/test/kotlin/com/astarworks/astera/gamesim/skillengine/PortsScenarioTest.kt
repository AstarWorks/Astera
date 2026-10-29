package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.CancelScope
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

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
                - {id: dash, op: dash, at: 1, speed: 1.5, for: 6, direction: {forward: 1}, follow_pitch: false, stop_on_block: true, invulnerable: true, face_movement: true}
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
        machine:
          start: go
          states:
            go:
              for: 16
              do:
                - {id: pulled, op: frame, anchor: caster, mode: snapshot, for: 16}
                - {id: goal, op: frame, anchor: caster, offset: {forward: 4}, mode: snapshot, for: 16}
                - {id: reel, op: reel, anchor: pulled, to: goal, speed: 1.0, at: 1}
    """.trimIndent()

    @Test
    fun reelDriverEndsOnArrive() {
        val rec = GatewayRecorder()
        val run = start(compile(reelSkill), rec, WorldId("world"))
        for (now in 0..12) run.tick(now)
        assertThat(rec.trace.filter { it.startsWith("event:go/reel.end") }).hasSize(1)
        assertThat(run.player.faults()).isEmpty()
    }

    private val worldSkill = """
        apiVersion: astera/v3
        kind: Skill
        id: ports-world
        anchors:
          - {id: body, anchor: caster}
        machine:
          start: s
          states:
            s:
              for: 20
              do:
                - {id: b, op: break_area, anchor: body, shape: {sphere: {radius: 1.5, ahead: 0.0}}, tool: pickaxe, block_damage: 100, ripple: 0, revert: 200, outside_arena: false}
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

    private fun startIn(player: TablePlayer, skill: CompiledSkill, rec: GatewayRecorder, world: WorldId): CastHandle {
        val request = CastRequest(skill, rec.caster, world, Vec3(0.0, 0.0, 0.0), 0.0, 0.0, 1L, startTick = 0)
        return when (val s = player.start(request, skill)) {
            is Result.Success -> s.value
            is Result.Failure -> throw AssertionError("start failed: ${s.error}")
        }
    }

    private fun worldBreak(id: String, radius: Double): String = """
        apiVersion: astera/v3
        kind: Skill
        id: $id
        anchors:
          - {id: body, anchor: caster}
        machine:
          start: s
          states:
            s:
              for: 20
              do:
                - {id: b, op: break_area, anchor: body, shape: {sphere: {radius: $radius, ahead: 0.0}}, tool: pickaxe, block_damage: 100, ripple: 0, revert: 200, outside_arena: false}
    """.trimIndent()

    @Test
    fun worldRevertOnlyCancelledCast() {
        val rec = GatewayRecorder()
        val cells = (-4..4).flatMap { x -> (-4..4).flatMap { y -> (-4..4).map { z -> BlockCell(x, y, z) } } }
        cells.forEach { rec.world.setBlock(it, "minecraft:stone") }
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
        val small = startIn(player, compile(worldBreak("ports-world-small", 1.5)), rec, WorldId("world"))
        val large = startIn(player, compile(worldBreak("ports-world-large", 3.0)), rec, WorldId("world"))
        player.tick(0)
        val inner = BlockCell(0, 0, 0)
        val outer = BlockCell(2, 0, 0)
        assertThat(rec.world.block(inner)).isNull()
        assertThat(rec.world.block(outer)).isNull()
        player.cancel(small, CancelScope.Cast)
        // the small cast broke the inner cells first: they come back; the large cast's ring stays broken
        assertThat(rec.world.block(inner)).isEqualTo("minecraft:stone")
        assertThat(rec.world.block(outer)).isNull()
        player.cancel(large, CancelScope.Cast)
        assertThat(rec.world.block(outer)).isEqualTo("minecraft:stone")
        assertThat(player.faults()).isEmpty()
    }

    private val hurtBackSkill = """
        apiVersion: astera/v3
        kind: Skill
        id: ports-attacker
        machine:
          start: wait
          states:
            wait:
              for: 20
              then: end
              on:
                - event: hurt
                  to: react
                  do:
                    - {id: contact, op: frame, anchor: caster, for: 4}
                    - {id: back, op: hurt, target: event.attacker, from: contact, at: 1}
            react:
              for: 4
              then: end
            end: {final: true}
    """.trimIndent()

    @Test
    fun eventAttackerResolvesToTheBoundAttacker() {
        val rec = GatewayRecorder()
        val run = start(compile(hurtBackSkill), rec, WorldId("world"))
        val attacker = UUID(0L, 77L)
        run.tick(0)
        run.player.deliver(run.cast, RuntimeEvent(EventPath("hurt"), 1, null, null, null, attacker))
        for (now in 1..6) run.tick(now)
        assertThat(rec.calls.filter { it.verb == "effect" && it.payload == "hurt" }.map { it.instance })
            .containsExactly(attacker.toString())
        assertThat(run.player.faults()).isEmpty()
    }

    private val alliesSkill = """
        apiVersion: astera/v3
        kind: Skill
        id: ports-allies
        machine:
          start: s
          states:
            s:
              for: 20
              do:
                - {id: st, op: status, target: {kind: KIND, around: caster, radius: 6.0, max_count: all INC}, status: shock, stacks: 1, for: 30}
    """.trimIndent()

    private fun candidate(id: UUID, z: Double) = HitCandidate(
        entityId = id,
        feet = Vec3(0.0, 0.0, z),
        yawDeg = 0.0,
        box = HitBox(Vec3(-0.3, 0.0, z - 0.3), Vec3(0.3, 1.8, z + 0.3)),
        velocity = Vec3.ZERO,
    )

    @Test
    fun alliesTargetResolvesThroughTheTeamPort() {
        val friend = UUID(0L, 11L)
        val foe = UUID(0L, 12L)
        for ((kind, expected) in listOf("allies" to friend, "enemies" to foe)) {
            val rec = GatewayRecorder()
            rec.victims = listOf(candidate(friend, 2.0), candidate(foe, 3.0))
            rec.allies += friend
            val run = start(compile(alliesSkill.replace("KIND", kind).replace("INC", if (kind == "allies") ", include_self: false" else "")), rec, WorldId("world"))
            for (now in 0..2) run.tick(now)
            assertThat(rec.calls.filter { it.verb == "status" }.map { it.instance }.toSet()).describedAs(kind).containsExactly(expected.toString())
            assertThat(run.player.faults()).isEmpty()
        }
    }
}
