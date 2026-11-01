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

    /**
     * Founder #13/#14 (patch separate from the skill, reload one skill): a `kind: Patch` file next to the probe skill (skin patch: recolours the
     * `glow` particle) is picked up by `astera skill reload reload-probe`; the probe's dump changes and another skill's stays byte-identical.
     * A malformed patch (a skin patch touching a gameplay op) is rejected loudly and the probe keeps the patched version (no half state); removing
     * the patch and reloading restores the base dump. The balance patch (damage x2) is covered by [reloadAppliesBalancePatchOnNextCast].
     */
    @Test
    fun reloadAppliesSeparatePatchAndKeepsOldVersionOnBadPatch() {
        val skill = File(probeDir, "skill.yaml")
        check(skill.isFile) { "reload probe not mounted: ${skill.absolutePath}" }
        check(skill.readText().contains("id: glow")) { "probe skill.yaml has no 'glow' particle row (docker/e2e-tune-content.sh not rebuilt)" }
        val patchFile = File(probeDir, "skin.yaml")
        fun patchDoc(ops: String) = "apiVersion: astera/v3\nkind: Patch\nid: reload-probe-overlay-skin\ntarget: reload-probe\npatchKind: skin\nops:\n$ops\n"
        val logMark = ServerLog.lineCount()
        try {
            val base = expandDump("reload-probe")
            val otherBefore = expandDump("lock-pitch-probe")

            patchFile.writeText(patchDoc("""  - {path: "machine.states.hold.do[glow].color", ${'$'}patch: replace, value: "#3a7bff"}"""))
            val okReply = clean(rcon("astera skill reload reload-probe"))
            val patched = expandDump("reload-probe")
            val otherAfter = expandDump("lock-pitch-probe")

            E2eReport.check(suite, "a separate patch file changes the skill's dump on reload; another skill is untouched", "RCON astera skill expand reload-probe / lock-pitch-probe, before/after reload") {
                assertThat(base).isNotBlank()
                assertThat(patched).describedAs("dump after adding the patch; reload reply='$okReply'").isNotEqualTo(base)
                assertThat(otherAfter).describedAs("lock-pitch-probe dump").isEqualTo(otherBefore)
                "dump changed (${base.length} -> ${patched.length} chars); other skill identical"
            }

            patchFile.writeText(patchDoc("""  - {path: "machine.states.hold.do[pin].for", ${'$'}patch: replace, value: 1}"""))
            val badReply = clean(rcon("astera skill reload reload-probe"))
            val afterBad = expandDump("reload-probe")

            E2eReport.check(suite, "a malformed patch is rejected loudly and the skill keeps its old version", "RCON astera skill reload reload-probe with a skin patch on a gameplay op") {
                assertThat(badReply).describedAs("reload reply for the bad patch").containsIgnoringCase("rejected")
                assertThat(afterBad).describedAs("dump after the rejected reload").isEqualTo(patched)
                "reply='${badReply.take(160)}'; dump unchanged"
            }

            patchFile.delete()
            val restoreReply = clean(rcon("astera skill reload reload-probe"))
            val restored = expandDump("reload-probe")
            E2eReport.check(suite, "removing the patch and reloading restores the base dump", "RCON astera skill expand reload-probe") {
                assertThat(restored).describedAs("dump after removing the patch; reload reply='$restoreReply'").isEqualTo(base)
                "base dump restored"
            }
        } finally {
            patchFile.delete()
            runCatching { rcon("astera skill reload reload-probe") }
        }
        E2eReport.check(suite, "no unexpected skill-engine error besides the rejected patch", "server log") {
            val unexpected = skillErrorsSince(logMark).filterNot { it.contains("reload-probe") }
            assertThat(unexpected).describedAs("skill-engine log problems").isEmpty()
            "clean"
        }
    }

    private fun healthOf(selector: String): Double? =
        Regex("(-?\\d+(\\.\\d+)?)").find(clean(rcon("data get entity $selector Health")).substringAfter("has the following entity data:"))
            ?.groupValues?.get(1)?.toDoubleOrNull()

    /**
     * Founder #13/#14, balance: a test-only `patchKind: balance` file next to the probe skill doubles the `dmg` param (10 -> 20). After
     * `astera skill reload reload-probe` the NEXT cast hurts a dummy twice as much (ratio 1.8..2.2: the damage matrix may scale the number);
     * removing the patch and reloading brings it back to the base damage. The probe row is `{op: damage, damage: {physical: "${'$'}{dmg}"}}`
     * around the caster (docker/e2e-tune-content.sh).
     */
    @Test
    fun reloadAppliesBalancePatchOnNextCast() {
        val skill = File(probeDir, "skill.yaml")
        check(skill.isFile && skill.readText().contains("id: hit")) { "reload probe has no 'hit' damage row (docker/e2e-tune-content.sh not rebuilt): ${skill.absolutePath}" }
        val patchFile = File(probeDir, "balance.yaml")
        val a = bot("Balance")
        op(a)
        Thread.sleep(500)
        val tag = "reload_dummy"
        val dummy = "@e[type=minecraft:zombie,tag=$tag,limit=1]"
        rcon("kill @e[type=minecraft:zombie,tag=$tag]")
        rcon("execute in minecraft:overworld run tp ${a.name} 0.5 -60 0.5 0 0")
        Thread.sleep(800)
        rcon("execute in minecraft:overworld run summon minecraft:zombie 0.5 -60 3.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"$tag\"],attributes:[{id:\"minecraft:max_health\",base:500}],Health:500f,active_effects:[{id:\"minecraft:fire_resistance\",duration:100000,show_particles:0b}]}")
        Thread.sleep(1_000)

        fun castAndMeasure(): Double {
            rcon("data merge entity $dummy {Health:500f}")
            Thread.sleep(300)
            val before = healthOf(dummy) ?: error("dummy health unreadable")
            rcon("astera skill cast reload-probe ${a.name} --force")
            Thread.sleep(1_500)
            val after = healthOf(dummy) ?: error("dummy health unreadable after the cast")
            Thread.sleep(2_500) // the 40-tick hold ends and the invulnerability window closes
            return before - after
        }

        val logMark = ServerLog.lineCount()
        try {
            val baseDump = expandDump("reload-probe")
            val base = castAndMeasure()
            patchFile.writeText("apiVersion: astera/v3\nkind: Patch\nid: reload-probe-overlay-balance\ntarget: reload-probe\npatchKind: balance\nops:\n  - {path: \"params.dmg.default\", ${'$'}patch: replace, value: 20}\n")
            val reply = clean(rcon("astera skill reload reload-probe"))
            val tunedDump = expandDump("reload-probe")
            val tuned = castAndMeasure()
            check(patchFile.delete()) { "could not delete the balance patch ${patchFile.absolutePath}" }
            val reply2 = clean(rcon("astera skill reload reload-probe"))
            val restoredDump = expandDump("reload-probe")
            val restored = castAndMeasure()

            // The compiled table is the product layer, the Health delta the runtime layer: a red names which one drifted.
            E2eReport.check(suite, "removing the balance patch and reloading restores the base table dump", "RCON astera skill expand reload-probe, before/after each reload") {
                assertThat(restoredDump).describedAs("table dump after removing the patch; reload reply='$reply2'; patched dump differs=${tunedDump != baseDump}").isEqualTo(baseDump)
                "table dump restored"
            }

            E2eReport.check(suite, "balance patch doubles the next cast's damage after reload, removing it restores the base", "dummy Health before/after each cast around astera skill reload") {
                assertThat(base).describedAs("base damage").isGreaterThan(0.0)
                assertThat(tuned / base).describedAs("tuned/base damage ratio (base=$base tuned=$tuned); reload reply='$reply'").isBetween(1.8, 2.2)
                assertThat(restored).describedAs("damage after removing the patch; reload reply='$reply2'").isCloseTo(base, org.assertj.core.data.Offset.offset(0.5))
                "base=$base tuned=$tuned restored=$restored"
            }
        } finally {
            patchFile.delete()
            runCatching { rcon("astera skill reload reload-probe") }
            rcon("kill @e[type=minecraft:zombie,tag=$tag]")
        }
        E2eReport.check(suite, "no skill-engine error in the server log for the balance reload", "server log") {
            assertThat(skillErrorsSince(logMark)).describedAs("skill-engine log problems").isEmpty()
            "clean"
        }
    }

    private fun weaponDisplaysNear(player: String): Int =
        clean(rcon("execute at $player if entity @e[type=minecraft:item_display,distance=..8,nbt={item:{id:\"minecraft:golden_sword\"}}]")).let { reply ->
            // exactly one match replies "Test passed" with no count; two or more reply "Test passed, count: N"
            Regex("count: (\\d+)").find(reply)?.groupValues?.get(1)?.toInt() ?: if (reply.startsWith("Test passed")) 1 else 0
        }

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

    private fun posOf(selector: String): Triple<Double, Double, Double>? =
        Regex("(-?\\d+(?:\\.\\d+)?)d").findAll(clean(rcon("data get entity $selector Pos"))).map { it.groupValues[1].toDouble() }.toList()
            .takeIf { it.size >= 3 }?.let { Triple(it[0], it[1], it[2]) }

    private fun faultTotal(): Int? = Regex("faults total=(\\d+)").find(clean(rcon("astera skill faults")))?.groupValues?.get(1)?.toInt()

    /**
     * Audit G3 (DashClearance proven on Paper): a caster dashes straight at a dummy 3 blocks ahead. `katana-iaido` runs through its victim
     * (`pass_through`), `spear-charge` stops at it; neither may end with the caster's eye inside the dummy's hitbox (0.6 x 1.95 zombie box on
     * Paper's real `boundingBox`), and the engine's fault counter (`astera skill faults`, per skill id and kind) must still read 0 afterwards.
     */
    @Test
    fun dashNeverEndsInsideVictimAndRaisesNoFault() {
        val a = bot("DashClear")
        op(a)
        Thread.sleep(500)
        val tag = "dash_dummy"
        val dummy = "@e[type=minecraft:zombie,tag=$tag,limit=1]"
        rcon("kill @e[type=minecraft:zombie,tag=$tag]")
        Thread.sleep(300)
        runCatching { rcon("astera skill faults reset") }
        val logMark = ServerLog.lineCount()
        try {
            for (skill in listOf("katana-iaido", "spear-charge")) {
                rcon("execute in minecraft:overworld run tp ${a.name} 0.5 -60 0.5 0 0")
                rcon("kill @e[type=minecraft:zombie,tag=$tag]")
                Thread.sleep(800)
                rcon("execute in minecraft:overworld run summon minecraft:zombie 0.5 -60 3.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"$tag\"],attributes:[{id:\"minecraft:max_health\",base:2000}],Health:2000f,active_effects:[{id:\"minecraft:fire_resistance\",duration:100000,show_particles:0b}]}")
                Thread.sleep(800)
                rcon("astera skill cast $skill ${a.name} --force")
                Thread.sleep(2_000)
                val caster = posOf(a.name)
                val victim = posOf(dummy)
                E2eReport.check(suite, "$skill dash does not end with the caster eye inside the dummy hitbox", "RCON data get entity Pos (caster, dummy) after the cast") {
                    assertThat(caster).describedAs("caster Pos").isNotNull()
                    assertThat(victim).describedAs("dummy Pos (alive)").isNotNull()
                    val eyeY = caster!!.second + 1.62
                    val inside = Math.abs(caster.first - victim!!.first) < 0.3 && Math.abs(caster.third - victim.third) < 0.3 &&
                        eyeY > victim.second && eyeY < victim.second + 1.95
                    assertThat(inside).describedAs("caster $caster eyeY=$eyeY inside dummy $victim").isFalse()
                    "caster=$caster dummy=$victim"
                }
            }
            E2eReport.check(suite, "no engine fault was counted for the dash casts", "RCON astera skill faults") {
                assertThat(faultTotal()).describedAs("fault total after the dash casts").isEqualTo(0)
                "faults total=0"
            }
        } finally {
            rcon("kill @e[type=minecraft:zombie,tag=$tag]")
        }
        E2eReport.check(suite, "no skill-engine error in the server log for the dash casts", "server log") {
            assertThat(skillErrorsSince(logMark)).describedAs("skill-engine log problems").isEmpty()
            "clean"
        }
    }
}
