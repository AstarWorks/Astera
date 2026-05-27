package com.astarworks.astera.core.domain.portal

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

internal class PortalTest {

    @Test
    fun `PortalId enforces kebab-case`() {
        PortalId("hub-to-arena")
        PortalId("p1")
        assertThatThrownBy { PortalId("HUB") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PortalId("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PortalId("has spaces") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Portal data class constructs cleanly`() {
        val p = Portal(
            id = PortalId("hub-to-arena"),
            sourceWorld = WorldId("hub"),
            sourcePosition = Vec3(0.0, 64.0, 0.0),
            sourceShape = PortalShape.Block(Vec3(0.0, 64.0, 0.0)),
            targetWorld = WorldId("arena"),
            targetPosition = Vec3(100.0, 64.0, 100.0),
            activationRule = PortalActivationRule.WalkOn,
        )
        assertThat(p.activationRule).isEqualTo(PortalActivationRule.WalkOn)
    }

    @Test
    fun `activation rule predicates classify variants correctly`() {
        assertThat(PortalActivationRule.WalkOn.acceptsWalkOn()).isTrue
        assertThat(PortalActivationRule.WalkOn.acceptsRightClick()).isFalse

        assertThat(PortalActivationRule.RightClick.acceptsWalkOn()).isFalse
        assertThat(PortalActivationRule.RightClick.acceptsRightClick()).isTrue

        assertThat(PortalActivationRule.SneakAct.acceptsWalkOn()).isFalse
        assertThat(PortalActivationRule.SneakAct.acceptsRightClick()).isTrue

        assertThat(PortalActivationRule.Both.acceptsWalkOn()).isTrue
        assertThat(PortalActivationRule.Both.acceptsRightClick()).isTrue
    }

    @Test
    fun `Region shape carries domain Region`() {
        val region = Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0))
        val shape = PortalShape.Region(region)
        assertThat(shape.region).isEqualTo(region)
    }
}
