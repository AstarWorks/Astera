package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.usecase.skin.SkinError
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Skill skins and the global balance overlay (engine-gaps-v2 2.3), through the real wiring and the shipped `couture-rapier-s2@crimson` skin:
 * an owned and selected skin resolves to the variant, a skin that is not owned casts the base, grants are validated against the catalog,
 * and a balance overlay written while the server runs changes the damage of the base and the skin on the next cast after a reload.
 */
class SkillSkinSelectionScenarioTest {
    private val base = "couture-rapier-s2"

    /** A copy of the shipped content with its own `skills/` tree (the rest is linked), so a scenario can add files to it. */
    private fun contentWithOwnSkills(): Path {
        val real = GameSim.defaultContentDir()
        val dir = Files.createTempDirectory("sim-content-skins")
        Files.list(real).use { entries ->
            entries.filter { it.fileName.toString() != "skills" }.forEach { Files.createSymbolicLink(dir.resolve(it.fileName.toString()), it) }
        }
        val from = real.resolve("skills")
        Files.walk(from).use { s ->
            s.forEach { p ->
                val to = dir.resolve("skills").resolve(from.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(to) else Files.copy(p, to)
            }
        }
        return dir
    }

    private fun withSim(name: String, content: Path = GameSim.defaultContentDir(), block: ScenarioScope.() -> Unit) {
        val sim = GameSim.boot(name, contentDir = content)
        try {
            ScenarioScope(sim).block()
        } finally {
            sim.shutdown()
        }
    }

    @Test
    fun `an owned selected skin resolves, an unowned or cleared one casts the base`() = withSim("skill-skin-resolve") {
        val skins = sim.combat.skillSkins
        val owner = player("owner", at = Vec3(0.0, groundY, 0.0), team = "red").also { it.holds("couture-rapier") }
        val stranger = player("stranger", at = Vec3(10.0, groundY, 0.0), team = "red").also { it.holds("couture-rapier") }
        val cleared = player("cleared", at = Vec3(20.0, groundY, 0.0), team = "red").also { it.holds("couture-rapier") }
        for (p in listOf(owner, stranger, cleared)) {
            val dummy = mob("dummy-${p.name}", at = Vec3(p.position.x, groundY, 4.0), team = "blue")
            p.faces(dummy)
            sim.setMaxHealth(dummy, 500.0)
        }

        assertThat(skins.select(owner.id, base, "crimson")).describedAs("select before grant").isEqualTo(Result.failure(SkinError.NotOwned(base, "crimson")))
        assertThat(skins.grant(owner.id, base, "crimson")).isInstanceOf(Result.Success::class.java)
        assertThat(skins.select(owner.id, base, "crimson")).isInstanceOf(Result.Success::class.java)
        owner.cast(base)
        stranger.cast(base)
        advance(70)
        assertThat(sim.skillEngine.castLog).containsExactlyInAnyOrder("$base@crimson", base)

        // a skin that was selected and then cleared plays the base again (own caster: a second cast would hit the cost and cooldown gate)
        sim.skillEngine.castLog.clear()
        assertThat(skins.grant(cleared.id, base, "crimson")).isInstanceOf(Result.Success::class.java)
        assertThat(skins.select(cleared.id, base, "crimson")).isInstanceOf(Result.Success::class.java)
        assertThat(skins.select(cleared.id, base, null)).isInstanceOf(Result.Success::class.java)
        cleared.cast(base)
        advance(10)
        assertThat(sim.skillEngine.castLog).containsExactly(base)
        assertThat(sim.skillEngine.problems).isEmpty()
    }

    @Test
    fun `granting an unknown variant or a non-skin variant is refused`() = withSim("skill-skin-refused") {
        val p = player("p", team = "red")
        val skins = sim.combat.skillSkins
        assertThat(skins.grant(p.id, base, "no-such-skin")).isEqualTo(Result.failure(SkinError.UnknownVariant(base, "no-such-skin")))
        assertThat(skins.grant(p.id, "no-such-skill", "crimson")).isEqualTo(Result.failure(SkinError.UnknownVariant("no-such-skill", "crimson")))
    }

    @Test
    fun `a balance overlay written at runtime changes base and skin damage after a reload without a restart`() {
        val content = contentWithOwnSkills()
        withSim("skill-skin-balance-overlay", content) {
            val skins = sim.combat.skillSkins
            // four casters (a cast has a cooldown and a cost): {base, skin} x {before, after the overlay}
            val casters = listOf("base-before", "skin-before", "base-after", "skin-after").mapIndexed { i, n ->
                val p = player(n, at = Vec3(i * 10.0, groundY, 0.0), team = "red").also { it.holds("couture-rapier") }
                val dummy = mob("dummy-$n", at = Vec3(i * 10.0, groundY, 4.0), team = "blue")
                p.faces(dummy)
                sim.setMaxHealth(dummy, 500.0)
                Triple(n, p, dummy)
            }.associateBy { it.first }
            for (n in listOf("skin-before", "skin-after")) {
                val p = casters.getValue(n).second
                assertThat(skins.grant(p.id, base, "crimson")).isInstanceOf(Result.Success::class.java)
                assertThat(skins.select(p.id, base, "crimson")).isInstanceOf(Result.Success::class.java)
            }
            fun damageOf(n: String): List<Double> {
                val (_, _, dummy) = casters.getValue(n)
                return eventsOf<DamageDealt>().filter { it.target == dummy.id }.map { it.resolved.finalAmount }
            }

            for (n in listOf("base-before", "skin-before")) casters.getValue(n).second.cast(base)
            advance(70)
            assertThat(damageOf("base-before")).contains(18.0)
            assertThat(damageOf("skin-before")).contains(18.0)

            // the overlay: a patch without `variant` beside the skill, then the incremental reload the dev tooling runs; no restart
            val overlay = content.resolve("skills/$base/patches/balance-test.yaml")
            Files.writeString(
                overlay,
                """
                apiVersion: astera/v3
                kind: Patch
                id: couture-rapier-s2-balance-test
                target: $base
                patchKind: balance
                ops:
                  - {path: "params.dmg_throw.default", ${'$'}patch: replace, value: 25}
                """.trimIndent() + "\n",
            )
            // failures of unrelated shipped files do not belong to this scenario; the overlay and its skill must load
            assertThat(sim.combat.reloadSkills().failures.filter { "/$base/" in it.file.toString() }).describedAs("reload failures of $base").isEmpty()
            advance(5)

            for (n in listOf("base-after", "skin-after")) casters.getValue(n).second.cast(base)
            advance(70)
            assertThat(damageOf("base-after")).describedAs("base after overlay").contains(25.0).doesNotContain(18.0)
            assertThat(damageOf("skin-after")).describedAs("skin after overlay").contains(25.0).doesNotContain(18.0)
            assertThat(sim.skillEngine.castLog).containsExactly(base, "$base@crimson", base, "$base@crimson")
            assertThat(sim.skillEngine.problems).isEmpty()
        }
    }

    @Test
    fun `the shipped skin loads as a skin and a skin patch that changes gameplay is rejected at load`() {
        val content = contentWithOwnSkills()
        withSim("skill-skin-gameplay-rejected", content) {
            val shipped = sim.combat.skillCatalog.get("$base@crimson")
            assertThat(shipped).describedAs("shipped crimson skin").isNotNull
            assertThat(shipped!!.patchKind).isEqualTo("skin")
            assertThat(sim.combat.skillCatalog.get(base)!!.patchKind).isEqualTo("base")

            // a "skin" that also changes the damage: refused at load (check 13 on the op, check 14 on the compiled table), the base stays
            Files.writeString(
                content.resolve("skills/$base/patches/skin-stronger.yaml"),
                """
                apiVersion: astera/v3
                kind: Patch
                id: couture-rapier-s2-stronger
                target: $base
                variant: stronger
                patchKind: skin
                ops:
                  - {path: "params.dmg_throw.default", ${'$'}patch: replace, value: 99}
                """.trimIndent() + "\n",
            )
            val failures = sim.combat.reloadSkills().failures
            assertThat(failures.map { it.message }.joinToString("\n")).describedAs("reload failures").contains("dmg_throw")
            assertThat(sim.combat.skillCatalog.get("$base@stronger")).describedAs("rejected skin is not in the catalog").isNull()
            assertThat(sim.combat.skillCatalog.get(base)).describedAs("base still loaded").isNotNull
            assertThat(sim.combat.skillSkins.grant(player("p", team = "red").id, base, "stronger"))
                .isEqualTo(Result.failure(SkinError.UnknownVariant(base, "stronger")))
        }
    }
}
