package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.application.skillengine.load.ops.statusDecoders
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.ops.statusExecutors
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVictimAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.StatusOp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

/** SKL-08 W13: status / buff ops decode from YAML maps and drive the gameplay gateway (real decoder + executors, faked ports). */
class StatusOpsScenarioTest {

    private val caster = UUID(0, 1)
    private val victim = UUID(0, 2)
    private val bystander = UUID(0, 3)

    private class Recorder : ISkillGameplayGateway {
    override fun statusStacks(entity: UUID, statusId: String): Int = 0
        val calls = mutableListOf<String>()
        override fun damage(skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3, ignoreInvulnerability: Boolean, ignoreArmor: Boolean) { calls += "damage" }
        override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) {
            calls += "status $victim $statusId x$stacks ${durationTicks}t"
        }
        override fun removeStatus(victim: UUID, statusId: String) { calls += "remove $victim $statusId" }
        override fun cleanse(victim: UUID, negativeOnly: Boolean) { calls += "cleanse $victim neg=$negativeOnly" }
        override fun ignite(caster: UUID, victim: UUID, durationTicks: Int) { calls += "ignite $victim ${durationTicks}t" }
        override fun silence(caster: UUID, victim: UUID, durationTicks: Int) { calls += "silence $victim ${durationTicks}t" }
        override fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?) {
            calls += "attr $victim $attribute $operation $amount ${durationTicks ?: "forever"}"
        }
        override fun restoreResource(victim: UUID, resource: String, amount: Double) { calls += "restore $victim $resource $amount" }
        override fun impulse(victim: UUID, vector: Vec3) { calls += "impulse" }
        override fun freezeEntities(entities: List<UUID>, ticks: Int) { calls += "freeze" }
    }

    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
            error("${m.name} is not part of the status-op scenario")
        } as T

    private val resolver = object : ValueResolver {
        override fun num(value: Value<Double>): Double = when (value) {
            is Value.Literal -> value.value
            is Value.ParamRef -> if (value.name == "bleed_ticks") 80.0 else error("param ${value.name}")
            is Value.Expr -> error("expr ${value.source}")
        }
        override fun vec3(value: Value<Vec3>): Vec3 = error("vec3")
        override fun quat(value: Value<Quaternion>): Quaternion = error("quat")
        override fun bool(value: Value<Boolean>): Boolean = error("bool")
        override fun text(value: Value<String>): String = error("text")
    }

    private fun context(gameplay: Recorder, cast: Long = 7): OpContext {
        val ports = SkillRuntimePorts(
            visuals = unused<ISkillVisualGateway>(),
            particles = unused<ISkillParticleGateway>(),
            sounds = unused<ISkillSoundGateway>(),
            gameplay = gameplay,
            entities = unused<ISkillEntityQuery>(),
            victimAnim = unused<ISkillVictimAnimGateway>(),
        )
        return object : OpContext {
        override val host: com.astarworks.astera.core.application.skillengine.runtime.OpHost get() = error("this scenario never reaches the cast host")
            override val cast = CastId(cast)
            override val world = com.astarworks.astera.core.domain.world.WorldId("world")
            override val tree: FrameTree = unused<FrameTree>()
            override val values = resolver
            override val ports = ports
            override fun raise(event: String) = error("raise")
            override fun run(ops: List<SkillOp>) = error("run")
        }
    }

    private fun reader(vararg entries: Pair<String, Any?>): OpReader {
        val map = entries.toMap()
        fun value(key: String): Value<Double>? = when (val v = map[key]) {
            null -> null
            is Number -> Value.Literal(v.toDouble())
            is String -> Value.ParamRef(v.substring(2, v.length - 1))
            else -> error("bad $key")
        }
        return object : OpReader {
        override fun textValue(key: String): Value<String>? = null
        override fun entries(key: String): List<OpReader> = emptyList()
            override val path = "gameplay.on.hit[test]"
            override fun string(key: String) = map[key] as? String
            override fun requireString(key: String) = string(key) ?: fail("$path.$key: missing")
            override fun int(key: String) = (map[key] as? Number)?.toInt()
            override fun bool(key: String) = map[key] as? Boolean
            override fun number(key: String) = value(key)
            override fun requireNumber(key: String) = value(key) ?: fail("$path.$key: missing")
            override fun vec3(key: String): Value<Vec3>? = null
            override fun boolValue(key: String): Value<Boolean>? = null
            override fun point(key: String): PointRef? = null
            override fun stringList(key: String): List<String>? = null
            override fun ops(key: String): List<SkillOp> = emptyList()
            override fun raw(key: String) = map[key]
            override fun fail(message: String): Nothing = throw IllegalArgumentException(message)
        }
    }

    private fun decode(name: String, vararg entries: Pair<String, Any?>): StatusOp {
        val decoder = statusDecoders().single { name in it.opNames }
        assertThat(decoder.section).isEqualTo(OpSection.GAMEPLAY)
        return decoder.decode(name, "op_$name", null, reader(*entries)) as StatusOp
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(ops: List<StatusOp>, gameplay: Recorder, targets: List<UUID> = listOf(victim), cast: Long = 7) {
        val executors = statusExecutors({ _, t -> if (t is TargetRef.Caster) listOf(caster) else targets }) { caster }
        runWith(executors, ops, gameplay, cast)
    }

    @Suppress("UNCHECKED_CAST")
    private fun runWith(executors: List<OpExecutor<*>>, ops: List<StatusOp>, gameplay: Recorder, cast: Long) {
        val ctx = context(gameplay, cast)
        for (op in ops) {
            val ex = executors.single { it.opClass.isInstance(op) } as OpExecutor<SkillOp>
            ex.execute(op, ctx)
        }
    }

    @Test
    fun `all seven status ops decode and drive the gateway with evaluated numbers`() {
        scenario("status-ops-all") {
            val ops = listOf(
                decode("status", "target" to "victim", "status" to "bleed", "stacks" to 2, "duration_ticks" to "\${bleed_ticks}"),
                decode("remove_status", "target" to "victim", "status" to "freeze"),
                decode("cleanse", "target" to "caster"),
                decode("ignite", "target" to "victim", "duration_ticks" to 40),
                decode("silence", "target" to "victim", "duration_ticks" to 60.4),
                decode("modify_attribute", "target" to "caster", "attribute" to "speed", "operation" to "multiply_base", "amount" to 0.2, "duration_ticks" to 100),
                decode("modify_attribute", "target" to "caster", "attribute" to "armor", "amount" to 3),
                decode("restore_resource", "target" to "caster", "resource" to "mana", "amount" to 15),
            )
            val gameplay = Recorder()
            run(ops, gameplay)
            assertThat(gameplay.calls).containsExactly(
                "status $victim bleed x2 80t",
                "remove $victim freeze",
                "cleanse $caster neg=true",
                "ignite $victim 40t",
                "silence $victim 60t",
                "attr $caster speed multiply_base 0.2 100",
                "attr $caster armor add 3.0 forever",
                "restore $caster mana 15.0",
            )
        }
    }

    @Test
    fun `an area target applies to every resolved entity and chance is deterministic per cast`() {
        scenario("status-ops-chance") {
            val op = decode("status", "target" to "victim", "status" to "stun", "duration_ticks" to 20, "chance" to 0.5)
            fun landed(cast: Long): Int {
                val gameplay = Recorder()
                val executors = statusExecutors({ _, _ -> listOf(victim, bystander) }) { caster }
                repeat(200) { runWith(executors, listOf(op), gameplay, cast) }
                return gameplay.calls.size
            }
            val a = landed(11)
            val b = landed(11)
            assertThat(a).isEqualTo(b)
            // 400 rolls at 50 percent: both extremes (always / never) are broken.
            assertThat(a).isBetween(100, 300)
            val sure = Recorder()
            run(listOf(decode("status", "target" to "victim", "status" to "stun", "duration_ticks" to 20, "chance" to 1.0)), sure, listOf(victim, bystander))
            assertThat(sure.calls).hasSize(2)
            val never = Recorder()
            run(listOf(decode("status", "target" to "victim", "status" to "stun", "duration_ticks" to 20, "chance" to 0.0)), never)
            assertThat(never.calls).isEmpty()
        }
    }

    @Test
    fun `authoring errors fail the decode with the path`() {
        scenario("status-ops-errors") {
            assertThatThrownBy { decode("ignite", "duration_ticks" to 10) }.hasMessageContaining("target: required")
            assertThatThrownBy { decode("modify_attribute", "target" to "victim", "attribute" to "speed", "operation" to "set", "amount" to 1) }
                .hasMessageContaining("operation")
            assertThatThrownBy { decode("cleanse", "target" to "nobody") }.hasMessageContaining("target")
            val area = decode("silence", "target" to mapOf("enemies" to mapOf("around" to "contact.point", "radius" to 4)), "duration_ticks" to 10)
            assertThat((area as StatusOp.Silence).target).isEqualTo(TargetRef.Enemies(PointRef("contact", "point"), Value.Literal(4.0)))
        }
    }
}
