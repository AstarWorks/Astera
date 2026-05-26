package com.astarworks.astera.core.domain.perviewer

import com.astarworks.astera.core.domain.event.PerViewerOverridesCleared
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class PerViewerOverridesClearedInvariantTest {

    private val viewer = PlayerId(UUID.randomUUID())

    @Test
    fun `rejects unknown family`() {
        assertThatThrownBy {
            PerViewerOverridesCleared(
                viewer = viewer,
                gatewayFamily = "tooltip",
                reason = ClearReason.Explicit,
                clearedCount = 1,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects zero clearedCount`() {
        assertThatThrownBy {
            PerViewerOverridesCleared(
                viewer = viewer,
                gatewayFamily = PerViewerOverridesCleared.FAMILY_NPC,
                reason = ClearReason.Explicit,
                clearedCount = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `accepts each known family`() {
        PerViewerOverridesCleared.KNOWN_FAMILIES.forEach { family ->
            val ev = PerViewerOverridesCleared(
                viewer = viewer,
                gatewayFamily = family,
                reason = ClearReason.PlayerQuit,
                clearedCount = 1,
            )
            assertThat(ev.gatewayFamily).isEqualTo(family)
        }
    }
}
