package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.load.DefaultOpRegistries
import com.astarworks.astera.core.application.skillengine.load.DefaultSkillCompiler
import com.astarworks.astera.core.application.skillengine.load.ItemFramesDecoder
import com.astarworks.astera.core.application.skillengine.load.YamlSkillDecoder
import com.astarworks.astera.core.application.skillengine.load.ops.damageDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.movementDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.ControlOpDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.FrameOpDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.HitOpDecoders
import com.astarworks.astera.core.application.skillengine.load.presentation.presentationDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.spawnDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.statusDecoders
import com.astarworks.astera.core.application.skillengine.load.worldDecoders
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * SKL-08 W8: the shipped coin-2 content (`content/skills/couture-rapier-s2/`) goes through the real loaders:
 * compose (skill.yaml + look.yaml + patches/crimson.yaml) -> decode -> compile, base and `@crimson` variant.
 *
 * Uses the production decoder registry (every op family) and the shipped `content/frames` point tables.
 */
class Coin2ContentScenario {

    private val skillsRoot: Path = Path.of(requireNotNull(System.getProperty("astera.sim.contentDir")), "skills")

    private fun composed(): Map<String, ComposedSkill> {
        val composer = SkillComposer(skillsRoot)
        return when (val r = composer.composeAll()) {
            is Result.Success -> r.value
            is Result.Failure -> error("compose failed: ${r.error}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun map(v: Any?): Map<String, Any?> = v as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun list(v: Any?): List<Map<String, Any?>> = v as List<Map<String, Any?>>

    private fun allOps(tree: Map<String, Any?>): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        for (section in listOf("gameplay", "presentation")) {
            val s = map(tree[section])
            list(s["timeline"] ?: emptyList<Any>()).forEach { out += list(it["do"]) }
            map(s["on"] ?: emptyMap<String, Any?>()).values.forEach { out += list(it) }
        }
        return out
    }

    @Test
    fun `base and crimson variant compose from the split files`() {
        val all = composed()
        val base = checkNotNull(all["couture-rapier-s2"]) { "couture-rapier-s2 missing" }
        val crimson = checkNotNull(all["couture-rapier-s2@crimson"]) { "couture-rapier-s2@crimson missing" }
        assertThat(base.sources.map { it.substringAfterLast('/') }).containsExactlyInAnyOrder("skill.yaml", "look.yaml")
        assertThat(crimson.sources.map { it.substringAfterLast('/') }).contains("crimson.yaml")

        // the skin only changes presentation: gameplay is identical, the colour and one trail differ
        assertThat(crimson.tree["gameplay"]).isEqualTo(base.tree["gameplay"])
        assertThat(map(map(base.tree["presentation"])["params"])["thread_color"]).isEqualTo("#ff5fa2")
        assertThat(map(map(crimson.tree["presentation"])["params"])["thread_color"]).isEqualTo("#d0203a")
        val baseIds = allOps(base.tree).map { it["id"] }
        val crimsonIds = allOps(crimson.tree).map { it["id"] }
        assertThat(baseIds).contains("trail_a", "trail_b")
        assertThat(crimsonIds).contains("trail_a").doesNotContain("trail_b")
    }

    @Test
    fun `content has no absolute coordinates and spins about a horizontal axis`() {
        val tree = checkNotNull(composed()["couture-rapier-s2"]).tree

        // no vec3 literal anywhere except the palm point's zero offset
        fun walk(path: String, v: Any?) {
            when (v) {
                is Map<*, *> -> v.forEach { (k, x) -> walk("$path.$k", x) }
                is List<*> -> {
                    val nums = v.size == 3 && v.all { it is Number }
                    if (nums) assertThat(path).describedAs("vec literal at $path").endsWith("offset")
                    if (nums && path.endsWith("offset")) assertThat(v.map { (it as Number).toDouble() }).containsOnly(0.0)
                    v.forEachIndexed { i, x -> walk("$path[$i]", x) }
                }
                else -> Unit
            }
        }
        walk("", tree)

        val spins = allOps(tree).filter { it["op"] == "spin" }
        assertThat(spins.map { it["id"] }).containsExactlyInAnyOrder("spin_shears", "spin_volley")
        spins.forEach { assertThat(it["axis"].toString()).describedAs("spin axis of ${it["id"]}").isIn("caster.left", "volley.left") }

        // the volley starts at the shears before the pieces are re-parented, and flies tip-first
        val release = list(map(map(tree["gameplay"])["on"])["release"]).map { it["id"] }
        assertThat(release.indexOf("place_volley")).isLessThan(release.indexOf("take_off"))
        assertThat(release.indexOf("take_off")).isLessThan(release.indexOf("fly"))
        val fly = list(map(map(tree["gameplay"])["on"])["release"]).first { it["id"] == "fly" }
        assertThat(map(fly["align"])["axis"]).isEqualTo("velocity")
    }

    @Test
    fun `gameplay numbers match the brief and the hit reactions are contact-only`() {
        val tree = checkNotNull(composed()["couture-rapier-s2"]).tree
        val params = map(map(tree["gameplay"])["params"])
        assertThat((params["dmg_throw"] as Number).toInt()).isEqualTo(18)
        assertThat((params["bleed_stacks"] as Number).toInt()).isEqualTo(2)
        val hit = list(map(map(tree["gameplay"])["on"])["hit"])
        val dmg = hit.first { it["id"] == "dmg" }
        assertThat(dmg["when"]).isEqualTo("= event.source == 'throw_hit'")
        val look = list(map(map(tree["presentation"])["on"])["hit"]).map { it["op"] }
        assertThat(look).contains("emit", "sound", "victim_anim") // sparks along contact.normal, stab sound, stagger
        assertThat(map(map(tree["gameplay"])["on"]).keys).contains("reel", "arrive")
    }

    @Test
    fun `base and crimson decode and compile`() {
        val decoder = YamlSkillDecoder(
            DefaultOpRegistries.decoders(
                movementDecoders(), statusDecoders(), worldDecoders(), spawnDecoders(), damageDecoders(), presentationDecoders(),
                listOf(FrameOpDecoders(), HitOpDecoders(), ControlOpDecoders()),
            ),
        )
        val (tables, tableErrors) = ItemFramesDecoder.loadDirectory(skillsRoot.resolveSibling("frames"))
        check(tableErrors.isEmpty()) { "frame tables: $tableErrors" }
        val compiler = DefaultSkillCompiler(CelExprCompiler()) { tables[it] }
        val all = composed()
        for (id in listOf("couture-rapier-s2", "couture-rapier-s2@crimson")) {
            val def = when (val d = decoder.decode(checkNotNull(all[id]))) {
                is Result.Success -> d.value
                is Result.Failure -> error("decode $id: ${d.error.path} ${d.error.message}")
            }
            assertThat(def.frames.map { it.id }).containsExactly("shears", "body", "blade_a", "blade_b", "volley", "palm")
            assertThat(def.gameplay.on.getValue("release").map { it.id }).containsSubsequence("place_volley", "take_off", "fly", "throw_hit")
            val compiled = when (val c = compiler.compile(def)) {
                is Result.Success -> c.value
                is Result.Failure -> error("compile $id: ${c.error.path} ${c.error.message}")
            }
            assertThat(compiled.exprs).isNotEmpty()
        }
    }
}
