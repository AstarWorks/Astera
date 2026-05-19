package com.astarworks.astera.core.domain.hologram

import com.astarworks.astera.core.application.port.outbound.HologramError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeHologramGateway
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.joml.Quaternionf
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.PI

class HologramTest {

    private val worldId = WorldId("world")
    private val origin = Vec3(0.0, 64.0, 0.0)

    @Test
    fun `spawn Text hologram then updateTransform then despawn round-trip`() {
        val gateway = FakeHologramGateway()
        val text = HologramKind.Text(
            lines = listOf(MessageKey("astera.tutorial.welcome")),
        )
        val hologram = Hologram(
            id = HologramId.random(),
            worldId = worldId,
            position = origin,
            kind = text,
        )

        val spawnResult = gateway.spawn(hologram)
        assertThat(spawnResult.isSuccess).isTrue()
        val handle = (spawnResult as Result.Success).value
        assertThat(gateway.current(handle)).isEqualTo(hologram)

        // Rotate 90° around X axis via JOML Quaternionf (Wave 5h-fix: use
        // org.joml directly rather than reimplementing Quaternion math).
        val rot = Quaternionf().fromAxisAngleRad(1f, 0f, 0f, (PI / 2).toFloat())
        val newTransform = HologramTransform(leftRotation = rot)
        val updateResult = gateway.updateTransform(handle, newTransform)
        assertThat(updateResult.isSuccess).isTrue()
        assertThat(gateway.current(handle)?.transform?.leftRotation).isEqualTo(rot)

        val despawnResult = gateway.despawn(handle)
        assertThat(despawnResult.isSuccess).isTrue()
        assertThat(gateway.current(handle)).isNull()
        assertThat(gateway.all()).doesNotContain(handle)
    }

    @Test
    fun `spawn Item hologram then updateKind swaps the itemId`() {
        val gateway = FakeHologramGateway()
        val initial = HologramKind.Item(itemId = ItemId("example-sword"))
        val hologram = Hologram(
            id = HologramId.random(),
            worldId = worldId,
            position = origin,
            kind = initial,
        )
        val handle = (gateway.spawn(hologram) as Result.Success).value

        val replacement = HologramKind.Item(itemId = ItemId("lightning-blade"))
        val result = gateway.updateKind(handle, replacement)
        assertThat(result.isSuccess).isTrue()

        val live = gateway.current(handle)
        assertThat(live).isNotNull
        assertThat(live!!.kind).isEqualTo(replacement)
        // Position and transform are preserved across kind swap
        assertThat(live.position).isEqualTo(origin)
        assertThat(live.transform).isEqualTo(HologramTransform())
    }

    @Test
    fun `spawn Block hologram then teleport updates position and optional world`() {
        val gateway = FakeHologramGateway()
        val block = HologramKind.Block(blockKey = "minecraft:diamond_block")
        val hologram = Hologram(
            id = HologramId.random(),
            worldId = worldId,
            position = origin,
            kind = block,
        )
        val handle = (gateway.spawn(hologram) as Result.Success).value

        val newPos = Vec3(100.0, 70.0, -50.0)
        val newWorld = WorldId("nether")
        val result = gateway.teleport(handle, newPos, newWorld)
        assertThat(result.isSuccess).isTrue()

        val live = gateway.current(handle)!!
        assertThat(live.position).isEqualTo(newPos)
        assertThat(live.worldId).isEqualTo(newWorld)
    }

    @Test
    fun `PerPlayer visibility scope round-trips through spawn`() {
        val gateway = FakeHologramGateway()
        val alice = PlayerId(UUID.randomUUID())
        val bob = PlayerId(UUID.randomUUID())
        val hologram = Hologram(
            id = HologramId.random(),
            worldId = worldId,
            position = origin,
            kind = HologramKind.Text(lines = listOf(MessageKey("astera.system.welcome"))),
            visibilityScope = HologramVisibilityScope.PerPlayer(setOf(alice, bob)),
        )

        val handle = (gateway.spawn(hologram) as Result.Success).value
        val live = gateway.current(handle)!!

        val scope = live.visibilityScope
        assertThat(scope).isInstanceOf(HologramVisibilityScope.PerPlayer::class.java)
        assertThat((scope as HologramVisibilityScope.PerPlayer).playerIds).containsExactlyInAnyOrder(alice, bob)
    }

