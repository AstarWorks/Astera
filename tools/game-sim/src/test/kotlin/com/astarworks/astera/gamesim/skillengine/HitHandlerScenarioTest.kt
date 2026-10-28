package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.CastTrace
import com.astarworks.astera.core.application.skillengine.runtime.player.EventBinding
import com.astarworks.astera.core.application.skillengine.runtime.player.LiveValueSource
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Bounds
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.ExpansionTable
import com.astarworks.astera.core.domain.skillengine.table.HitDamage
import com.astarworks.astera.core.domain.skillengine.table.HitShape
import com.astarworks.astera.core.domain.skillengine.table.HitTargets
import com.astarworks.astera.core.domain.skillengine.table.HitWindow
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.joml.Vector3d
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * W2-03 (R7/R8/R12/R15/R21/R24): the `hit` op's per-victim events, miss, detection-only damage, nearest-first `max_count`, sector
 * `forward`, per-victim damage amounts and `ignore_invulnerability`. Hand-built tables on the real [TablePlayer] and [GatewayRecorder].
 */
class HitHandlerScenarioTest {
    private fun id(op: String) = InstanceId(listOf(InstanceId.Segment(OpId(op), null)))
    private val hit1 = id("hit1")
    private fun row(tick: Int, action: Action, inst: InstanceId = id("none")) = Row(tick, inst, action, SrcPath("t"))
    private fun live(name: String): Val<Double> = Val.Live<Double>(name, null)
    private fun sound(volume: Val<Double>) = Action.Sound("s", volume, Val.Known(1.0), Val.Known(Vector3d()), "master")
    private fun sub(key: String, vararg rows: Row) = SubTable(EventPath(key), StatePath("a"), TickRange(0, 100), Int.MAX_VALUE, rows.toList(), emptyList())

    private fun victim(feet: Vec3) = HitCandidate(
        UUID.randomUUID(), feet, 0.0, HitBox(Vec3(feet.x - 0.3, feet.y, feet.z - 0.3), Vec3(feet.x + 0.3, feet.y + 1.8, feet.z + 0.3)),
    )

    private fun sphere(radius: Double = 10.0) = HitShape.Sphere(Val.Known(Vector3d()), Val.Known(radius))

    private fun hitRow(
        tick: Int,
        shape: HitShape = sphere(),
        targets: HitTargets = HitTargets("enemies", null),
        window: HitWindow = HitWindow.Once,
        ignoreInvulnerability: Boolean = false,
        final: Boolean = true,
        damage: List<HitDamage> = emptyList(),
    ) = row(tick, Action.Hit(shape, null, "", targets, window, ignoreInvulnerability, final, damage), hit1)

    private fun skill(rows: List<Row>, subs: List<SubTable>): CompiledSkill {
        val state = StateTable(
            StatePath("a"), Duration.Fixed(100), null, false,
            listOf(row(0, Action.Enter(StatePath("a")))) + rows, emptyList(),
        )
        val table = ExpansionTable(
            skillId = "t", sampleSeed = 1L, params = emptyMap(),
            bounds = Bounds(emptyMap(), 0, 0, 0, 0, TickRange(0, 100), 4),
            states = listOf(state), subTables = subs.associateBy { it.event }, writes = emptyMap(), conditions = emptyMap(),
            config = EngineConfig(),
        )
        return CompiledSkill(SkillDef("t", null, null, machine = Machine("a", emptyMap())), table)
    }

    private class Trace : CastTrace {
        override fun enter(cast: CastHandle, state: StatePath, tick: Int) = Unit
        override fun exit(cast: CastHandle, state: StatePath, reason: ExitReason, tick: Int) = Unit
        override fun event(cast: CastHandle, path: EventPath, tick: Int, index: Int?) = Unit
        override fun call(cast: CastHandle, callId: InstanceId, tick: Int) = Unit
    }

    /** Live values for the cases: `event.i`, `vz` (the event victim's feet z), `falloff` (10 - vz), `stacks` / `consume` (a stack counter). */
    private class Live(val rec: GatewayRecorder) : LiveValueSource by rec.live {
        var stacks = 3.0

        private fun feetZ(b: EventBinding?): Double =
            rec.victims.first { it.entityId == b?.victim?.id }.feet.z

