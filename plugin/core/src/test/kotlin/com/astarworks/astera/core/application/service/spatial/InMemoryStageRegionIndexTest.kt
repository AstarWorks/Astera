package com.astarworks.astera.core.application.service.spatial

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.region.RegionId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryStageRegionIndexTest {

    private val arena = WorldId("arena")
    private val lobby = WorldId("lobby")
    private val safeZone = RegionId("safe-zone")
    private val killZone = RegionId("kill-zone")

    @Test
    fun `regionsAt empty for unregistered world`() {
        val ix = InMemoryStageRegionIndex()
        assertThat(ix.regionsAt(arena, Vec3.ZERO)).isEmpty()
    }

    @Test
    fun `register then regionsAt returns containing regions`() {
        val ix = InMemoryStageRegionIndex()
        ix.register(arena, safeZone, Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))

        assertThat(ix.regionsAt(arena, Vec3(5.0, 5.0, 5.0))).containsExactly(safeZone)
        assertThat(ix.regionsAt(arena, Vec3(50.0, 5.0, 5.0))).isEmpty()
    }

    @Test
    fun `regions are isolated per world`() {
        val ix = InMemoryStageRegionIndex()
        ix.register(arena, safeZone, Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))

        assertThat(ix.regionsAt(arena, Vec3(5.0, 5.0, 5.0))).containsExactly(safeZone)
        assertThat(ix.regionsAt(lobby, Vec3(5.0, 5.0, 5.0))).isEmpty()
    }

    @Test
    fun `registering same id in different world moves it`() {
        val ix = InMemoryStageRegionIndex()
        ix.register(arena, safeZone, Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))
        ix.register(lobby, safeZone, Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))

        assertThat(ix.regionsAt(arena, Vec3(5.0, 5.0, 5.0))).isEmpty()
        assertThat(ix.regionsAt(lobby, Vec3(5.0, 5.0, 5.0))).containsExactly(safeZone)
    }

    @Test
    fun `unregister removes and returns true`() {
        val ix = InMemoryStageRegionIndex()
        ix.register(arena, safeZone, Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))

        assertThat(ix.unregister(safeZone)).isTrue()
        assertThat(ix.regionsAt(arena, Vec3(5.0, 5.0, 5.0))).isEmpty()
        assertThat(ix.unregister(safeZone)).isFalse()
    }

    @Test
    fun `regionOf returns the AABB or null`() {
        val ix = InMemoryStageRegionIndex()
        val r = Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0))
        ix.register(arena, safeZone, r)

        assertThat(ix.regionOf(safeZone)).isEqualTo(r)
        assertThat(ix.regionOf(killZone)).isNull()
    }

    @Test
    fun `overlapping regions all returned`() {
        val ix = InMemoryStageRegionIndex()
        ix.register(arena, safeZone, Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))
        ix.register(arena, killZone, Region(Vec3(5.0, 5.0, 5.0), Vec3(15.0, 15.0, 15.0)))

        assertThat(ix.regionsAt(arena, Vec3(7.0, 7.0, 7.0)))
            .containsExactlyInAnyOrder(safeZone, killZone)
    }
}