    @Test
    fun `PerTeam visibility scope preserves matchId and teamId`() {
        val gateway = FakeHologramGateway()
        val matchId = MatchId("match-1")
        val teamId = TeamId("red")
        val hologram = Hologram(
            id = HologramId.random(),
            worldId = worldId,
            position = origin,
            kind = HologramKind.Text(lines = listOf(MessageKey("astera.system.welcome"))),
            visibilityScope = HologramVisibilityScope.PerTeam(matchId, teamId),
        )

        val handle = (gateway.spawn(hologram) as Result.Success).value
        val scope = gateway.current(handle)!!.visibilityScope
        assertThat(scope).isInstanceOf(HologramVisibilityScope.PerTeam::class.java)
        scope as HologramVisibilityScope.PerTeam
        assertThat(scope.matchId).isEqualTo(matchId)
        assertThat(scope.teamId).isEqualTo(teamId)
    }

    @Test
    fun `updateTransform on unknown handle returns UnknownHandle failure`() {
        val gateway = FakeHologramGateway()
        val unknown = HologramHandle("not-spawned")
        val result = gateway.updateTransform(unknown, HologramTransform())
        assertThat(result.isFailure).isTrue()
        assertThat((result as Result.Failure).error).isInstanceOf(HologramError.UnknownHandle::class.java)
    }

