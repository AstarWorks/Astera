package com.astarworks.astera.core.application.service.region

import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.region.RegionId
import com.astarworks.astera.core.domain.region.RegionPermission
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryRegionPermissionServiceTest {

    private val arena = WorldId("arena")
    private val safeZone = RegionId("safe-zone")
    private val outsideRegion = RegionId("outside")
    private val inside = Vec3(5.0, 64.0, 5.0)
    private val outside = Vec3(500.0, 64.0, 500.0)
    private val alice = PlayerId(UUID.randomUUID())
    private val bob = PlayerId(UUID.randomUUID())

    private fun newIndex(): IStageRegionIndex {
        val index = SimpleIndex()
        index.register(arena, safeZone, Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 128.0, 10.0)))
        return index
    }

    @Test
    fun `default-allow when no rule matches`() {
        val svc = InMemoryRegionPermissionService(newIndex())
        assertThat(svc.check(alice, inside, arena, RegionPermission.Build)).isTrue()
        assertThat(svc.check(alice, outside, arena, RegionPermission.Build)).isTrue()
    }

    @Test
    fun `deny everyone blocks every player inside the region`() {
        val svc = InMemoryRegionPermissionService(newIndex())
        svc.deny(safeZone, RegionPermission.Build)

        assertThat(svc.check(alice, inside, arena, RegionPermission.Build)).isFalse()
        assertThat(svc.check(bob, inside, arena, RegionPermission.Build)).isFalse()
    }

    @Test
    fun `deny does not apply outside the region`() {
        val svc = InMemoryRegionPermissionService(newIndex())
        svc.deny(safeZone, RegionPermission.Build)

        assertThat(svc.check(alice, outside, arena, RegionPermission.Build)).isTrue()
    }

    @Test
    fun `player-specific allow overrides everyone-deny`() {
        val svc = InMemoryRegionPermissionService(newIndex())
        svc.deny(safeZone, RegionPermission.Build)
        svc.allow(safeZone, RegionPermission.Build, players = setOf(alice))

        assertThat(svc.check(alice, inside, arena, RegionPermission.Build)).isTrue()
        assertThat(svc.check(bob, inside, arena, RegionPermission.Build)).isFalse()
    }

    @Test
    fun `permission key applies independently`() {
        val svc = InMemoryRegionPermissionService(newIndex())
        svc.deny(safeZone, RegionPermission.Build)

        // Build denied; Break still allowed (no rule).
        assertThat(svc.check(alice, inside, arena, RegionPermission.Build)).isFalse()
        assertThat(svc.check(alice, inside, arena, RegionPermission.Break)).isTrue()
    }

    @Test
    fun `re-adding same rule is idempotent`() {
        val svc = InMemoryRegionPermissionService(newIndex())
        svc.deny(safeZone, RegionPermission.Build)
        svc.deny(safeZone, RegionPermission.Build)
        svc.deny(safeZone, RegionPermission.Build)

        assertThat(svc.rules(safeZone)).hasSize(1)
        assertThat(svc.check(alice, inside, arena, RegionPermission.Build)).isFalse()
    }

    @Test
    fun `allow then deny replaces the rule`() {
        val svc = InMemoryRegionPermissionService(newIndex())
        svc.allow(safeZone, RegionPermission.Build)
        assertThat(svc.check(alice, inside, arena, RegionPermission.Build)).isTrue()

        svc.deny(safeZone, RegionPermission.Build)
        assertThat(svc.rules(safeZone)).hasSize(1)
        assertThat(svc.check(alice, inside, arena, RegionPermission.Build)).isFalse()
    }

    @Test
    fun `clear removes everyone-rule and returns true`() {
        val svc = InMemoryRegionPermissionService(newIndex())
        svc.deny(safeZone, RegionPermission.Build)

        assertThat(svc.clear(safeZone, RegionPermission.Build)).isTrue()
        assertThat(svc.rules(safeZone)).isEmpty()
        assertThat(svc.check(alice, inside, arena, RegionPermission.Build)).isTrue()
    }

    @Test
    fun `clear with non-matching rule returns false`() {
        val svc = InMemoryRegionPermissionService(newIndex())
        svc.deny(safeZone, RegionPermission.Build)
        assertThat(svc.clear(safeZone, RegionPermission.Break)).isFalse()
    }

    @Test
    fun `rules returns empty for unknown region`() {
        val svc = InMemoryRegionPermissionService(newIndex())
        assertThat(svc.rules(outsideRegion)).isEmpty()
    }

    @Test
    fun `Use permission with ItemId is treated distinctly per item`() {
        val svc = InMemoryRegionPermissionService(newIndex())
        val tnt = com.astarworks.astera.core.domain.item.ItemId("tnt")
        val key = com.astarworks.astera.core.domain.item.ItemId("key")
        svc.deny(safeZone, RegionPermission.Use(tnt))

        assertThat(svc.check(alice, inside, arena, RegionPermission.Use(tnt))).isFalse()
        assertThat(svc.check(alice, inside, arena, RegionPermission.Use(key))).isTrue()
    }

    /** Minimal index for tests, separate from the FakeStageRegionIndex in test-fixtures. */
    private class SimpleIndex : IStageRegionIndex {
        private val entries: MutableMap<RegionId, Pair<WorldId, Region>> = mutableMapOf()

        override fun regionsAt(world: WorldId, position: Vec3): Collection<RegionId> =
            entries.filterValues { (w, r) -> w == world && r.contains(position) }.keys.toList()

        override fun regionOf(regionId: RegionId): Region? = entries[regionId]?.second

        override fun register(world: WorldId, regionId: RegionId, region: Region) {
            entries[regionId] = world to region
        }

        override fun unregister(regionId: RegionId): Boolean = entries.remove(regionId) != null
    }
}
