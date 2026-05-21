package com.astarworks.astera.core.application.service.packet

import com.astarworks.astera.core.domain.packet.PacketDecision
import com.astarworks.astera.core.domain.packet.PacketEnvelope
import com.astarworks.astera.core.domain.packet.PacketKind
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPacketInterceptorTest {

    private val player = PlayerId(UUID.randomUUID())

    private fun env(
        kind: PacketKind,
        payload: Map<String, Any> = emptyMap(),
        atTick: Long = 0L,
    ): PacketEnvelope = PacketEnvelope(kind, player, payload, atTick)

    @Test
    fun `register + dispatch invokes listeners for matching kind only`() {
        val interceptor = InMemoryPacketInterceptor()
        val movementInvocations = mutableListOf<PacketEnvelope>()
        val chatInvocations = mutableListOf<PacketEnvelope>()
        interceptor.register(PacketKind.MovementServerbound) {
            movementInvocations += it
            PacketDecision.Pass
        }
        interceptor.register(PacketKind.ChatClientbound) {
            chatInvocations += it
            PacketDecision.Pass
        }

        interceptor.dispatch(env(PacketKind.MovementServerbound))

        assertThat(movementInvocations).hasSize(1)
        assertThat(chatInvocations).isEmpty()
    }

    @Test
    fun `cancel from any listener short-circuits the chain`() {
        val interceptor = InMemoryPacketInterceptor()
        var secondListenerInvoked = false
        interceptor.register(PacketKind.MovementServerbound) { PacketDecision.Cancel }
        interceptor.register(PacketKind.MovementServerbound) {
            secondListenerInvoked = true
            PacketDecision.Pass
        }

        val decision = interceptor.dispatch(env(PacketKind.MovementServerbound))

        assertThat(decision).isEqualTo(PacketDecision.Cancel)
        assertThat(secondListenerInvoked).isFalse()
    }

    @Test
    fun `Replace passes merged payload to subsequent listener`() {
        val interceptor = InMemoryPacketInterceptor()
        val observed = mutableListOf<Map<String, Any>>()
        interceptor.register(PacketKind.ChatClientbound) {
            PacketDecision.Replace(mapOf("message" to "[masked]"))
        }
        interceptor.register(PacketKind.ChatClientbound) {
            observed += it.payloadView
            PacketDecision.Pass
        }

        interceptor.dispatch(env(PacketKind.ChatClientbound, mapOf("message" to "original", "chatType" to "global")))

        assertThat(observed).hasSize(1)
        assertThat(observed[0]["message"]).isEqualTo("[masked]")
        assertThat(observed[0]["chatType"]).isEqualTo("global")
    }

    @Test
    fun `Replace is preserved as the final decision when no Cancel follows`() {
        val interceptor = InMemoryPacketInterceptor()
        interceptor.register(PacketKind.ChatClientbound) {
            PacketDecision.Replace(mapOf("message" to "[masked]"))
        }

        val decision = interceptor.dispatch(env(PacketKind.ChatClientbound, mapOf("message" to "original")))

        assertThat(decision).isInstanceOf(PacketDecision.Replace::class.java)
        assertThat((decision as PacketDecision.Replace).newPayload["message"]).isEqualTo("[masked]")
    }

    @Test
    fun `unregister removes the listener and decrements listenerCount`() {
        val interceptor = InMemoryPacketInterceptor()
        val sub = interceptor.register(PacketKind.MovementServerbound) { PacketDecision.Pass }
        assertThat(interceptor.listenerCount(PacketKind.MovementServerbound)).isEqualTo(1)

        interceptor.unregister(sub)

        assertThat(interceptor.listenerCount(PacketKind.MovementServerbound)).isZero()
        assertThat(sub.isActive).isFalse()
    }

    @Test
    fun `dispatch with no matching listener returns Pass`() {
        val interceptor = InMemoryPacketInterceptor()

        val decision = interceptor.dispatch(env(PacketKind.EntityDestroy))

        assertThat(decision).isEqualTo(PacketDecision.Pass)
    }
}
