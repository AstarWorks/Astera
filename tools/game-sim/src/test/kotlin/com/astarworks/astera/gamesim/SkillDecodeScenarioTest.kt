package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.load.DefaultOpRegistries
import com.astarworks.astera.core.application.skillengine.load.DefaultSkillCompiler
import com.astarworks.astera.core.application.skillengine.load.InMemorySkillCatalog
import com.astarworks.astera.core.application.skillengine.load.ItemFrames
import com.astarworks.astera.core.application.skillengine.load.ItemFramesDecoder
import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.YamlSkillDecoder
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.hit.HitWindow
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import com.astarworks.astera.core.domain.skillengine.op.ControlOp
import com.astarworks.astera.core.domain.skillengine.op.FrameOp
import com.astarworks.astera.core.domain.skillengine.op.FxOp
import com.astarworks.astera.core.domain.skillengine.op.HitOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.VisualOp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * SKL-08 / ADR-0301 W3a: compose a coin-2 fixture (frozen grammar, test resources) with the real composer, decode it with
 * the real decoder, compile every expression with the real CEL compiler, and check the failure modes. The op families
 * belong to other workers, so a tiny in-scenario decoder covers just the ops the fixture uses.
 */
class SkillDecodeScenarioTest {

    private object GameplayFake : OpDecoder {
        override val section = OpSection.GAMEPLAY
        override val opNames = setOf("event", "spin", "place", "reparent", "hit", "schedule")

        override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = when (op) {
            "event" -> ControlOp.RaiseEvent(id, whenExpr, reader.requireString("name"))
            "spin" -> FrameOp.Motion(
                id, whenExpr, reader.requireString("frame"),
                MotionSpec.Spin(AxisRef(reader.requireString("axis")), reader.requireNumber("deg_per_tick"), reader.string("until")),
            )
            "place" -> FrameOp.Place(id, whenExpr, reader.requireString("frame"), reader.requireString("like"), reader.vec3("offset"))
            "reparent" -> FrameOp.Reparent(
                id, whenExpr, reader.stringList("frames").orEmpty(), reader.requireString("to"), reader.bool("keep_world") ?: true, null,
            )
            "hit" -> {
                val cap = reader.raw("capsule") as Map<*, *>
                HitOp.Hit(
                    id, whenExpr, reader.stringList("frames").orEmpty(),
                    HitVolume.Capsule(PointRef.parse(cap["from"] as String), PointRef.parse(cap["to"] as String), Value.Literal((cap["radius"] as Number).toDouble())),
                    if (reader.requireString("window") == "once") HitWindow.Once else reader.fail("only window: once in this fake"),
                )
            }
            "schedule" -> ControlOp.Schedule(id, whenExpr, reader.requireNumber("after"), reader.requireString("event"))
            else -> reader.fail("unreachable")
        }
    }

    private object PresentationFake : OpDecoder {
        override val section = OpSection.PRESENTATION
        override val opNames = setOf("sound", "remove")

        override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = when (op) {
            "sound" -> FxOp.Sound(
                id, whenExpr, reader.requireString("sound"), reader.point("at") ?: reader.fail("missing 'at'"),
                reader.number("volume"), reader.number("pitch"), reader.int("every"), reader.string("until"),
            )
            else -> VisualOp.Remove(id, whenExpr, reader.stringList("ids").orEmpty())
        }
    }

    private val resources: Path = Path.of(javaClass.getResource("/skill-decode")!!.toURI())
    private val registry = DefaultOpRegistries.decoders(listOf(GameplayFake), listOf(PresentationFake))
    private val decoder = YamlSkillDecoder(registry)
    private val tables: Map<String, ItemFrames> = ItemFramesDecoder.loadDirectory(resources.resolve("frames")).also {
        assertThat(it.second).isEmpty()
    }.first
    private val compiler = DefaultSkillCompiler(CelExprCompiler()) { tables[it] }

    private fun composed(): Map<String, ComposedSkill> {
        val r = SkillComposer(resources.resolve("skills")).composeAll()
        check(r is Result.Success) { "compose failed: ${(r as Result.Failure).error}" }
        return r.value
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutated(base: ComposedSkill, edit: (MutableMap<String, Any?>) -> Unit): ComposedSkill {
        fun copy(v: Any?): Any? = when (v) {
            is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> v.forEach { (k, x) -> m[k.toString()] = copy(x) } }
            is List<*> -> v.map { copy(it) }.toMutableList()
            else -> v
        }
        val tree = copy(base.tree) as MutableMap<String, Any?>
        edit(tree)
        return base.copy(tree = tree)
    }

