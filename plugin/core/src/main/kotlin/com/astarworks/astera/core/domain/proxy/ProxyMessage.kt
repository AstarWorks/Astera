package com.astarworks.astera.core.domain.proxy

/**
 * A single inbound or outbound plugin message on the proxy.
 *
 * Equality is structural over [fromServer] + [channel] + the *contents* of
 * [payload] — the default `data class` synthesized `equals` on `ByteArray`
 * would only compare identity, which is wrong for value objects flowing
 * through the bus. We therefore override [equals] / [hashCode] explicitly.
 *
 * The payload is intentionally `ByteArray` (not e.g. JSON) because:
 * 1. Plugin messaging on Paper/Velocity is byte-oriented natively.
 * 2. Sub-protocols on top (sub-channel routing, length-prefixed records,
 *    protobuf, MessagePack, plain UTF-8 strings) live in a higher layer.
 *    Forcing one here would invent a Phase 1 wire schema we don't need yet.
 */
public class ProxyMessage(
    public val fromServer: ServerName,
    public val channel: ProxyChannel,
    public val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProxyMessage) return false
        return fromServer == other.fromServer &&
            channel == other.channel &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = fromServer.hashCode()
        result = 31 * result + channel.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "ProxyMessage(from=$fromServer, channel=$channel, payloadBytes=${payload.size})"
}
