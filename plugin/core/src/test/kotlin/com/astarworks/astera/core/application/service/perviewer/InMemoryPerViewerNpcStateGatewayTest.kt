package com.astarworks.astera.core.application.service.perviewer

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcSkin
import com.astarworks.astera.core.domain.event.NpcOverrideApplied
import com.astarworks.astera.core.domain.event.PerViewerOverridesCleared
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.perviewer.ClearReason
import com.astarworks.astera.core.domain.perviewer.NpcAttributeKind
import com.astarworks.astera.core.domain.perviewer.PerViewerOverrideError
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPerViewerNpcStateGatewayTest {

    private val viewer = PlayerId(UUID.randomUUID())
    private val npc = EntityHandle(id = UUID.randomUUID(), backendId = "citizens")
    private val skin = NpcSkin(playerName = "Notch")

    @Test
    fun `overrideSkin rejects unknown viewer`() {
        val gw = InMemoryPerViewerNpcStateGateway()
        gw.trackEntity(npc)

        val result = gw.overrideSkin(viewer, npc, skin)

        assertThat(result).isEqualTo(Result.failure(PerViewerOverrideError.UnknownViewer))
    }

    @Test
    fun `overrideSkin rejects unknown npc`() {
        val gw = InMemoryPerViewerNpcStateGateway()
        gw.trackViewer(viewer)

        val result = gw.overrideSkin(viewer, npc, skin)

        assertThat(result).isEqualTo(Result.failure(PerViewerOverrideError.UnknownTarget))
    }

    @Test
    fun `overrideSkin publishes NpcOverrideApplied the first time then is idempotent`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerNpcStateGateway(eventBus = bus)
        gw.trackViewer(viewer)
        gw.trackEntity(npc)

        gw.overrideSkin(viewer, npc, skin)
        gw.overrideSkin(viewer, npc, skin)

        val events = bus.publishedOfType<NpcOverrideApplied>()
        assertThat(events).hasSize(1)
        assertThat(events[0].snapshot.skin).isEqualTo(skin)
    }

    @Test
    fun `overrideName replaces previous name override`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerNpcStateGateway(eventBus = bus)
        gw.trackViewer(viewer)
        gw.trackEntity(npc)
        val first = MessageKey("astera.npc.first")
        val second = MessageKey("astera.npc.second")

        gw.overrideName(viewer, npc, first)
        gw.overrideName(viewer, npc, second)

        val snapshot = gw.currentOverrides(viewer, npc)
        assertThat(snapshot?.displayName).isEqualTo(second)
        assertThat(bus.publishedOfType<NpcOverrideApplied>()).hasSize(2)
    }

    @Test
    fun `overrideEquipment with null item explicitly hides the slot`() {
        val gw = InMemoryPerViewerNpcStateGateway()
        gw.trackViewer(viewer)
        gw.trackEntity(npc)
        val item = ItemId("iron-helmet")

        gw.overrideEquipment(viewer, npc, EquipSlot.HELMET, item)
        gw.overrideEquipment(viewer, npc, EquipSlot.HELMET, null)

        val snapshot = gw.currentOverrides(viewer, npc)
        assertThat(snapshot?.equipment).containsEntry(EquipSlot.HELMET, null)
        // ItemId override -> null override is a real change, both events fire.
    }

    @Test
    fun `overrideAttribute scale stacks with name override in same snapshot`() {
        val gw = InMemoryPerViewerNpcStateGateway()
        gw.trackViewer(viewer)
        gw.trackEntity(npc)

        gw.overrideAttribute(viewer, npc, NpcAttributeKind.Scale(2.0))
        gw.overrideAttribute(viewer, npc, NpcAttributeKind.Glowing(true))
        gw.overrideName(viewer, npc, MessageKey("astera.boss.title"))

        val snapshot = gw.currentOverrides(viewer, npc)!!
        assertThat(snapshot.attributes["scale"]).isEqualTo(NpcAttributeKind.Scale(2.0))
        assertThat(snapshot.attributes["glowing"]).isEqualTo(NpcAttributeKind.Glowing(true))
        assertThat(snapshot.displayName).isEqualTo(MessageKey("astera.boss.title"))
    }

    @Test
    fun `overrideAttribute Scale replaces previous Scale value`() {
        val gw = InMemoryPerViewerNpcStateGateway()
        gw.trackViewer(viewer)
        gw.trackEntity(npc)

        gw.overrideAttribute(viewer, npc, NpcAttributeKind.Scale(1.5))
        gw.overrideAttribute(viewer, npc, NpcAttributeKind.Scale(3.0))

        val snapshot = gw.currentOverrides(viewer, npc)!!
        assertThat(snapshot.attributes["scale"]).isEqualTo(NpcAttributeKind.Scale(3.0))
    }

    @Test
    fun `clearAllOverrides drops the snapshot and emits one cleared event`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerNpcStateGateway(eventBus = bus)
        gw.trackViewer(viewer)
        gw.trackEntity(npc)
        gw.overrideSkin(viewer, npc, skin)

        gw.clearAllOverrides(viewer, npc)

        assertThat(gw.currentOverrides(viewer, npc)).isNull()
        val cleared = bus.publishedOfType<PerViewerOverridesCleared>()
        assertThat(cleared).hasSize(1)
        assertThat(cleared[0].reason).isEqualTo(ClearReason.Explicit)
        assertThat(cleared[0].gatewayFamily).isEqualTo(PerViewerOverridesCleared.FAMILY_NPC)
        assertThat(cleared[0].clearedCount).isEqualTo(1)
    }

    @Test
    fun `untrackViewer sweeps every override and emits one PlayerQuit cleared event`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerNpcStateGateway(eventBus = bus)
        gw.trackViewer(viewer)
        gw.trackEntity(npc)
        val secondNpc = EntityHandle(id = UUID.randomUUID(), backendId = "citizens")
        gw.trackEntity(secondNpc)
        gw.overrideSkin(viewer, npc, skin)
        gw.overrideName(viewer, secondNpc, MessageKey("astera.npc.second"))

        gw.untrackViewer(viewer)

        val cleared = bus.publishedOfType<PerViewerOverridesCleared>()
        assertThat(cleared).hasSize(1)
        assertThat(cleared[0].reason).isEqualTo(ClearReason.PlayerQuit)
        assertThat(cleared[0].clearedCount).isEqualTo(2)
        assertThat(gw.clearAllOverridesForViewer(viewer)).isZero()
    }

    @Test
    fun `untrackEntity sweeps every viewer's override of that npc`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerNpcStateGateway(eventBus = bus)
        val viewer2 = PlayerId(UUID.randomUUID())
        gw.trackViewer(viewer)
        gw.trackViewer(viewer2)
        gw.trackEntity(npc)
        gw.overrideSkin(viewer, npc, skin)
        gw.overrideSkin(viewer2, npc, skin)

        gw.untrackEntity(npc)

        val cleared = bus.publishedOfType<PerViewerOverridesCleared>()
            .filter { it.reason == ClearReason.TargetDespawned }
        assertThat(cleared).hasSize(2)
        assertThat(cleared.map { it.viewer }).containsExactlyInAnyOrder(viewer, viewer2)
    }
}
