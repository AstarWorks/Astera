package com.astarworks.astera.e2e

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * QA-7: the production front door. Real players always join through Velocity, which re-decodes some
 * clientbound packets (commands tree, chat, player info, boss bars, ...). A packet Velocity cannot
 * decode kills the join ("loading terrain" forever) while a direct-to-backend bot never notices.
 * Runs with `-Dvelocity.packet-decode-logging=true`, so the log names the offending packet.
 */
class VelocityE2eTest : E2eBase() {

    private val suite = "Velocity"

    @Test
    fun `join through Velocity succeeds without decode errors`() {
        assumeTrue(E2eEnv.viaVelocity, "bots connect directly (E2E_VIA=direct): nothing to check")
        val b = bot("ViaVel", PackPolicy.LOAD)
        b.waitUntil(8_000) { b.sidebarLines().isNotEmpty() && b.actionBars.isNotEmpty() }
        Thread.sleep(2_000) // Velocity logs a failed backend packet decode a moment after it happens

        E2eReport.check(suite, "bot joins through Velocity and stays connected", "Velocity -> backend (modern forwarding)") {
            assertThat(b.joined).describedAs("joined via ${E2eEnv.proxyHost}; disconnect=${b.disconnectReason}").isTrue()
            assertThat(b.disconnectReason).describedAs("disconnect reason").isNull()
            "joined as ${b.name} via ${E2eEnv.proxyHost}:${E2eEnv.proxyPort}"
        }
        E2eReport.check(suite, "Velocity log has no exception / decode error", "velocity latest.log") {
            val path = E2eEnv.velocityLog
            assertThat(path).describedAs("astera.e2e.velocityLog not set").isNotNull()
            val file = File(path!!)
            assertThat(file).describedAs("Velocity log $path").exists()
            val lines = file.readLines()
            val bad = lines.filter { Regex("Exception|did not decode|Failed to decode|decode.*(error|fail)|\\bERROR\\b").containsMatchIn(it) }
            assertThat(bad).describedAs("Velocity decode errors / exceptions").isEmpty()
            assertThat(lines.any { b.name in it }).describedAs("Velocity never logged ${b.name} connecting").isTrue()
            "${lines.size} log lines, ${b.name} seen, 0 errors"
        }
    }
}
