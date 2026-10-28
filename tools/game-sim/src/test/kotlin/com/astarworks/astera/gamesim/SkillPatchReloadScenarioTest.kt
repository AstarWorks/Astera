package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.compose.ComposeOptions
import com.astarworks.astera.core.application.skillengine.compose.ComposeError
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.load.SkillCatalog
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents.SkillReloadError
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * SKL-14 (founder 10/27 19:07, 19:59, 18:54): the four patch uses on one shipped skill, content only, and per-skill reload.
 *
 * Skill under test: shipped `couture-rapier-s2`. The patches are NOT shipped: they are the fixtures in
 * `src/test/resources/skill-patch-examples/` (recolour, VFX-only swap, range tuning, debuff). Each variant is composed
 * by the real [SkillComposer], compiled by the real catalog and cast by the real runner; the assertions are on the
 * composed tree (which paths differ) and on what the cast did (damage, status, contacts, objects, particles, sounds).
 */
class SkillPatchReloadScenarioTest {

    private val baseId = "couture-rapier-s2"
    private val otherId = "couture-rapier-s1"
    private val balanceOn = ComposeOptions(allowBalancePatches = true)

    private fun wired(): Coin2Collaborators = Coin2CastWiring.collaborators()

    private fun examples(): Path =
        Path.of(requireNotNull(javaClass.classLoader.getResource("skill-patch-examples")) { "fixture dir missing" }.toURI())

    /** The shipped skills [baseId] and [otherId] copied to a scratch dir (so the test can edit them), plus optional fixtures. */
    private fun scratch(tmp: Path, c: Coin2Collaborators, vararg fixtures: String): Path {
        val dir = tmp.resolve("skills")
        Files.createDirectories(dir)
        for (id in listOf(baseId, otherId)) c.skillsDir.resolve(id).toFile().copyRecursively(dir.resolve(id).toFile(), overwrite = true)
        if (fixtures.isNotEmpty()) {
            val extra = dir.resolve("_examples")
            Files.createDirectories(extra)
            for (f in fixtures) Files.copy(examples().resolve(f), extra.resolve(f))
        }
        return dir
    }

    // -------------------------------------------------------------- tree diff

    private fun flatten(path: String, v: Any?, out: MutableMap<String, Any?>) {
        when (v) {
            is Map<*, *> -> v.forEach { (k, x) -> flatten(if (path.isEmpty()) "$k" else "$path.$k", x, out) }
            is List<*> -> if (v.all { it is Map<*, *> && it["id"] is String }) {
                v.forEach { e -> flatten("$path[${(e as Map<*, *>)["id"]}]", e, out) }
            } else {
                out[path] = v
            }
            else -> out[path] = v
        }
    }

    private fun leaves(tree: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        flatten("", tree.filterKeys { it != "id" && it != "_patch" }, out)
        return out
    }

    /** Paths whose value differs between the two composed trees (added, removed or changed). */
    private fun diffPaths(a: Map<String, Any?>, b: Map<String, Any?>): Set<String> {
        val x = leaves(a)
        val y = leaves(b)
        return (x.keys + y.keys).filter { x[it] != y[it] }.toSet()
    }

    private fun assertOnlyPatched(variant: String, diff: Set<String>, vararg allowedPrefixes: String) {
        assertThat(diff).describedAs("$variant differs from the base").isNotEmpty()
        assertThat(diff.filter { p -> allowedPrefixes.none { p.startsWith(it) } })
            .describedAs("$variant changed fields outside its patch ${allowedPrefixes.toList()}")
            .isEmpty()
        for (prefix in allowedPrefixes) {
            assertThat(diff.any { it.startsWith(prefix) }).describedAs("$variant did change $prefix").isTrue()
        }
    }

    // -------------------------------------------------------------- cast fingerprint

    private class Print(
        val damage: List<Pair<String, Double>>,
        val statuses: List<Triple<String, Int, Int>>,
        val contacts: List<Any>,
        val events: List<Pair<String, Int>>,
        val objects: List<String>,
        val particles: List<String>,
        val sounds: List<String>,
        val reach: Double,
    )

