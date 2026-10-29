package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
                .filter { (it.contains("skillengine", true) || it.contains("skill engine", true)) && (it.contains("ERROR") || it.contains("WARN")) }
            assertThat(errors).describedAs("skill-engine log problems").isEmpty()
            "clean"
        }
    }
}
