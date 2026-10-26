package com.astarworks.astera.e2e

import java.util.Base64
import java.util.UUID

/** Poll from the harness, never block a server scheduler waiting for a different owner. */
internal fun Rcon.probe(query: String): String {
    val token = UUID.randomUUID().toString()
    val prefix = "E2E_PROBE $token "
    var reply = command("e2eprobe $token $query")
    val deadline = System.nanoTime() + 8_000_000_000L
    while (true) {
        check(reply.startsWith(prefix)) { "Invalid state probe reply for '$query': $reply" }
        val status = reply.removePrefix(prefix).trim()
        if (status != "PENDING") {
            val payload = String(Base64.getDecoder().decode(status.substringAfter(' ', "")), Charsets.UTF_8)
            check(status.startsWith("OK ")) { "State probe failed for '$query': $payload" }
            return payload
        }
        check(System.nanoTime() < deadline) { "State probe timed out: $query" }
        Thread.sleep(10)
        reply = command("e2eprobe $token")
    }
}
