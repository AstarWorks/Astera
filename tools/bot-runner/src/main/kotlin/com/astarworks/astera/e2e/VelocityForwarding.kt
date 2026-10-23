package com.astarworks.astera.e2e

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Velocity "modern" player-info forwarding, client side (QA-2, ADR-0221).
 *
 * A Paper backend with `proxies.velocity.enabled=true` sends a login plugin request on channel
 * `velocity:player_info` and only lets the connection through when the answer is
 * `HMAC-SHA256(secret, body) ++ body`, body = varint version(1), address, UUID, name, properties.
 * A bot that sends that answer is indistinguishable from a player coming through the proxy.
 */
object VelocityForwarding {
    const val CHANNEL = "velocity:player_info"
    private const val DEFAULT_VERSION = 1

    fun payload(secret: String, clientAddress: String, uuid: UUID, name: String): ByteArray {
        val body = ByteArrayOutputStream().apply {
            varInt(DEFAULT_VERSION)
            string(clientAddress)
            write(ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array())
            string(name)
            varInt(0) // no profile properties (offline-style profile: no skin)
        }.toByteArray()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        return mac.doFinal(body) + body
    }

    private fun ByteArrayOutputStream.varInt(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        write(v)
    }

    private fun ByteArrayOutputStream.string(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        varInt(b.size)
        write(b)
    }
}
