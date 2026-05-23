package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ItemInstanceRefinedTest {

    @Test
    fun `rejects blank layerKind`() {
        assertThatThrownBy {
            ItemInstanceRefined(
                instanceUuid = UUID.randomUUID(),
                itemId = ItemId("example"),
                owner = PlayerId(UUID.randomUUID()),
                layerKind = "  ",
                totalLayers = 1,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects totalLayers less than 1`() {
        assertThatThrownBy {
            ItemInstanceRefined(
                instanceUuid = UUID.randomUUID(),
                itemId = ItemId("example"),
                owner = PlayerId(UUID.randomUUID()),
                layerKind = "star",
                totalLayers = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
