package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Skill engine v3 (ADR-0303) on a real server. W1-21: the `lock pitch` hold (10.6, founder Q3 default A).
 * The probe skill `lock-pitch-probe` is `{op: lock, pitch: true, at: 0, for: 40}` only (content owned by the W2 content track);
 * the cast goes through `astera skill cast`. While the window is open an out-of-band pitch change (RCON `tp`) must be pulled
 * back by the gateway's 1-degree drift re-send; after the window it must stay.
 */
class SkillEngineV3E2eTest : E2eBase() {

    private val suite = "SkillEngine v3"

    private fun clean(s: String): String = s.replace(Regex("§."), "").trim()

    private fun pitchOf(selector: String): Double? =
        Regex("(-?\\d+(\\.\\d+)?)f").findAll(clean(rcon("data get entity $selector Rotation"))).toList().getOrNull(1)?.groupValues?.get(1)?.toDoubleOrNull()

    @Test
    fun lockPitchHoldsAndReleases() {
        val a = bot("Lock")
        op(a)
        Thread.sleep(500)
        rcon("execute in minecraft:overworld run tp ${a.name} 0.5 -60 0.5 0 -10")
        Thread.sleep(800)
        val sel = a.name
        val logMark = ServerLog.lineCount()
        rcon("astera skill cast lock-pitch-probe ${a.name} --force")
        Thread.sleep(300)
        rcon("tp ${a.name} ~ ~ ~ 0 -60")
        Thread.sleep(500)
        val held = pitchOf(sel)

        E2eReport.check(suite, "pitch is pulled back to the value held at the lock", "RCON data get entity Rotation") {
            assertThat(held).describedAs("pitch during the 40-tick window").isNotNull()
            assertThat(held!!).describedAs("held pitch (-10), not the forced -60").isBetween(-11.5, -8.5)
            "pitch $held"
        }

        Thread.sleep(2_500)
        rcon("tp ${a.name} ~ ~ ~ 0 -60")
        Thread.sleep(500)
        val released = pitchOf(sel)

        E2eReport.check(suite, "pitch is free after the window", "RCON data get entity Rotation") {
            assertThat(released).isNotNull()
            assertThat(released!!).describedAs("pitch after the lock window").isBetween(-61.5, -58.5)
            "pitch $released"
        }

        E2eReport.check(suite, "no skill-engine error in the server log", "server log") {
            val errors = ServerLog.read().lineSequence().drop(logMark).toList()
                .filter { (it.contains("[skill-error]") || it.contains("skillengine", true) || it.contains("skill engine", true)) && (it.contains("ERROR") || it.contains("WARN")) }
            assertThat(errors).describedAs("skill-engine log problems").isEmpty()
            "clean"
        }
    }

    /** The probe skill's directory, mounted read-write into the runner (docker-compose.e2e.yml `e2e-skill-probe`); the server sees it as content/skills/reload-probe. */
    private val probeDir: File = File(System.getProperty("astera.e2e.skillProbeDir", "/e2e-skill-probe"))

    private fun expandDump(id: String): String = clean(rcon("astera skill expand $id"))

    private fun skillErrorsSince(mark: Int): List<String> = ServerLog.read().lineSequence().drop(mark).toList()
        .filter { (it.contains("[skill-error]") || it.contains("skillengine", true) || it.contains("skill engine", true)) && (it.contains("ERROR") || it.contains("WARN")) }

