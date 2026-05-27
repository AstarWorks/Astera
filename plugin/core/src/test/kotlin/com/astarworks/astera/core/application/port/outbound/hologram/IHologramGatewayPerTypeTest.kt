package com.astarworks.astera.core.application.port.outbound.hologram

import com.astarworks.astera.core.application.port.outbound.HologramError
import com.astarworks.astera.core.application.service.InMemoryHologramGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.BillboardMode
import com.astarworks.astera.core.domain.hologram.Hologram
import com.astarworks.astera.core.domain.hologram.HologramColor
import com.astarworks.astera.core.domain.hologram.HologramId
import com.astarworks.astera.core.domain.hologram.HologramKind
import com.astarworks.astera.core.domain.hologram.ItemDisplayMode
import com.astarworks.astera.core.domain.hologram.TextAlignment
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IHologramGatewayPerTypeTest {

    private val worldId = WorldId("world")
    private val origin = Vec3(0.0, 64.0, 0.0)

    private fun spawnText(gateway: InMemoryHologramGateway) =
        (gateway.spawn(
            Hologram(
                id = HologramId.random(),
                worldId = worldId,
                position = origin,
                kind = HologramKind.Text(lines = listOf(MessageKey("astera.test.welcome"))),
            ),
        ) as Result.Success).value

    private fun spawnItem(gateway: InMemoryHologramGateway) =
        (gateway.spawn(
            Hologram(
                id = HologramId.random(),
                worldId = worldId,
                position = origin,
                kind = HologramKind.Item(itemId = ItemId("example-sword")),
            ),
        ) as Result.Success).value

    private fun spawnBlock(gateway: InMemoryHologramGateway) =
        (gateway.spawn(
            Hologram(
                id = HologramId.random(),
                worldId = worldId,
                position = origin,
                kind = HologramKind.Block(blockKey = "minecraft:stone"),
            ),
        ) as Result.Success).value

    // ─── Display base updaters (apply to every kind variant) ───

    @Test
    fun `updateShadowRadius applies to any kind variant`() {
        val gateway = InMemoryHologramGateway()
        val text = spawnText(gateway)
        val item = spawnItem(gateway)
        val block = spawnBlock(gateway)
        for (handle in listOf(text, item, block)) {
            assertThat(gateway.updateShadowRadius(handle, 2.5f).isSuccess).isTrue()
            assertThat(gateway.snapshot(handle)?.shadowRadius).isEqualTo(2.5f)
        }
    }

    @Test
    fun `updateShadowStrength applies to any kind variant`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        assertThat(gateway.updateShadowStrength(handle, 0.25f).isSuccess).isTrue()
        assertThat(gateway.snapshot(handle)?.shadowStrength).isEqualTo(0.25f)
    }

    @Test
    fun `updateViewRange applies to any kind variant`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        assertThat(gateway.updateViewRange(handle, 128f).isSuccess).isTrue()
        assertThat(gateway.snapshot(handle)?.viewRangeBlocks).isEqualTo(128f)
    }

    @Test
    fun `updateBillboard applies to any kind variant`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        assertThat(gateway.updateBillboard(handle, BillboardMode.CENTER).isSuccess).isTrue()
        assertThat(gateway.snapshot(handle)?.billboard).isEqualTo(BillboardMode.CENTER)
    }

    @Test
    fun `updateDisplayDimensions sets width and height atomically`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        assertThat(gateway.updateDisplayDimensions(handle, width = 3.5f, height = 1.5f).isSuccess).isTrue()
        val snap = gateway.snapshot(handle)
        assertThat(snap?.displayWidth).isEqualTo(3.5f)
        assertThat(snap?.displayHeight).isEqualTo(1.5f)
    }

    // ─── Text-specific updaters + TypeMismatch on non-Text ───

    @Test
    fun `updateTextContent on Text hologram replaces lines`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        val newLines = listOf(MessageKey("astera.test.line.a"), MessageKey("astera.test.line.b"))
        assertThat(gateway.updateTextContent(handle, newLines).isSuccess).isTrue()
        val kind = gateway.snapshot(handle)?.kind as HologramKind.Text
        assertThat(kind.lines).isEqualTo(newLines)
    }

    @Test
    fun `updateTextContent on Item hologram fails with TypeMismatch`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnItem(gateway)
        val result = gateway.updateTextContent(handle, listOf(MessageKey("astera.x")))
        assertThat(result.isFailure).isTrue()
        val err = (result as Result.Failure).error
        assertThat(err).isInstanceOf(HologramError.TypeMismatch::class.java)
        val mismatch = err as HologramError.TypeMismatch
        assertThat(mismatch.expected).isEqualTo(HologramKind.Text::class)
        assertThat(mismatch.actual).isEqualTo(HologramKind.Item::class)
    }

    @Test
    fun `updateTextLineWidth on Text hologram updates lineWidth`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        assertThat(gateway.updateTextLineWidth(handle, 50).isSuccess).isTrue()
        val kind = gateway.snapshot(handle)?.kind as HologramKind.Text
        assertThat(kind.lineWidth).isEqualTo(50)
    }

    @Test
    fun `updateTextLineWidth on Block hologram fails with TypeMismatch`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnBlock(gateway)
        val result = gateway.updateTextLineWidth(handle, 50)
        assertThat(result.isFailure).isTrue()
        assertThat((result as Result.Failure).error).isInstanceOf(HologramError.TypeMismatch::class.java)
    }

    @Test
    fun `updateTextBackgroundColor on Text accepts null to clear`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        val color = HologramColor(red = 10, green = 20, blue = 30, alpha = 40)
        gateway.updateTextBackgroundColor(handle, color)
        val k1 = gateway.snapshot(handle)?.kind as HologramKind.Text
        assertThat(k1.backgroundColor).isEqualTo(color)
        gateway.updateTextBackgroundColor(handle, null)
        val k2 = gateway.snapshot(handle)?.kind as HologramKind.Text
        assertThat(k2.backgroundColor).isNull()
    }

    @Test
    fun `updateTextOpacity on Text updates the byte`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        gateway.updateTextOpacity(handle, 64.toByte())
        val kind = gateway.snapshot(handle)?.kind as HologramKind.Text
        assertThat(kind.textOpacity).isEqualTo(64.toByte())
    }

    @Test
    fun `updateTextAlignment on Text updates the enum`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        gateway.updateTextAlignment(handle, TextAlignment.LEFT)
        val kind = gateway.snapshot(handle)?.kind as HologramKind.Text
        assertThat(kind.alignment).isEqualTo(TextAlignment.LEFT)
    }

    @Test
    fun `updateTextSeeThrough on Text updates the boolean`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        gateway.updateTextSeeThrough(handle, true)
        val kind = gateway.snapshot(handle)?.kind as HologramKind.Text
        assertThat(kind.seeThrough).isTrue()
    }

    @Test
    fun `updateTextShadowed on Text updates the boolean`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        gateway.updateTextShadowed(handle, true)
        val kind = gateway.snapshot(handle)?.kind as HologramKind.Text
        assertThat(kind.shadowed).isTrue()
    }

    @Test
    fun `updateTextDefaultBackground on Text updates the boolean`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        gateway.updateTextDefaultBackground(handle, true)
        val kind = gateway.snapshot(handle)?.kind as HologramKind.Text
        assertThat(kind.defaultBackground).isTrue()
    }

    // ─── Item-specific updaters + TypeMismatch on non-Item ───

    @Test
    fun `updateItemId on Item hologram changes the itemId`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnItem(gateway)
        gateway.updateItemId(handle, ItemId("lightning-blade"))
        val kind = gateway.snapshot(handle)?.kind as HologramKind.Item
        assertThat(kind.itemId).isEqualTo(ItemId("lightning-blade"))
    }

    @Test
    fun `updateItemId on Text hologram fails with TypeMismatch`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnText(gateway)
        val result = gateway.updateItemId(handle, ItemId("example-sword"))
        assertThat(result.isFailure).isTrue()
        val err = (result as Result.Failure).error
        assertThat(err).isInstanceOf(HologramError.TypeMismatch::class.java)
        val mismatch = err as HologramError.TypeMismatch
        assertThat(mismatch.expected).isEqualTo(HologramKind.Item::class)
        assertThat(mismatch.actual).isEqualTo(HologramKind.Text::class)
    }

    @Test
    fun `updateItemDisplayMode on Item changes the enum`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnItem(gateway)
        gateway.updateItemDisplayMode(handle, ItemDisplayMode.HEAD)
        val kind = gateway.snapshot(handle)?.kind as HologramKind.Item
        assertThat(kind.displayMode).isEqualTo(ItemDisplayMode.HEAD)
    }

    @Test
    fun `updateItemDisplayMode on Block hologram fails with TypeMismatch`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnBlock(gateway)
        val result = gateway.updateItemDisplayMode(handle, ItemDisplayMode.HEAD)
        assertThat(result.isFailure).isTrue()
        assertThat((result as Result.Failure).error).isInstanceOf(HologramError.TypeMismatch::class.java)
    }

    // ─── Block-specific updater + TypeMismatch on non-Block ───

    @Test
    fun `updateBlockData on Block hologram changes the blockKey`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnBlock(gateway)
        gateway.updateBlockData(handle, "minecraft:gold_block")
        val kind = gateway.snapshot(handle)?.kind as HologramKind.Block
        assertThat(kind.blockKey).isEqualTo("minecraft:gold_block")
    }

    @Test
    fun `updateBlockData on Item hologram fails with TypeMismatch`() {
        val gateway = InMemoryHologramGateway()
        val handle = spawnItem(gateway)
        val result = gateway.updateBlockData(handle, "minecraft:stone")
        assertThat(result.isFailure).isTrue()
        val err = (result as Result.Failure).error
        assertThat(err).isInstanceOf(HologramError.TypeMismatch::class.java)
        val mismatch = err as HologramError.TypeMismatch
        assertThat(mismatch.expected).isEqualTo(HologramKind.Block::class)
        assertThat(mismatch.actual).isEqualTo(HologramKind.Item::class)
    }

    // ─── Unknown handle on any updater ───

    @Test
    fun `every per-type updater returns UnknownHandle for an unknown handle`() {
        val gateway = InMemoryHologramGateway()
        val unknown = com.astarworks.astera.core.domain.hologram.HologramHandle("never-spawned")
        assertThat((gateway.updateShadowRadius(unknown, 1f) as Result.Failure).error)
            .isInstanceOf(HologramError.UnknownHandle::class.java)
        assertThat((gateway.updateTextContent(unknown, emptyList()) as Result.Failure).error)
            .isInstanceOf(HologramError.UnknownHandle::class.java)
        assertThat((gateway.updateItemId(unknown, ItemId("example-sword")) as Result.Failure).error)
            .isInstanceOf(HologramError.UnknownHandle::class.java)
        assertThat((gateway.updateBlockData(unknown, "minecraft:stone") as Result.Failure).error)
            .isInstanceOf(HologramError.UnknownHandle::class.java)
    }
}
