package com.astarworks.astera.core.domain.survival

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class IslandPlotTest {

    private val islandId = IslandId("island-1")
    private val owner = PlayerId(UUID.randomUUID())
    private val friend = PlayerId(UUID.randomUUID())
    private val stranger = PlayerId(UUID.randomUUID())
    private val region = Region(Vec3(0.0, 0.0, 0.0), Vec3(16.0, 64.0, 16.0))

    @Test
    fun `owner must be in buildAllowed`() {
        assertThatThrownBy {
            IslandPlot(
                id = PlotId.random(),
                islandId = islandId,
                ownerId = owner,
                regionBounds = region,
                buildAllowed = setOf(friend), // owner missing
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `canBuild reflects buildAllowed`() {
        val plot = IslandPlot(
            id = PlotId.random(),
            islandId = islandId,
            ownerId = owner,
            regionBounds = region,
            buildAllowed = setOf(owner, friend),
        )
        assertThat(plot.canBuild(owner)).isTrue
        assertThat(plot.canBuild(friend)).isTrue
        assertThat(plot.canBuild(stranger)).isFalse
    }

    @Test
    fun `grantBuild adds to allow-list idempotently`() {
        val plot = IslandPlot(
            id = PlotId.random(),
            islandId = islandId,
            ownerId = owner,
            regionBounds = region,
            buildAllowed = setOf(owner),
        )
        val granted = plot.grantBuild(friend)
        assertThat(granted.canBuild(friend)).isTrue
        // Re-grant — no-op.
        val regranted = granted.grantBuild(friend)
        assertThat(regranted.buildAllowed).hasSize(2)
    }

    @Test
    fun `revokeBuild removes from allow-list`() {
        val plot = IslandPlot(
            id = PlotId.random(),
            islandId = islandId,
            ownerId = owner,
            regionBounds = region,
            buildAllowed = setOf(owner, friend),
        )
        val revoked = plot.revokeBuild(friend)
        assertThat(revoked.canBuild(friend)).isFalse
    }

    @Test
    fun `revokeBuild cannot remove owner`() {
        val plot = IslandPlot(
            id = PlotId.random(),
            islandId = islandId,
            ownerId = owner,
            regionBounds = region,
            buildAllowed = setOf(owner),
        )
        assertThatThrownBy { plot.revokeBuild(owner) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
