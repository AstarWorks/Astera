package com.astarworks.astera.e2e

import java.io.File

/** The backend's server log: a mounted latest.log (runner container) or `docker logs` (host mode). */
object ServerLog {
    fun read(): String {
        val path = System.getProperty("astera.e2e.serverLog")
        if (path != null && File(path).exists()) return File(path).readText()
        return runCatching {
            val p = ProcessBuilder("docker", "logs", "${E2eEnv.project}-e2e-1").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        }.getOrDefault("")
    }

    fun lines(contains: String): List<String> = read().lineSequence().filter { contains in it }.toList()
}