    @Test
    fun `despawn of unknown handle returns Success per idempotent contract`() {
        val gateway = FakeHologramGateway()
        val unknown = HologramHandle("never-existed")
        val result = gateway.despawn(unknown)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `operations history records every gateway call in order`() {
        val gateway = FakeHologramGateway()
        val hologram = Hologram(
            id = HologramId.random(),
            worldId = worldId,
            position = origin,
            kind = HologramKind.Text(lines = listOf(MessageKey("astera.system.welcome"))),
        )
        val handle = (gateway.spawn(hologram) as Result.Success).value
        gateway.updateTransform(handle, HologramTransform(translation = Vec3(1.0, 0.0, 0.0)))
        gateway.teleport(handle, Vec3(5.0, 5.0, 5.0), null)
        gateway.despawn(handle)

        val ops = gateway.operations()
        assertThat(ops).hasSize(4)
        assertThat(ops[0]).isInstanceOf(FakeHologramGateway.HologramOp.Spawned::class.java)
        assertThat(ops[1]).isInstanceOf(FakeHologramGateway.HologramOp.TransformUpdated::class.java)
        assertThat(ops[2]).isInstanceOf(FakeHologramGateway.HologramOp.Teleported::class.java)
        assertThat(ops[3]).isInstanceOf(FakeHologramGateway.HologramOp.Despawned::class.java)
    }

    @Test
    fun `blank blockKey rejected at HologramKind Block construction`() {
        assertThatThrownBy { HologramKind.Block(blockKey = "") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("blockKey")
    }

    @Test
    fun `zero lineWidth rejected at HologramKind Text construction`() {
        assertThatThrownBy {
            HologramKind.Text(
                lines = listOf(MessageKey("astera.test")),
                lineWidth = 0,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("lineWidth")
    }

    @Test
    fun `out-of-range blockLight rejected at HologramBrightness construction`() {
        assertThatThrownBy { HologramBrightness(blockLight = 16, skyLight = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("blockLight")
    }

    @Test
    fun `negative durationTicks rejected at HologramInterpolation construction`() {
        assertThatThrownBy { HologramInterpolation(durationTicks = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("durationTicks")
    }

    // ─── Wave 5h-fix2: dynamic update + viewer query ────────────────────────

    @Test
    fun `updateVisibility swaps scope from PerPlayer to Everyone`() {
        val gateway = FakeHologramGateway()
        val alice = PlayerId(UUID.randomUUID())
        val handle = (
            gateway.spawn(
                Hologram(
                    id = HologramId.random(),
                    worldId = worldId,
                    position = origin,
                    kind = HologramKind.Text(listOf(MessageKey("astera.test"))),
                    visibilityScope = HologramVisibilityScope.PerPlayer(setOf(alice)),
                ),
            ) as Result.Success
            ).value

        val r = gateway.updateVisibility(handle, HologramVisibilityScope.Everyone)
        assertThat(r.isSuccess).isTrue()
        assertThat(gateway.current(handle)!!.visibilityScope).isEqualTo(HologramVisibilityScope.Everyone)
    }

    @Test
    fun `updateBrightness null clears prior brightness override`() {
        val gateway = FakeHologramGateway()
        val handle = (
            gateway.spawn(
                Hologram(
                    id = HologramId.random(),
                    worldId = worldId,
                    position = origin,
                    kind = HologramKind.Text(listOf(MessageKey("astera.test"))),
                    brightness = HologramBrightness(blockLight = 10, skyLight = 5),
                ),
            ) as Result.Success
            ).value

        gateway.updateBrightness(handle, null)
        assertThat(gateway.current(handle)!!.brightness).isNull()
    }

    @Test
    fun `updateInterpolation reflects new durationTicks`() {
        val gateway = FakeHologramGateway()
        val handle = (
            gateway.spawn(
                Hologram(
                    id = HologramId.random(),
                    worldId = worldId,
                    position = origin,
                    kind = HologramKind.Text(listOf(MessageKey("astera.test"))),
                ),
            ) as Result.Success
            ).value

        gateway.updateInterpolation(handle, HologramInterpolation(durationTicks = 40, delayTicks = 5))
        val current = gateway.current(handle)!!
        assertThat(current.interpolation.durationTicks).isEqualTo(40)
        assertThat(current.interpolation.delayTicks).isEqualTo(5)
    }

    @Test
    fun `updateGlowColor applies and clears override`() {
        val gateway = FakeHologramGateway()
        val handle = (
            gateway.spawn(
                Hologram(
                    id = HologramId.random(),
                    worldId = worldId,
                    position = origin,
                    kind = HologramKind.Text(listOf(MessageKey("astera.test"))),
                ),
            ) as Result.Success
            ).value

        gateway.updateGlowColor(handle, HologramColor(red = 255.toByte(), green = 0, blue = 0))
        assertThat(gateway.current(handle)!!.glowColor).isNotNull
        gateway.updateGlowColor(handle, null)
        assertThat(gateway.current(handle)!!.glowColor).isNull()
    }

    @Test
    fun `viewers Everyone returns onlinePlayersProvider snapshot`() {
        val alice = PlayerId(UUID.randomUUID())
        val bob = PlayerId(UUID.randomUUID())
        val gateway = FakeHologramGateway(
            onlinePlayersProvider = { setOf(alice, bob) },
        )
        val handle = (
            gateway.spawn(
                Hologram(
                    id = HologramId.random(),
                    worldId = worldId,
                    position = origin,
                    kind = HologramKind.Text(listOf(MessageKey("astera.test"))),
                    visibilityScope = HologramVisibilityScope.Everyone,
                ),
            ) as Result.Success
            ).value

        assertThat(gateway.viewers(handle)).containsExactlyInAnyOrder(alice, bob)
    }

    @Test
    fun `viewers PerPlayer intersects with online set`() {
        val alice = PlayerId(UUID.randomUUID())
        val bob = PlayerId(UUID.randomUUID())
        val charlie = PlayerId(UUID.randomUUID())
        val gateway = FakeHologramGateway(
            onlinePlayersProvider = { setOf(alice, charlie) },
        )
        val handle = (
            gateway.spawn(
                Hologram(
                    id = HologramId.random(),
                    worldId = worldId,
                    position = origin,
                    kind = HologramKind.Text(listOf(MessageKey("astera.test"))),
                    visibilityScope = HologramVisibilityScope.PerPlayer(setOf(alice, bob)),
                ),
            ) as Result.Success
            ).value

        // alice is in both scope + online → visible. bob is in scope but offline → filtered.
        assertThat(gateway.viewers(handle)).containsExactly(alice)
    }

    @Test
    fun `viewers on UnknownHandle returns empty list`() {
        val gateway = FakeHologramGateway()
        assertThat(gateway.viewers(HologramHandle("never-spawned"))).isEmpty()
    }
}
