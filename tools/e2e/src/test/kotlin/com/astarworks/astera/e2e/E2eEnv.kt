package com.astarworks.astera.e2e

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Where the offline-mode `e2e` backend lives (docker/docker-compose.e2e.yml, loopback only). */
object E2eEnv {
    val host: String = System.getProperty("astera.e2e.host", "127.0.0.1")
    val port: Int = System.getProperty("astera.e2e.port", "25570").toInt()
    val rconPort: Int = System.getProperty("astera.e2e.rconPort", "25571").toInt()
    val rconPassword: String = System.getProperty("astera.e2e.rconPassword", "astera-e2e")
    val autostart: Boolean = System.getProperty("astera.e2e.autostart", "false").toBoolean()
    private val composeFile: String? = System.getProperty("astera.e2e.composeFile")

    private fun portOpen(p: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, p), 1_000) }
        true
    } catch (_: Exception) {
        false
    }

    /** Makes sure the backend answers RCON (= "Done" printed, every Astera plugin enabled). */
    fun ensureUp() {
        if (!portOpen(rconPort)) {
            check(autostart && composeFile != null) {
                "e2e backend is not reachable on $host:$rconPort. Run `make e2e` (or `docker compose -p astera-e2e " +
                    "-f docker/docker-compose.e2e.yml --profile e2e up -d --build`), or pass -Pe2e.autostart=true."
            }
            val proc = ProcessBuilder(
                "docker", "compose", "-p", "astera-e2e", "-f", composeFile, "--profile", "e2e", "up", "-d", "--build",
            ).inheritIO().start()
            check(proc.waitFor(20, TimeUnit.MINUTES) && proc.exitValue() == 0) { "docker compose up failed" }
        }
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4)
        var last: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                Rcon(host, rconPort, rconPassword).use { it.command("list") }
                return
            } catch (t: Throwable) {
                last = t
                Thread.sleep(2_000)
            }
        }
        throw IllegalStateException("e2e backend did not become ready within 4 minutes", last)
    }
}
