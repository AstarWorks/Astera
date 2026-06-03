package com.astarworks.astera.core.adapter.paper.animation

import com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.player.PlayerAnimationError
import com.astarworks.astera.core.domain.animation.player.PlayerHand
import com.astarworks.astera.core.domain.animation.player.SitTarget
import com.astarworks.astera.core.domain.event.PlayerSneakChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.bukkit.Server
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * Smoke tests for [PaperPlayerAnimationGateway].
 *
 * Bukkit's `Server` + `Player` interfaces are too wide to hand-roll a full
 * fake, and `mockito` is not on the `:plugin:core` test classpath. The
 * tests below therefore target paths that don't need a real `Player`:
 *  - error-return on `InvalidDuration` (short-circuits before any Server
 *    call).
 *  - off-main return shape via a `java.lang.reflect.Proxy` `Server` whose
 *    `isPrimaryThread` returns `false`.
 *  - idempotency-tracking maps on a never-online player (UnknownPlayer
 *    branch) — calling `setSneaking(true)` twice still hits Server first,
 *    so the only observable side effect is the UnknownPlayer error and an
 *    empty event bus.
 *
 * A richer integration test (full mount / wake / sit cycle) lands once the
 * project picks up a Bukkit mocking lib (out of scope for Wave 6d).
 */
class PaperPlayerAnimationGatewayTest {

    private val bus = FakeDomainEventBus()
    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `implements IPlayerAnimationGateway`() {
        val gateway: IPlayerAnimationGateway = PaperPlayerAnimationGateway(
            server = primaryThreadStubServer(),
            eventBus = bus,
            tickClock = { 7L },
        )
        assertThat(gateway).isInstanceOf(IPlayerAnimationGateway::class.java)
    }

    @Test
    fun `playCustomAnimation rejects negative duration with InvalidDuration before touching Server`() {
        val gateway = PaperPlayerAnimationGateway(
            server = throwingStubServer(),  // any Server call would AssertionError out
            eventBus = bus,
            tickClock = { 0L },
        )

        val result = gateway.playCustomAnimation(
            playerId,
            CustomAnimationId("wave-emote"),
            durationTicks = -1L,
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(PlayerAnimationError.InvalidDuration::class.java)
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `off-main calls return Platform off-main without publishing events`() {
        val gateway = PaperPlayerAnimationGateway(
            server = stubServer(isPrimaryThread = false),
            eventBus = bus,
            tickClock = { 0L },
        )

        val result = gateway.swingArm(playerId, PlayerHand.MainHand)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(PlayerAnimationError.Platform::class.java)
        assertThat((error as PlayerAnimationError.Platform).detail).isEqualTo("off-main")
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `UnknownPlayer on never-online viewer for setSneaking does not toggle tracked state or publish`() {
        val gateway = PaperPlayerAnimationGateway(
            server = stubServer(isPrimaryThread = true),  // getPlayer returns null
            eventBus = bus,
            tickClock = { 0L },
        )

        val first = gateway.setSneaking(playerId, true)
        val second = gateway.setSneaking(playerId, true)

        assertThat(first).isInstanceOf(Result.Failure::class.java)
        assertThat((first as Result.Failure).error)
            .isInstanceOf(PlayerAnimationError.UnknownPlayer::class.java)
        assertThat(second).isInstanceOf(Result.Failure::class.java)
        // Tracked state never advanced (no online Player to mutate), so the
        // gateway saw no events on either call.
        assertThat(bus.publishedOfType<PlayerSneakChanged>()).isEmpty()
        assertThat(gateway.sneakState(playerId)).isNull()
    }

    @Test
    fun `setSitting None on never-tracked offline viewer surfaces UnknownPlayer`() {
        // The adapter guards `server.isPrimaryThread` before `server.getPlayer`,
        // so an offline never-tracked viewer surfaces UnknownPlayer (the
        // idempotency check does not short-circuit ahead of the player
        // resolution). Tracked state stays the InMemory-mirror default of
        // `SitTarget.None` (the absence of a registered transition is itself
        // `None`).
        val gateway = PaperPlayerAnimationGateway(
            server = stubServer(isPrimaryThread = true),
            eventBus = bus,
            tickClock = { 0L },
        )
        val result = gateway.setSitting(playerId, SitTarget.None)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error)
            .isInstanceOf(PlayerAnimationError.UnknownPlayer::class.java)
        assertThat(gateway.sitState(playerId)).isEqualTo(SitTarget.None)
    }

    // ------------------------------------------------------------------ stubs

    private fun primaryThreadStubServer(): Server = stubServer(isPrimaryThread = true)

    private fun stubServer(isPrimaryThread: Boolean): Server {
        // Java dynamic proxy: only `isPrimaryThread`, `getPlayer(UUID)`,
        // `getEntity(UUID)`, and `getWorld(String)` are called in the
        // tested paths. All other Server methods throw — none should be
        // hit on the negative paths exercised here.
        return Proxy.newProxyInstance(
            Server::class.java.classLoader,
            arrayOf(Server::class.java),
            ServerStubHandler(isPrimaryThread = isPrimaryThread),
        ) as Server
    }

    private fun throwingStubServer(): Server {
        return Proxy.newProxyInstance(
            Server::class.java.classLoader,
            arrayOf(Server::class.java),
            InvocationHandler { _, method, _ ->
                error("PaperPlayerAnimationGatewayTest: unexpected Server.${method.name} call")
            },
        ) as Server
    }

    private class ServerStubHandler(
        private val isPrimaryThread: Boolean,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "isPrimaryThread" -> isPrimaryThread
            // `getPlayer(UUID)`, `getEntity(UUID)`, `getWorld(String)` →
            // pretend "not online / not loaded". All three are nullable
            // return types in the Bukkit Server interface.
            "getPlayer", "getEntity", "getWorld" -> null
            // toString/hashCode/equals for proxy hygiene.
            "toString" -> "ServerStubHandler(isPrimaryThread=$isPrimaryThread)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === (args?.firstOrNull())
            else -> error("PaperPlayerAnimationGatewayTest: unexpected Server.${method.name} call")
        }
    }
}
