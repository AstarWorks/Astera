package com.astarworks.astera.core.application.service.handoff

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.HandoffToken
import com.astarworks.astera.core.domain.proxy.ServerName
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Wire format of a [HandoffToken] on `ProxyChannel.HANDOFF`. [decode] returns `null` for a malformed frame. */
public object HandoffTokenCodec {

    private const val WIRE_VERSION: Int = 1

    public fun encode(token: HandoffToken): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeInt(WIRE_VERSION)
            it.writeUTF(token.playerId.uuid.toString())
            it.writeLong(token.version)
            it.writeUTF(token.fromServer.value)
        }
        return bytes.toByteArray()
    }

    public fun decode(payload: ByteArray): HandoffToken? = try {
        DataInputStream(ByteArrayInputStream(payload)).use {
            require(it.readInt() == WIRE_VERSION)
            HandoffToken(PlayerId(UUID.fromString(it.readUTF())), it.readLong(), ServerName(it.readUTF()))
        }
    } catch (_: Exception) {
        null
    }
}