    private fun cast(c: Coin2Collaborators, cat: SkillCatalog, id: String, withDummy: Boolean): Print {
        val sim = Sim(c, cat, withDummy)
        val cast = sim.start(checkNotNull(cat.get(id)) { "$id is not in the catalog" }, 0)
        sim.run(0, listOf(cast))
        assertThat(sim.runner.diagnostics()).describedAs("$id: no op failed").isEmpty()
        val snaps = sim.snapshots(cast)
        val cap = sim.capture
        return Print(
            damage = sim.gameplay.damage.map { it.kind to it.amount },
            statuses = sim.gameplay.statuses.toList(),
            contacts = snaps.flatMap { it.events }.mapNotNull { it.contact },
            events = snaps.flatMap { it.events }.map { it.name to it.tick },
            objects = cap.objects.values.map { "${it.kind} ${it.fields}" }.sorted(),
            particles = cap.particles.entries.sortedBy { it.key }.flatMap { (t, l) -> l.map { "$t ${it.particle} n=${it.count} c=${it.colorHex} s=${it.dataSize}" } },
            sounds = cap.sounds.entries.sortedBy { it.key }.flatMap { (t, l) -> l.map { "$t $it" } },
            reach = snaps.maxOf { s ->
                val volley = s.origins["volley"]
                val palm = s.origins["palm"]
                if (volley == null || palm == null) 0.0 else volley.distance(palm)
            },
        )
    }

    private fun SkillCatalog.assertHas(vararg ids: String) =
        assertThat(this.ids()).describedAs("catalog ids").contains(*ids)

    // -------------------------------------------------------------- 1) four patch uses