    /**
     * Founder #14 (hot reload): rewrite one skill's YAML, run `astera skill reload <id>`; the next dump of that skill changes and the dump of
     * another skill (lock-pitch-probe) is byte-identical. The probe skill is baked by docker/e2e-tune-content.sh; its file is restored at the end.
     */
    @Test
    fun reloadOneSkillChangesNextCastOnly() {
        val file = File(probeDir, "skill.yaml")
        check(file.isFile) { "reload probe not mounted: ${file.absolutePath} (docker-compose.e2e.yml e2e-skill-probe, -Dastera.e2e.skillProbeDir)" }
        val original = file.readText()
        check(original.contains("for: 40")) { "probe skill.yaml has no 'for: 40' to rewrite" }
        val logMark = ServerLog.lineCount()
        try {
            val probeBefore = expandDump("reload-probe")
            val otherBefore = expandDump("lock-pitch-probe")

            file.writeText(original.replace("for: 40", "for: 30"))
            val reply = clean(rcon("astera skill reload reload-probe"))
            val probeAfter = expandDump("reload-probe")
            val otherAfter = expandDump("lock-pitch-probe")

            E2eReport.check(suite, "reload of one skill changes that skill's expansion dump", "RCON astera skill expand reload-probe, before/after reload") {
                assertThat(probeBefore).describedAs("dump before the reload").isNotBlank()
                assertThat(probeAfter).describedAs("dump after the reload; reload reply='$reply'").isNotEqualTo(probeBefore)
                "dump changed (${probeBefore.length} -> ${probeAfter.length} chars), reload reply='$reply'"
            }

            E2eReport.check(suite, "reload of one skill leaves another skill's dump untouched", "RCON astera skill expand lock-pitch-probe, before/after reload") {
                assertThat(otherBefore).isNotBlank()
                assertThat(otherAfter).describedAs("lock-pitch-probe dump after reloading reload-probe").isEqualTo(otherBefore)
                "lock-pitch-probe dump identical (${otherAfter.length} chars)"
            }

            E2eReport.check(suite, "no skill-engine error in the server log for the reload", "server log") {
                assertThat(skillErrorsSince(logMark)).describedAs("skill-engine log problems").isEmpty()
                "clean"
            }
        } finally {
            file.writeText(original)
            runCatching { rcon("astera skill reload reload-probe") }
        }
    }

    private fun weaponDisplaysNear(player: String): Int =
        Regex("count: (\\d+)").find(clean(rcon("execute at $player if entity @e[type=minecraft:item_display,distance=..8,nbt={item:{id:\"minecraft:golden_sword\"}}]")))
            ?.groupValues?.get(1)?.toInt() ?: 0

    /**
     * Founder #27 (one weapon): during a bastion cast the rig shows the held weapon as one ItemDisplay (the real held item is swapped out for the
     * window). The server runs at `tick rate 5` for the case (as tools/vfx-shot does) so every tick lasts 200 ms and the RCON sampler (between ticks,
     * on the main thread) sees each tick's end state several times; `tick freeze`/`step` is not used because it does not freeze the Bukkit scheduler.
     * Every sample from 1 s after the cast until 1 s before the end tick must count exactly one golden_sword ItemDisplay near the caster.
     */
    @Test
    fun heldModelSwapShowsOneWeaponEveryTick() {
        val a = bot("OneWeapon")
        op(a)
        Thread.sleep(500)
        rcon("execute in minecraft:overworld run tp ${a.name} 0.5 -60 0.5 0 0")
        rcon("item replace entity ${a.name} weapon.mainhand with minecraft:golden_sword")
        Thread.sleep(800)
        val baseline = weaponDisplaysNear(a.name)
        val endTick = 58
        val slowRate = 5
        val tickMs = 1000L / slowRate
        val samples = mutableListOf<Int>()
        val logMark = ServerLog.lineCount()
        try {
            rcon("tick rate $slowRate")
            val reply = clean(rcon("astera skill cast bastion-greatsword-s1 ${a.name} --force"))
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < endTick * tickMs - 1_000) {
                if (System.currentTimeMillis() - started >= 1_000) samples += weaponDisplaysNear(a.name)
                Thread.sleep(20)
            }
            E2eReport.check(suite, "bastion cast shows exactly one weapon ItemDisplay at every sampled tick", "RCON execute if entity @e[type=item_display,nbt=golden_sword] at tick rate $slowRate") {
                assertThat(baseline).describedAs("weapon displays before the cast").isEqualTo(0)
                assertThat(samples.size).describedAs("samples taken; cast reply='$reply'").isGreaterThan(20)
                assertThat(samples.filter { it != 1 }).describedAs("samples that did not count exactly 1 (of ${samples.size})").isEmpty()
                "${samples.size} samples, all 1"
            }
        } finally {
            rcon("tick rate 20")
        }
        E2eReport.check(suite, "no skill-engine error in the server log for the one-weapon cast", "server log") {
            assertThat(skillErrorsSince(logMark)).describedAs("skill-engine log problems").isEmpty()
            "clean"
        }
    }
}
