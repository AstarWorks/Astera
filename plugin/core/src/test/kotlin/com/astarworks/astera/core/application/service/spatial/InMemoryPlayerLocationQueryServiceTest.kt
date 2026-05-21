package com.astarworks.astera.core.application.service.spatial

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPlayerLocationQueryServiceTest {

    private val arena = WorldId("arena")
    private val lobby = WorldId("lobby")
    private val alice = PlayerId(UUID.randomUUID())
    private val bob = PlayerId(UUID.randomUUID())
    private val charlie = PlayerId(UUID.randomUUID())

    @Test
    fun `empty service returns empty queries`() {
        val svc = InMemoryPlayerLocationQueryService()
        assertThat(svc.playersIn(arena, Region(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 100.0, 100.0)))).isEmpty()
        assertThat(svc.playersWithinDistance(arena, Vec3.ZERO, 100.0)).isEmpty()
        assertThat(svc.nearestPlayer(arena, Vec3.ZERO, 100.0)).isNull()
    }

    @Test
    fun `updatePlayerPosition then playersIn finds the player`() {
        val svc = InMemoryPlayerLocationQueryService()
        svc.updatePlayerPosition(alice, arena, Vec3(5.0, 5.0, 5.0))
        svc.updatePlayerPosition(bob, arena, Vec3(50.0, 5.0, 5.0))

        val region = Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0))
        assertThat(svc.playersIn(arena, region)).containsExactly(alice)
    }

    @Test
    fun `playersIn is world-scoped`() {
        val svc = InMemoryPlayerLocationQueryService()
        svc.updatePlayerPosition(alice, arena, Vec3(5.0, 5.0, 5.0))
        svc.updatePlayerPosition(bob, lobby, Vec3(5.0, 5.0, 5.0))

        val region = Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0))
        assertThat(svc.playersIn(arena, region)).containsExactly(alice)
        assertThat(svc.playersIn(lobby, region)).containsExactly(bob)
    }

    @Test
    fun `playersWithinDistance respects radius`() {
        val svc = InMemoryPlayerLocationQueryService()
        svc.updatePlayerPosition(alice, arena, Vec3(0.0, 0.0, 0.0))
        svc.updatePlayerPosition(bob, arena, Vec3(3.0, 0.0, 0.0))
        svc.updatePlayerPosition(charlie, arena, Vec3(10.0, 0.0, 0.0))

        assertThat(svc.playersWithinDistance(arena, Vec3.ZERO, 5.0))
            .containsExactlyInAnyOrder(alice, bob)
        assertThat(svc.playersWithinDistance(arena, Vec3.ZERO, 0.0))
            .containsExactly(alice)
    }

    @Test
    fun `nearestPlayer returns closest within max distance`() {
        val svc = InMemoryPlayerLocationQueryService()
        svc.updatePlayerPosition(alice, arena, Vec3(10.0, 0.0, 0.0))
        svc.updatePlayerPosition(bob, arena, Vec3(3.0, 0.0, 0.0))
        svc.updatePlayerPosition(charlie, arena, Vec3(50.0, 0.0, 0.0))

        assertThat(svc.nearestPlayer(arena, Vec3.ZERO, 100.0)).isEqualTo(bob)
    }

    @Test
    fun `nearestPlayer returns null when no one in range`() {
        val svc = InMemoryPlayerLocationQueryService()
        svc.updatePlayerPosition(alice, arena, Vec3(100.0, 0.0, 0.0))

        assertThat(svc.nearestPlayer(arena, Vec3.ZERO, 50.0)).isNull()
    }

    @Test
    fun `update overwrites previous position`() {
        val svc = InMemoryPlayerLocationQueryService()
        svc.updatePlayerPosition(alice, arena, Vec3(0.0, 0.0, 0.0))
        svc.updatePlayerPosition(alice, arena, Vec3(100.0, 0.0, 0.0))

        assertThat(svc.playersWithinDistance(arena, Vec3.ZERO, 5.0)).isEmpty()
        assertThat(svc.playersWithinDistance(arena, Vec3(100.0, 0.0, 0.0), 5.0)).containsExactly(alice)
    }

    @Test
    fun `forgetPlayer removes the player`() {
        val svc = InMemoryPlayerLocationQueryService()
        svc.updatePlayerPosition(alice, arena, Vec3.ZERO)
        svc.forgetPlayer(alice)

        assertThat(svc.playersWithinDistance(arena, Vec3.ZERO, 100.0)).isEmpty()
    }

    @Test
    fun `negative radius throws`() {
        val svc = InMemoryPlayerLocationQueryService()
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            svc.playersWithinDistance(arena, Vec3.ZERO, -1.0)
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            svc.nearestPlayer(arena, Vec3.ZERO, -1.0)
        }
    }
}
