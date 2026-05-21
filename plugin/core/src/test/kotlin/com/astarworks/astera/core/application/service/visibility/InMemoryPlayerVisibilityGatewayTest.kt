package com.astarworks.astera.core.application.service.visibility

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.EntityHiddenFromViewer
import com.astarworks.astera.core.domain.event.EntityShownToViewer
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.visibility.VisibilityError
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPlayerVisibilityGatewayTest {

    private val viewer = PlayerId(UUID.randomUUID())
    private val otherViewer = PlayerId(UUID.randomUUID())
    private val target = EntityHandle(id = UUID.randomUUID(), backendId = "mob")

    @Test
    fun `hideEntity rejects unknown viewer`() {
        val gw = InMemoryPlayerVisibilityGateway()
        gw.trackEntity(target)

        val result = gw.hideEntity(viewer, target)

        assertThat(result).isEqualTo(Result.failure(VisibilityError.UnknownViewer))
    }

    @Test
    fun `hideEntity rejects unknown target`() {
        val gw = InMemoryPlayerVisibilityGateway()
        gw.trackViewer(viewer)

        val result = gw.hideEntity(viewer, target)

        assertThat(result).isEqualTo(Result.failure(VisibilityError.UnknownTarget))
    }

    @Test
    fun `hideEntity rejects self-hide when target points to viewer player`() {
        val gw = InMemoryPlayerVisibilityGateway()
        gw.trackViewer(viewer)
        val selfTarget = EntityHandle(id = viewer.uuid, backendId = "player")
        gw.trackEntity(selfTarget)

        val result = gw.hideEntity(viewer, selfTarget)

        assertThat(result).isEqualTo(Result.failure(VisibilityError.SameSelfHide))
    }

    @Test
    fun `hideEntity publishes EntityHiddenFromViewer the first time then is idempotent`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPlayerVisibilityGateway(eventBus = bus)
        gw.trackViewer(viewer)
        gw.trackEntity(target)

        gw.hideEntity(viewer, target)
        gw.hideEntity(viewer, target)

        val hidden = bus.publishedOfType<EntityHiddenFromViewer>()
        assertThat(hidden).hasSize(1)
        assertThat(hidden[0].viewer).isEqualTo(viewer)
        assertThat(hidden[0].target).isEqualTo(target)
    }

    @Test
    fun `showEntity publishes EntityShownToViewer only when previously hidden`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPlayerVisibilityGateway(eventBus = bus)
        gw.trackViewer(viewer)
        gw.trackEntity(target)

        // No-op show before hide should not publish anything.
        gw.showEntity(viewer, target)
        assertThat(bus.publishedOfType<EntityShownToViewer>()).isEmpty()

        gw.hideEntity(viewer, target)
        gw.showEntity(viewer, target)

        assertThat(bus.publishedOfType<EntityShownToViewer>()).hasSize(1)
    }

    @Test
    fun `hiddenFromViewer returns an immutable snapshot of the per-viewer index`() {
        val gw = InMemoryPlayerVisibilityGateway()
        gw.trackViewer(viewer)
        gw.trackEntity(target)
        gw.hideEntity(viewer, target)

        val snapshot = gw.hiddenFromViewer(viewer)
        assertThat(snapshot).containsExactly(target)
        // The implementation returns a defensive copy (`toList()`), so
        // adding another hide afterwards must not mutate the prior snapshot.
        val secondTarget = EntityHandle(id = UUID.randomUUID(), backendId = "mob")
        gw.trackEntity(secondTarget)
        gw.hideEntity(viewer, secondTarget)
        assertThat(snapshot).containsExactly(target)
        assertThat(gw.hiddenFromViewer(viewer)).containsExactlyInAnyOrder(target, secondTarget)
    }

    @Test
    fun `visibleToViewers returns the set of viewers NOT hiding the target`() {
        val gw = InMemoryPlayerVisibilityGateway()
        gw.trackViewer(viewer)
        gw.trackViewer(otherViewer)
        gw.trackEntity(target)
        gw.hideEntity(viewer, target)

        assertThat(gw.visibleToViewers(target)).containsExactly(otherViewer)
    }

    @Test
    fun `hidePlayer rejects same viewer == target`() {
        val gw = InMemoryPlayerVisibilityGateway()
        gw.trackViewer(viewer)

        val result = gw.hidePlayer(viewer, viewer)

        assertThat(result).isEqualTo(Result.failure(VisibilityError.SameSelfHide))
    }

    @Test
    fun `hidePlayer publishes a hidden event then showPlayer publishes shown`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPlayerVisibilityGateway(eventBus = bus)
        gw.trackViewer(viewer)
        gw.trackViewer(otherViewer)

        gw.hidePlayer(viewer, otherViewer)
        gw.showPlayer(viewer, otherViewer)

        val hidden = bus.publishedOfType<EntityHiddenFromViewer>()
        val shown = bus.publishedOfType<EntityShownToViewer>()
        assertThat(hidden).hasSize(1)
        assertThat(shown).hasSize(1)
        assertThat(hidden[0].target.id).isEqualTo(otherViewer.uuid)
        assertThat(hidden[0].target.backendId).isEqualTo("player")
    }

    @Test
    fun `untrackViewer drops the per-viewer index entirely`() {
        val gw = InMemoryPlayerVisibilityGateway()
        gw.trackViewer(viewer)
        gw.trackEntity(target)
        gw.hideEntity(viewer, target)

        gw.untrackViewer(viewer)

        assertThat(gw.hiddenFromViewer(viewer)).isEmpty()
        // Re-registering does NOT restore the prior hide.
        gw.trackViewer(viewer)
        assertThat(gw.hiddenFromViewer(viewer)).isEmpty()
    }

    @Test
    fun `untrackEntity removes the target from every per-viewer bucket`() {
        val gw = InMemoryPlayerVisibilityGateway()
        gw.trackViewer(viewer)
        gw.trackViewer(otherViewer)
        gw.trackEntity(target)
        gw.hideEntity(viewer, target)
        gw.hideEntity(otherViewer, target)

        gw.untrackEntity(target)

        assertThat(gw.hiddenFromViewer(viewer)).isEmpty()
        assertThat(gw.hiddenFromViewer(otherViewer)).isEmpty()
    }
}
