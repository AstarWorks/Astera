package com.astarworks.astera.core.domain.perviewer

import com.astarworks.astera.core.domain.event.BlockOverrideApplied
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class BlockOverrideAppliedInvariantTest {

    private val viewer = PlayerId(UUID.randomUUID())
    private val world = WorldId("lobby")
    private val pos = Vec3(0.0, 64.0, 0.0)

    @Test
    fun `requires exactly one of type or texture or hidden`() {
        // No axis at all
        assertThatThrownBy {
            BlockOverrideApplied(viewer, world, pos)
        }.isInstanceOf(IllegalArgumentException::class.java)

        // Two axes at once
        assertThatThrownBy {
            BlockOverrideApplied(
                viewer = viewer,
                world = world,
                position = pos,
                overrideType = BlockType("minecraft:stone"),
                overrideTexture = HudGlyphRef("highlight/x"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `accepts single axis variants`() {
        val ev1 = BlockOverrideApplied(viewer, world, pos, overrideType = BlockType("minecraft:stone"))
        val ev2 = BlockOverrideApplied(viewer, world, pos, overrideTexture = HudGlyphRef("highlight/x"))
        val ev3 = BlockOverrideApplied(viewer, world, pos, hidden = true)
        assertThat(ev1.overrideType).isNotNull
        assertThat(ev2.overrideTexture).isNotNull
        assertThat(ev3.hidden).isTrue
    }
}