    @Test
    fun `recolour, VFX swap, range tuning and debuff patches change only their own fields`(@TempDir tmp: Path) {
        val c = wired()
        val dir = scratch(tmp, c, "example-recolour.yaml", "example-vfx-swap.yaml", "example-range-tuning.yaml", "example-debuff.yaml")

        val composer = SkillComposer(dir, balanceOn)
        val composed = when (val r = composer.composeAll()) {
            is Result.Success -> r.value
            is Result.Failure -> error("compose failed: ${r.error}")
        }
        val base = checkNotNull(composed[baseId]).tree
        fun tree(v: String) = checkNotNull(composed["$baseId@$v"]) { "variant $v missing: ${composed.keys}" }.tree

        // composed trees: exactly the patched paths differ
        assertOnlyPatched("recolour", diffPaths(base, tree("example_recolour")), "presentation.params.thread_color")
        assertOnlyPatched(
            "vfx swap", diffPaths(base, tree("example_vfx_swap")),
            "presentation.params.spark_particle", "presentation.params.spark_count",
            "presentation.timeline[spin_fx].do[trail_a]", "presentation.timeline[spin_fx].do[trail_b]",
            "presentation.on.release[throw_snd].sound", "presentation.on.release[petals]",
        )
        assertOnlyPatched("range tuning", diffPaths(base, tree("example_range")), "gameplay.params.throw_range")
        assertOnlyPatched(
            "debuff", diffPaths(base, tree("example_debuff")),
            "gameplay.params.bleed_stacks", "gameplay.params.bleed_ticks", "gameplay.on.hit[poison_on_throw]",
        )

        // without the dev flag a balance patch is not applied: its variant does not exist (skin patches still do)
        val noBalance = (SkillComposer(dir).composeAll() as Result.Success).value
        assertThat(noBalance.keys).contains("$baseId@example_recolour", "$baseId@example_vfx_swap")
            .doesNotContain("$baseId@example_range", "$baseId@example_debuff")

        // casts: base vs each variant on the real runner
        val cat = c.newCatalog()
        val report = SkillEngineContents(cat, balanceOn).reload(dir)
        assertThat(report.failed).describedAs("fixtures load: ${report.failures}").isZero()
        cat.assertHas("$baseId@example_recolour", "$baseId@example_vfx_swap", "$baseId@example_range", "$baseId@example_debuff")

        val baseP = cast(c, cat, baseId, withDummy = true)
        assertThat(baseP.damage).describedAs("the base deals damage").isNotEmpty()
        assertThat(baseP.contacts).describedAs("the base hits the dummy").isNotEmpty()

        // recolour: only colours differ (objects and particles equal once the two colours are made the same)
        val recolour = cast(c, cat, "$baseId@example_recolour", true)
        fun uncolour(l: List<String>, vararg hex: String) = l.map { s -> hex.fold(s) { acc, h -> acc.replace(h, "#C", ignoreCase = true) } }
        assertThat(recolour.damage).isEqualTo(baseP.damage)
        assertThat(recolour.statuses).isEqualTo(baseP.statuses)
        assertThat(recolour.contacts).isEqualTo(baseP.contacts)
        assertThat(recolour.events).isEqualTo(baseP.events)
        assertThat(recolour.sounds).isEqualTo(baseP.sounds)
        assertThat(recolour.objects).describedAs("a recolour changes the line / trail colours").isNotEqualTo(baseP.objects)
        assertThat(uncolour(recolour.objects, "#3a7bff", "#ff5fa2")).isEqualTo(uncolour(baseP.objects, "#3a7bff", "#ff5fa2"))
        assertThat(uncolour(recolour.particles, "#3a7bff", "#ff5fa2")).isEqualTo(uncolour(baseP.particles, "#3a7bff", "#ff5fa2"))

        // VFX swap: gameplay identical; other particle + count, no trails, one sound swapped, nothing else
        val swap = cast(c, cat, "$baseId@example_vfx_swap", true)
        assertThat(swap.damage).isEqualTo(baseP.damage)
        assertThat(swap.statuses).isEqualTo(baseP.statuses)
        assertThat(swap.contacts).isEqualTo(baseP.contacts)
        assertThat(swap.events).isEqualTo(baseP.events)
        assertThat(swap.reach).isEqualTo(baseP.reach)
        assertThat(swap.objects.filter { it.startsWith("trail") }).isEmpty()
        assertThat(baseP.objects.count { it.startsWith("trail") }).isGreaterThanOrEqualTo(2)
        assertThat(swap.objects.filterNot { it.startsWith("trail") }).isEqualTo(baseP.objects.filterNot { it.startsWith("trail") })
        assertThat(swap.particles).isNotEqualTo(baseP.particles)
        assertThat(swap.particles.any { "cherry_leaves" in it }).isTrue()
        assertThat(baseP.particles.none { "cherry_leaves" in it }).isTrue()
        assertThat(swap.sounds.size).isEqualTo(baseP.sounds.size)
        assertThat(swap.sounds.count { "couture.slash_light" in it }).isGreaterThan(baseP.sounds.count { "couture.slash_light" in it })

        // range tuning: no visual or damage change at the dummy, a different flight (aim point + max_ticks follow throw_range) with no target in the way
        val rangeHit = cast(c, cat, "$baseId@example_range", true)
        assertThat(rangeHit.damage).isEqualTo(baseP.damage)
        assertThat(rangeHit.statuses).isEqualTo(baseP.statuses)
        assertThat(rangeHit.contacts).isEqualTo(baseP.contacts)
        val baseFree = cast(c, cat, baseId, withDummy = false)
        val rangeFree = cast(c, cat, "$baseId@example_range", withDummy = false)
        assertThat(rangeFree.reach).describedAs("the throw flight changes with throw_range").isNotEqualTo(baseFree.reach)
        assertThat(rangeFree.damage).isEqualTo(baseFree.damage)

        // debuff: damage and look unchanged; stronger bleed and one extra status
        val debuff = cast(c, cat, "$baseId@example_debuff", true)
        assertThat(debuff.damage).isEqualTo(baseP.damage)
        assertThat(debuff.contacts).isEqualTo(baseP.contacts)
        assertThat(debuff.objects).isEqualTo(baseP.objects)
        assertThat(debuff.particles).isEqualTo(baseP.particles)
        assertThat(debuff.sounds).isEqualTo(baseP.sounds)
        assertThat(baseP.statuses).contains(Triple("bleed", 2, 60))
        assertThat(debuff.statuses).contains(Triple("bleed", 5, 200), Triple("poison", 1, 100)).doesNotContain(Triple("bleed", 2, 60))
        assertThat(debuff.statuses.size).isEqualTo(baseP.statuses.size + 1)
    }

