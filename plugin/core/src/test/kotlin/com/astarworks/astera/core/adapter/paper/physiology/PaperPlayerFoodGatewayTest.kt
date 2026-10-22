package com.astarworks.astera.core.adapter.paper.physiology

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * F2-2 (ADR-0194): vanilla hunger is neutralised. The food bar belongs to Astera
 * (energy mirror); the Bukkit events that would move it or heal from it are cancelled.
 * Bukkit `Player` / `Server` are dynamic proxies that only remember what is written.
 */
class PaperPlayerFoodGatewayTest {

    private val uuid = UUID.randomUUID()
    private val pid = PlayerId(uuid)

    private class PlayerState {
        var food = 20
        var saturation = 5.0f
        var exhaustion = 3.0f
    }

    private val state = PlayerState()

    private val player: Player = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
            "getUniqueId" -> uuid
            "getFoodLevel" -> state.food
            "setFoodLevel" -> { state.food = args[0] as Int; null }
            "getSaturation" -> state.saturation
            "setSaturation" -> { state.saturation = args[0] as Float; null }
            "getExhaustion" -> state.exhaustion
            "setExhaustion" -> { state.exhaustion = args[0] as Float; null }
            "hashCode" -> uuid.hashCode()
            "equals" -> false
            "toString" -> "FakePlayer"
            else -> defaultFor(method.returnType)
        }
    } as Player

    private val server: Server = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Server::class.java)) { _, method, args ->
        when (method.name) {
            "getPlayer" -> if (args[0] == uuid) player else null
            else -> defaultFor(method.returnType)
        }
    } as Server

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Long.TYPE -> 0L
        else -> null
    }

    private val gateway = PaperPlayerFoodGateway(server, FakeScheduler(), InMemoryDomainEventBus())

    @Test
    fun `mirrorEnergy writes the level and zeroes saturation and exhaustion`() {
        val outcome = gateway.mirrorEnergy(pid, 7)

        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        assertThat(state.food).isEqualTo(7)
        assertThat(state.saturation).isEqualTo(0.0f)
        assertThat(state.exhaustion).isEqualTo(0.0f)
    }

    @Test
    fun `mirrorEnergy rejects levels outside 0 to 20`() {
        assertThat(gateway.mirrorEnergy(pid, 21)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.mirrorEnergy(pid, -1)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `every vanilla food level change is cancelled and the mirrored level is put back`() {
        gateway.mirrorEnergy(pid, 12)
        state.food = 11 // as if vanilla had just applied an exhaustion tick

        val event = FoodLevelChangeEvent(player, 11)
        gateway.onFoodLevelChange(event)

        assertThat(event.isCancelled).isTrue()
        assertThat(state.food).isEqualTo(12)
        assertThat(state.saturation).isEqualTo(0.0f)
        assertThat(state.exhaustion).isEqualTo(0.0f)
    }

    @Test
    fun `eating cannot refill the bar either`() {
        gateway.mirrorEnergy(pid, 3)

        val event = FoodLevelChangeEvent(player, 20)
        gateway.onFoodLevelChange(event)

        assertThat(event.isCancelled).isTrue()
        assertThat(state.food).isEqualTo(3)
    }

    @Test
    fun `satiety and natural regeneration do not heal, other healing does`() {
        val satiated = EntityRegainHealthEvent(player, 1.0, EntityRegainHealthEvent.RegainReason.SATIATED)
        val regen = EntityRegainHealthEvent(player, 1.0, EntityRegainHealthEvent.RegainReason.REGEN)
        val potion = EntityRegainHealthEvent(player, 1.0, EntityRegainHealthEvent.RegainReason.MAGIC)

        gateway.onRegainHealth(satiated)
        gateway.onRegainHealth(regen)
        gateway.onRegainHealth(potion)

        assertThat(satiated.isCancelled).isTrue()
        assertThat(regen.isCancelled).isTrue()
        assertThat(potion.isCancelled).isFalse()
    }
}