    @Suppress("UNCHECKED_CAST")
    private fun entry(tree: Map<String, Any?>, section: String, listPath: List<String>, id: String): MutableMap<String, Any?> {
        var cur: Any? = tree[section]
        for (k in listPath) cur = (cur as Map<String, Any?>)[k]
        return (cur as List<Map<String, Any?>>).first { it["id"] == id } as MutableMap<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun opIn(tree: Map<String, Any?>, section: String, row: String, id: String): MutableMap<String, Any?> {
        val timeline = (tree[section] as Map<String, Any?>)["timeline"] as List<Map<String, Any?>>
        val ops = timeline.first { it["id"] == row }["do"] as List<MutableMap<String, Any?>>
        return ops.first { it["id"] == id }
    }

    private fun failure(c: ComposedSkill): SkillDecodeError {
        val d = decoder.decode(c)
        if (d is Result.Failure) return d.error
        val r = compiler.compile((d as Result.Success).value)
        return (r as? Result.Failure)?.error ?: error("expected a decode/compile failure for ${c.id}")
    }

    @Test
    fun `shipped-shape coin 2 decodes compiles and keeps contacts identical under the crimson skin`() {
        val all = composed()
        val catalog = InMemorySkillCatalog(decoder, compiler)
        val report = catalog.reload(all.values.toList())
        assertThat(report.failed).isEmpty()
        assertThat(catalog.ids()).containsExactlyInAnyOrder("couture-rapier-s2", "couture-rapier-s2@crimson")

        val base = catalog.get("couture-rapier-s2")!!
        assertThat(base.def.item).isEqualTo("couture-rapier")
        assertThat(base.def.frames.map { it.id }).containsExactly("shears", "blade_a", "blade_b", "body", "volley", "palm")
        assertThat(base.def.gameplay.expose.map { it.name }).containsExactly("range", "damage")
        assertThat(base.def.gameplay.on.keys).containsExactly("release", "hit")
        assertThat(base.exprs.keys).contains(
            "gameplay.on.hit[reel_later].whenExpr",
            "presentation.timeline[0].ops[whoosh].volume",
            "presentation.on.hit[stab_snd].pitch",
        )
        val release = base.def.gameplay.on.getValue("release").map { it.id }
        assertThat(release).containsExactly("place_volley", "take_off", "spin_volley", "throw_hit") // order is authored order

        val skin = catalog.get("couture-rapier-s2@crimson")!!
        assertThat(skin.def.gameplay).isEqualTo(base.def.gameplay) // same contacts: gameplay is identical
        assertThat(skin.def.presentation).isNotEqualTo(base.def.presentation)
        assertThat(skin.def.presentation.timeline[0].ops.map { it.id }).containsExactly("whoosh") // tick_snd deleted by id
    }

    @Test
    fun `the points table decodes named points and axes per model part`() {
        val table = tables.getValue("couture-rapier")
        assertThat(table.parts.keys).containsExactly("held", "body", "blade_a", "blade_b", "held_hand")
        assertThat(table.parts.getValue("blade_a").points.keys).containsExactly("root", "tip", "pivot")
        assertThat(table.parts.getValue("blade_a").axes.keys).containsExactly("long", "edge")
        assertThat(table.partFor(null)!!.points).containsKeys("grip", "pivot", "tip")
    }

    @Test
    fun `four broken variants report the path and reason`() {
        val base = composed().getValue("couture-rapier-s2")

        val unknownKey = failure(mutated(base) { opIn(it, "gameplay", "spin_up", "spin_shears")["bogus"] = 1 })
        assertThat(unknownKey).isInstanceOf(SkillDecodeError.UnknownKey::class.java)
        assertThat(unknownKey.path).isEqualTo("gameplay.timeline[spin_up].do[spin_shears]")

        val wrongSection = failure(mutated(base) { opIn(it, "presentation", "spin_fx", "whoosh")["op"] = "spin" })
        assertThat(wrongSection).isInstanceOf(SkillDecodeError.UnknownOp::class.java)
        assertThat(wrongSection.path).isEqualTo("presentation.timeline[spin_fx].do[whoosh]")

        val duplicate = failure(mutated(base) { opIn(it, "presentation", "spin_fx", "whoosh")["id"] = "spin_shears" })
        assertThat(duplicate).isInstanceOf(SkillDecodeError.DuplicateId::class.java)

        val badFrame = failure(mutated(base) { entry(it, "gameplay", listOf("on", "release"), "place_volley")["like"] = "nope" })
        assertThat(badFrame).isInstanceOf(SkillDecodeError.UndefinedReference::class.java)
        assertThat(badFrame.path).isEqualTo("gameplay.on.release[place_volley].like")
    }

    @Test
    fun `dangling param, point and expression and a gameplay-touching skin are rejected`() {
        val base = composed().getValue("couture-rapier-s2")

        val param = failure(mutated(base) { opIn(it, "gameplay", "spin_up", "spin_shears")["deg_per_tick"] = "\${nope}" })
        assertThat(param).isInstanceOf(SkillDecodeError.UndefinedReference::class.java)

        val point = failure(mutated(base) { opIn(it, "presentation", "spin_fx", "whoosh")["at"] = "blade_a.nope" })
        assertThat(point).isInstanceOf(SkillDecodeError.UndefinedReference::class.java)
        assertThat(point.path).isEqualTo("presentation.timeline[0].ops[whoosh].at")

        val expr = failure(mutated(base) { opIn(it, "presentation", "spin_fx", "tick_snd")["volume"] = "= 1 +" })
        assertThat(expr).isInstanceOf(SkillDecodeError.BadExpr::class.java)

        val skin = failure(
            mutated(base) {
                it["_patch"] = mapOf("kind" to "skin", "touched" to listOf("presentation.params.thread_color", "gameplay.params.dmg_throw"))
            },
        )
        assertThat(skin).isInstanceOf(SkillDecodeError.Invalid::class.java)
        assertThat(skin.path).isEqualTo("gameplay.params.dmg_throw")
    }

    @Test
    fun `a failed reload keeps the old definition`() {
        val base = composed().getValue("couture-rapier-s2")
        val catalog = InMemorySkillCatalog(decoder, compiler)
        assertThat(catalog.reload(listOf(base)).failed).isEmpty()
        val before = catalog.get("couture-rapier-s2")!!
        val broken = mutated(base) { opIn(it, "gameplay", "spin_up", "spin_shears")["bogus"] = 1 }
        val report = catalog.reload(listOf(broken))
        assertThat(report.loaded).isEmpty()
        assertThat(report.failed.keys).containsExactly("couture-rapier-s2")
        assertThat(catalog.get("couture-rapier-s2")).isSameAs(before)
    }
}
