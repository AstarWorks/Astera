package com.astarworks.astera.core.adapter.paper.proxy

import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ProxyMessage
import com.astarworks.astera.core.domain.proxy.ServerName
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Wire frame of one [ProxyMessage] on a Redis pub/sub topic (ADR-0179):
 * `UTF(fromServer) UTF(channel) int(len) bytes(payload)`.
 */
internal object ProxyFrameCodec {

    fun encode(message: ProxyMessage): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF(message.fromServer.value)
            data.writeUTF(message.channel.name)
            data.writeInt(message.payload.size)
            data.write(message.payload)
        }
        return out.toByteArray()
    }

    /** `null` when the frame is truncated or carries an invalid channel / server name. */
    fun decode(bytes: ByteArray): ProxyMessage? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val from = ServerName(data.readUTF())
            val channel = ProxyChannel(data.readUTF())
            val size = data.readInt()
            require(size in 0..MAX_PAYLOAD)
            val payload = ByteArray(size)
            data.readFully(payload)
            ProxyMessage(from, channel, payload)
        }
    }.getOrNull()

    private const val MAX_PAYLOAD: Int = 1 shl 20
}