    @Test
    fun `unsupported patches raise instead of being ignored`(@TempDir tmp: Path) {
        val c = wired()
        fun compose(name: String, text: String): List<ComposeError> {
            val dir = scratch(tmp.resolve(name), c)
            Files.writeString(dir.resolve("_bad.yaml"), text)
            return when (val r = SkillComposer(dir, balanceOn).composeAll()) {
                is Result.Success -> emptyList()
                is Result.Failure -> r.error
            }
        }
        val head = "apiVersion: astera/v2\nkind: Patch\ntarget: $baseId\nvariant: bad\n"
        // deleting something the base does not have
        val missing = compose("a", head + "patchKind: skin\npresentation:\n  timeline:\n    - id: spin_fx\n      do:\n        - {id: no_such_op, \$patch: delete}\n")
        assertThat(missing).describedAs("delete of a missing op").isNotEmpty()
        // a skin patch may not touch gameplay
        val forbidden = compose("b", head + "patchKind: skin\ngameplay:\n  params: {throw_range: 3.0}\n")
        assertThat(forbidden.any { it is ComposeError.PatchError.ForbiddenPath }).describedAs("skin patch on gameplay: $forbidden").isTrue()
        // a patch whose target skill does not exist
        val target = compose("c", "apiVersion: astera/v2\nkind: Patch\ntarget: no-such-skill\npatchKind: balance\ngameplay:\n  params: {x: 1}\n")
        assertThat(target.any { it is ComposeError.PatchError.TargetNotFound }).describedAs("unknown target: $target").isTrue()
    }

    // -------------------------------------------------------------- 2) anchors

    @Test
    fun `YAML anchors work inside one document and an alias across files is rejected with a clear error`(@TempDir tmp: Path) {
        val c = wired()
        // inside one document: shipped S2 look.yaml uses &thread_col / *thread_col and composes
        val ok = SkillComposer(c.skillsDir).composeAll()
        assertThat(ok).describedAs("shipped content with in-document anchors composes").isInstanceOf(Result.Success::class.java)

        val dir = tmp.resolve("skills")
        Files.createDirectories(dir.resolve("anchor-demo"))
        Files.writeString(
            dir.resolve("anchor-demo/skill.yaml"),
            "apiVersion: astera/v2\nkind: Skill\nid: anchor-demo\nmeta: {shared: &shared {a: 1}}\n",
        )
        Files.writeString(
            dir.resolve("anchor-demo/other.yaml"),
            "apiVersion: astera/v2\nkind: Skill\nid: anchor-demo\ngameplay:\n  params: *shared\n",
        )
        val result = SkillComposer(dir).composeAll()
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val errors = (result as Result.Failure).error
        assertThat(errors.map { it.file }).contains("anchor-demo/other.yaml")
        assertThat(errors.joinToString { it.reason }).contains("undefined alias").contains("ONE document")
    }

    // -------------------------------------------------------------- 3) per-skill reload

    private fun flightSpeed(c: Coin2Collaborators, cat: SkillCatalog): Double {
        val sim = Sim(c, cat, withDummy = false)
        val cast = sim.start(checkNotNull(cat.get(baseId)), 0)
        sim.run(0, listOf(cast))
        val snaps = sim.snapshots(cast)
        val release = snaps.flatMap { it.events }.first { it.name == "release" }.tick
        val at = snaps.associateBy { it.tick }
        return at.getValue(release + 3).origins.getValue("volley").distance(at.getValue(release + 2).origins.getValue("volley"))
    }

    private fun fileWith(dir: Path, needle: String): Path = Files.walk(dir).use { s ->
        s.filter { Files.isRegularFile(it) && Files.readString(it).contains(needle) }.findFirst().orElseThrow()
    }

