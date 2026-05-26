package com.astarworks.astera.core.domain.inventory

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InventoryTransferTargetTest {

    @Test
    fun `BlockContainer captures world position and kind`() {
        val t = InventoryTransferTarget.BlockContainer(
            world = WorldId("arena-1"),
            position = Vec3(10.0, 64.0, -3.0),
            containerKind = "minecraft:chest",
        )
        assertThat(t.world.value).isEqualTo("arena-1")
        assertThat(t.containerKind).isEqualTo("minecraft:chest")
    }

    @Test
    fun `VirtualContainer rejects blank key`() {
        assertThatThrownBy { InventoryTransferTarget.VirtualContainer("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Dropped captures world and position`() {
        val t = InventoryTransferTarget.Dropped(WorldId("lobby"), Vec3.ZERO)
        assertThat(t.world.value).isEqualTo("lobby")
        assertThat(t.position).isEqualTo(Vec3.ZERO)
    }
}