        override fun num(cast: CastHandle, name: String, binding: EventBinding?): Double = when (name) {
            "event.i" -> binding?.index?.toDouble() ?: -1.0
            "vz" -> feetZ(binding)
            "falloff" -> 10.0 - feetZ(binding)
            "stacks" -> stacks
            "consume" -> { stacks = 0.0; 0.0 }
            else -> rec.live.num(cast, name, binding)
        }
    }

    private class Fx(skill: CompiledSkill, val rec: GatewayRecorder, val live: LiveValueSource, gameplay: ISkillGameplayGateway = rec.gameplay) {
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, gameplay, rec.query, live, Trace(), rec.movement, rec.items, rec.world)
        val cast: CastHandle
        var now = 0

        init {
            val request = CastRequest(skill, rec.caster, WorldId("world"), Vec3(0.0, 0.0, 0.0), 0.0, 0.0, 1L, startTick = 0)
            cast = (player.start(request, skill) as Result.Success).value
        }

        fun run(untilTick: Int) {
            while (now <= untilTick) {
                rec.tick = now
                player.tick(now)
                now++
            }
        }

        fun sounds() = rec.calls.filter { it.verb == "sound" }
        fun volumes() = sounds().map { Regex("vol=(\\S+)").find(it.payload)!!.groupValues[1].toDouble() }
    }

    private fun fx(rows: List<Row>, subs: List<SubTable>, victims: List<HitCandidate>, gameplay: ISkillGameplayGateway? = null): Fx {
        val rec = GatewayRecorder()
        rec.victims = victims
        return Fx(skill(rows, subs), rec, Live(rec), gameplay ?: rec.gameplay)
    }

    @Test
    fun onHitPerVictimSubTable() {
        val f = fx(
            listOf(hitRow(0)),
            listOf(sub("hit1.hit", row(0, sound(live("event.i"))), row(0, sound(live("vz"))))),
            listOf(victim(Vec3(0.0, 0.0, 4.0)), victim(Vec3(0.0, 0.0, 2.0)), victim(Vec3(0.0, 0.0, 3.0))),
        )
        f.run(2)
        // one handler paste per victim, nearest first: event.i 0,1,2 and the feet z 2,3,4
        assertThat(f.volumes()).containsExactly(0.0, 2.0, 1.0, 3.0, 2.0, 4.0)
        assertThat(f.player.faults()).isEmpty()
    }

    @Test
    fun missFiresOnceWhenNoVictim() {
        val f = fx(
            listOf(hitRow(0, final = false), hitRow(1, final = false), hitRow(2, final = true)),
            listOf(sub("hit1.miss", row(0, sound(Val.Known(7.0))))),
            emptyList(),
        )
        f.run(5)
        assertThat(f.sounds()).hasSize(1)
        assertThat(f.sounds().single().tick).isEqualTo(2)
        assertThat(f.player.faults()).isEmpty()
    }

    @Test
    fun missNotFiredAfterHit() {
        // window once: tick 0 hits the victim, ticks 1 and 2 find nobody new, yet the final detection raises no miss
        val f = fx(
            listOf(hitRow(0, final = false), hitRow(1, final = false), hitRow(2, final = true)),
            listOf(sub("hit1.hit", row(0, sound(Val.Known(1.0)))), sub("hit1.miss", row(0, sound(Val.Known(9.0))))),
            listOf(victim(Vec3(0.0, 0.0, 3.0))),
        )
        f.run(5)
        assertThat(f.volumes()).containsExactly(1.0)
        assertThat(f.player.faults()).isEmpty()
    }

    @Test
    fun damageOmittedIsDetectionOnly() {
        val f = fx(
            listOf(hitRow(0)),
            listOf(sub("hit1.hit", row(0, sound(Val.Known(1.0))))),
            listOf(victim(Vec3(0.0, 0.0, 3.0))),
        )
        f.run(2)
        assertThat(f.rec.calls.filter { it.verb == "damage" }).isEmpty()
        assertThat(f.sounds()).hasSize(1)
    }

    @Test
    fun handlerOrderKnockbackThenDamage() {
        // hit damage omitted: the handler's own rows run in declaration order (knockback before damage, v2 on_hit order)
        val f = fx(
            listOf(hitRow(0)),
            listOf(sub("hit1.hit", row(0, sound(Val.Known(1.0))), row(0, sound(Val.Known(2.0))))),
            listOf(victim(Vec3(0.0, 0.0, 3.0))),
        )
        f.run(2)
        assertThat(f.volumes()).containsExactly(1.0, 2.0)
    }

    @Test
    fun maxCountNearestFirst() {
        val f = fx(
            listOf(hitRow(0, targets = HitTargets("enemies", 2))),
            listOf(sub("hit1.hit", row(0, sound(live("vz"))))),
            listOf(victim(Vec3(0.0, 0.0, 5.0)), victim(Vec3(0.0, 0.0, 1.0)), victim(Vec3(0.0, 0.0, 3.0))),
        )
        f.run(2)
        assertThat(f.volumes()).containsExactly(1.0, 3.0)
    }

    @Test
    fun sectorYawVsLook() {
        fun sector(forward: Vector3d, flat: Boolean) = HitShape.Sector(
            Val.Known(Vector3d()), Val.Known(forward), Val.Known(40.0), Val.Known(6.0), Val.Known(20.0), flat,
        )
        // forward: yaw flattens a pitched forward onto the horizon, so the victim straight ahead is inside the wedge
        val yaw = fx(
            listOf(hitRow(0, shape = sector(Vector3d(0.0, 0.5, 1.0), flat = true))),
            listOf(sub("hit1.hit", row(0, sound(Val.Known(1.0))))),
            listOf(victim(Vec3(0.0, 0.0, 3.0))),
        )
        yaw.run(2)
        assertThat(yaw.sounds()).hasSize(1)
        assertThat(yaw.player.faults()).isEmpty()
        // forward: yaw of a vertical anchor has no horizontal component: a loud fault, not a guess
        val vertical = fx(
            listOf(hitRow(0, shape = sector(Vector3d(0.0, 1.0, 0.0), flat = true))),
            listOf(sub("hit1.hit", row(0, sound(Val.Known(1.0))))),
            listOf(victim(Vec3(0.0, 0.0, 3.0))),
        )
        vertical.run(2)
        assertThat(vertical.sounds()).isEmpty()
        assertThat(vertical.player.faults()).isNotEmpty()
    }

    @Test
    fun falloffReadsEventVictim() {
        val f = fx(
            listOf(hitRow(0, damage = listOf(HitDamage(DamageAttribute.PHYSICAL, live("falloff"))))),
            emptyList(),
            listOf(victim(Vec3(0.0, 0.0, 4.0)), victim(Vec3(0.0, 0.0, 2.0))),
        )
        f.run(2)
        // nearest first, each amount read for its own victim
        assertThat(f.rec.calls.filter { it.verb == "damage" }.map { it.payload }).containsExactly("physical=8", "physical=6")
        assertThat(f.player.faults()).isEmpty()
    }

    @Test
    fun perStackReadBeforeConsumeAfter() {
        val rec = GatewayRecorder()
        rec.victims = listOf(victim(Vec3(0.0, 0.0, 2.0)), victim(Vec3(0.0, 0.0, 3.0)))
        val live = Live(rec)
        val f = Fx(
            skill(
                listOf(hitRow(0, damage = listOf(HitDamage(DamageAttribute.PHYSICAL, live("stacks"))))),
                // the consume runs from the handler, after the damage landed
                listOf(sub("hit1.hit", row(0, sound(live("consume"))))),
            ),
            rec, live,
        )
        f.run(2)
        assertThat(rec.calls.filter { it.verb == "damage" }.map { it.payload }).containsExactly("physical=3", "physical=3")
        assertThat(live.stacks).isEqualTo(0.0)
        assertThat(rec.calls.indexOfLast { it.verb == "damage" }).isLessThan(rec.calls.indexOfFirst { it.verb == "sound" })
    }

    @Test
    fun ignoreInvulnerabilityOnHit() {
        val rec = GatewayRecorder()
        val flags = ArrayList<Boolean>()
        val spy = object : ISkillGameplayGateway by rec.gameplay {
            override fun damage(skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3, ignoreInvulnerability: Boolean, ignoreArmor: Boolean) {
                flags += ignoreInvulnerability
                rec.gameplay.damage(skillId, caster, victim, components, hitPoint, ignoreInvulnerability, ignoreArmor)
            }
        }
        for (flag in listOf(true, false)) {
            val f = Fx(
                skill(listOf(hitRow(0, ignoreInvulnerability = flag, damage = listOf(HitDamage(DamageAttribute.PHYSICAL, Val.Known(5.0))))), emptyList()),
                rec.also { it.victims = listOf(victim(Vec3(0.0, 0.0, 3.0))) }, Live(rec), spy,
            )
            f.run(1)
        }
        assertThat(flags).containsExactly(true, false)
    }
}