    @Test
    fun `reloading one skill recomposes only it and its dependents, rejects broken files and keeps the old version`(@TempDir tmp: Path) {
        val c = wired()
        val dir = scratch(tmp, c)
        val cat = c.newCatalog()
        val contents = SkillEngineContents(cat)
        assertThat(contents.reload(dir).failed).isZero()
        val crimson = "$baseId@crimson"

        val s1 = checkNotNull(cat.get(otherId))
        val s2 = checkNotNull(cat.get(baseId))
        val s2c = checkNotNull(cat.get(crimson))
        assertThat(flightSpeed(c, cat)).isEqualTo(1.7, org.assertj.core.data.Offset.offset(0.05))

        // a cast already running keeps the definition it started with
        val sim = Sim(c, cat, withDummy = false)
        val running = sim.start(s2, 0)
        for (t in 0 until 14) sim.tickOnce(t)

        // 1) edit the base gameplay file: the base and its variant (a dependent) recompose, nothing else
        val gameplayFile = fileWith(dir.resolve(baseId), "throw_speed: 1.7")
        Files.writeString(gameplayFile, Files.readString(gameplayFile).replace("throw_speed: 1.7", "throw_speed: 1.2"))
        val ok = contents.reloadSkill(dir, baseId)
        assertThat(ok).isInstanceOf(Result.Success::class.java)
        val done = (ok as Result.Success).value
        assertThat(done.recomposed).isEqualTo(setOf(baseId, crimson))
        assertThat(done.reloaded).isEqualTo(setOf(baseId, crimson))
        assertThat(cat.get(otherId)).describedAs("another skill keeps its compiled instance").isSameAs(s1)
        assertThat(cat.get(baseId)).isNotSameAs(s2)
        assertThat(cat.get(crimson)).isNotSameAs(s2c)
        assertThat(flightSpeed(c, cat)).describedAs("next cast uses the new value").isEqualTo(1.2, org.assertj.core.data.Offset.offset(0.05))
        for (t in 14 until 120) sim.tickOnce(t)
        val release = sim.snapshots(running).flatMap { it.events }.first { it.name == "release" }.tick
        val at = sim.snapshots(running).associateBy { it.tick }
        assertThat(at.getValue(release + 3).origins.getValue("volley").distance(at.getValue(release + 2).origins.getValue("volley")))
            .describedAs("the cast in progress finished on the old definition").isEqualTo(1.7, org.assertj.core.data.Offset.offset(0.05))

        // 2) edit only the skin patch: only the variant recomposes, the base instance is untouched
        val before = checkNotNull(cat.get(baseId))
        val patchFile = dir.resolve("$baseId/patches/crimson.yaml")
        Files.writeString(patchFile, Files.readString(patchFile).replace("spark_particle: flame", "spark_particle: end_rod"))
        val skin = (contents.reloadSkill(dir, crimson) as Result.Success).value
        assertThat(skin.recomposed).isEqualTo(setOf(crimson))
        assertThat(cat.get(baseId)).isSameAs(before)

        // 3) a file that does not parse: rejected with an error, the old version stays
        val good = Files.readString(gameplayFile)
        val kept = checkNotNull(cat.get(baseId))
        Files.writeString(gameplayFile, good + "\n  broken: [unclosed\n")
        val broken = contents.reloadSkill(dir, baseId)
        assertThat(broken).isInstanceOf(Result.Failure::class.java)
        val rejected = (broken as Result.Failure).error as SkillReloadError.Rejected
        assertThat(rejected.messages.joinToString()).isNotBlank()
        assertThat(cat.get(baseId)).describedAs("old version stays").isSameAs(kept)
        assertThat(flightSpeed(c, cat)).isEqualTo(1.2, org.assertj.core.data.Offset.offset(0.05))

        // 4) a file that parses but does not decode (unknown op): rejected too, old version stays
        Files.writeString(gameplayFile, good.replace("op: spin, frame: shears", "op: no_such_op, frame: shears"))
        val undecodable = contents.reloadSkill(dir, baseId)
        assertThat(undecodable).isInstanceOf(Result.Failure::class.java)
        assertThat(cat.get(baseId)).describedAs("old version stays after a decode error").isSameAs(kept)

        // 5) fixing the file brings the skill back (the failed id is retried)
        Files.writeString(gameplayFile, good.replace("throw_speed: 1.2", "throw_speed: 0.9"))
        assertThat(contents.reloadSkill(dir, baseId)).isInstanceOf(Result.Success::class.java)
        assertThat(flightSpeed(c, cat)).isEqualTo(0.9, org.assertj.core.data.Offset.offset(0.05))

        // unknown skill and reload before the first full load
        val unknown = contents.reloadSkill(dir, "no-such-skill")
        assertThat((unknown as Result.Failure).error).isInstanceOf(SkillReloadError.UnknownSkill::class.java)
        val fresh = SkillEngineContents(c.newCatalog()).reloadSkill(dir, baseId)
        assertThat((fresh as Result.Failure).error).isEqualTo(SkillReloadError.NotLoaded)
    }
}
