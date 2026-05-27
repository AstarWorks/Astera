package com.astarworks.astera.core.application.service.portal

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.portal.Portal
import com.astarworks.astera.core.domain.portal.PortalActivationRule
import com.astarworks.astera.core.domain.portal.PortalId
import com.astarworks.astera.core.domain.portal.PortalShape
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class InMemoryPortalGatewayTest {

    private val world = WorldId("hub")
    private val target = WorldId("arena")

    private fun block(id: String, at: Vec3) = Portal(
        id = PortalId(id),
        sourceWorld = world,
        sourcePosition = at,
        sourceShape = PortalShape.Block(at),
        targetWorld = target,
        targetPosition = Vec3(100.0, 64.0, 100.0),
        activationRule = PortalActivationRule.WalkOn,
    )

    private fun region(id: String, region: Region) = Portal(
        id = PortalId(id),
        sourceWorld = world,
        sourcePosition = region.min,
        sourceShape = PortalShape.Region(region),
        targetWorld = target,
        targetPosition = Vec3(100.0, 64.0, 100.0),
        activationRule = PortalActivationRule.Both,
    )

    @Test
    fun `register and findByPosition resolves block portal`() {
        val gw = InMemoryPortalGateway()
        val p = block("a", Vec3(0.0, 64.0, 0.0))
        assertThat(gw.register(p)).isNull()

        assertThat(gw.findByPosition(world, Vec3(0.0, 64.0, 0.0))).isEqualTo(p)
        assertThat(gw.findByPosition(world, Vec3(1.0, 64.0, 0.0))).isNull()
    }

    @Test
    fun `register on same id replaces and returns prior`() {
        val gw = InMemoryPortalGateway()
        val a = block("a", Vec3(0.0, 64.0, 0.0))
        val a2 = a.copy(sourcePosition = Vec3(5.0, 64.0, 0.0), sourceShape = PortalShape.Block(Vec3(5.0, 64.0, 0.0)))
        gw.register(a)
        assertThat(gw.register(a2)).isEqualTo(a)
        // Old position no longer indexed.
        assertThat(gw.findByPosition(world, Vec3(0.0, 64.0, 0.0))).isNull()
        assertThat(gw.findByPosition(world, Vec3(5.0, 64.0, 0.0))).isEqualTo(a2)
    }

    @Test
    fun `unregister returns true once then false`() {
        val gw = InMemoryPortalGateway()
        val a = block("a", Vec3(0.0, 64.0, 0.0))
        gw.register(a)
        assertThat(gw.unregister(PortalId("a"))).isTrue
        assertThat(gw.unregister(PortalId("a"))).isFalse
        assertThat(gw.findByPosition(world, Vec3(0.0, 64.0, 0.0))).isNull()
    }

    @Test
    fun `region portal is found anywhere inside the AABB`() {
        val gw = InMemoryPortalGateway()
        val r = Region(Vec3(0.0, 60.0, 0.0), Vec3(10.0, 70.0, 10.0))
        val p = region("zone", r)
        gw.register(p)

        assertThat(gw.findByPosition(world, Vec3(5.0, 65.0, 5.0))).isEqualTo(p)
        assertThat(gw.findByPosition(world, Vec3(0.0, 60.0, 0.0))).isEqualTo(p)
        assertThat(gw.findByPosition(world, Vec3(11.0, 65.0, 5.0))).isNull()
    }

    @Test
    fun `findByPosition respects world isolation`() {
        val gw = InMemoryPortalGateway()
        gw.register(block("a", Vec3(0.0, 64.0, 0.0)))
        assertThat(gw.findByPosition(WorldId("other"), Vec3(0.0, 64.0, 0.0))).isNull()
    }

    @Test
    fun `all returns every registered portal`() {
        val gw = InMemoryPortalGateway()
        gw.register(block("a", Vec3(0.0, 64.0, 0.0)))
        gw.register(block("b", Vec3(1.0, 64.0, 0.0)))
        assertThat(gw.all()).hasSize(2)
    }
}
