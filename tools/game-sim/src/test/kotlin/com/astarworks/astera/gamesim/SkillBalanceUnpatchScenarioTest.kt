package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * SkillEngineV3E2eTest#reloadAppliesBalancePatchOnNextCast on the real wiring: the e2e probe skill is cast, a balance patch beside it
 * (`dmg` 10 -> 20) is written and `reloadSkill` (what `/astera skill reload reload-probe` calls) is run, the patch file is deleted and
 * `reloadSkill` runs again. The next cast must deal the base damage again, so a patch that survives its file's removal fails here.
 */
class SkillBalanceUnpatchScenarioTest {
    private fun contentWithProbe(): Path {
        val real = GameSim.defaultContentDir()
        val dir = Files.createTempDirectory("sim-content-unpatch")
        Files.list(real).use { entries ->
            entries.filter { it.fileName.toString() != "skills" }.forEach { Files.createSymbolicLink(dir.resolve(it.fileName.toString()), it) }
        }
        val probe = dir.resolve("skills/reload-probe")
        Files.createDirectories(probe)
        Files.copy(GameSim.defaultContentDir().resolve("../docker/e2e-fixtures/reload-probe.yaml").normalize(), probe.resolve("skill.yaml"))
        return dir
    }

    @Test
    fun `removing a balance patch file and reloading the skill restores the base damage on the next cast`() {
        val content = contentWithProbe()
        val sim = GameSim.boot("skill-balance-unpatch", contentDir = content)
        try {
            ScenarioScope(sim).run {
                val caster = player("caster", at = Vec3(0.0, groundY, 0.0), team = "red")
                val dummy = mob("dummy", at = Vec3(0.0, groundY, 4.0), team = "blue")
                caster.faces(dummy)
                sim.setMaxHealth(dummy, 5000.0)
                val patchFile = content.resolve("skills/reload-probe/balance.yaml")

                fun castDamage(): Double {
                    val seen = eventsOf<DamageDealt>().count { it.target == dummy.id }
                    sim.cast(caster, "reload-probe", "diamond-axe")
                    advance(60)
                    val all = eventsOf<DamageDealt>().filter { it.target == dummy.id }.map { it.resolved.finalAmount }
                    return all.drop(seen).sum()
                }

                fun reload() = assertThat(sim.combat.reloadSkill("reload-probe")).describedAs("reload").isInstanceOf(Result.Success::class.java)

                val base = castDamage()
                assertThat(base).describedAs("base damage").isGreaterThan(0.0)
                Files.writeString(
                    patchFile,
                    "apiVersion: astera/v3\nkind: Patch\nid: reload-probe-overlay-balance\ntarget: reload-probe\npatchKind: balance\nops:\n" +
                        "  - {path: \"params.dmg.default\", \$patch: replace, value: 20}\n",
                )
                reload()
                assertThat(castDamage()).describedAs("tuned damage").isEqualTo(base * 2)
                Files.delete(patchFile)
                reload()
                assertThat(castDamage()).describedAs("damage after removing the patch").isEqualTo(base)
                assertThat(sim.skillEngine.problems).isEmpty()
            }
        } finally {
            sim.shutdown()
        }
    }
}
