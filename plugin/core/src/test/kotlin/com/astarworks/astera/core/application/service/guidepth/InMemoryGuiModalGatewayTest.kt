package com.astarworks.astera.core.application.service.guidepth

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.ModalDismissed
import com.astarworks.astera.core.domain.event.ModalShown
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.guidepth.GuiModalSpec
import com.astarworks.astera.core.domain.guidepth.ModalHandle
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryGuiModalGatewayTest {

    private val playerId = PlayerId(UUID.randomUUID())

    private data class Captured(
        val shown: MutableList<ModalShown> = mutableListOf(),
        val dismissed: MutableList<ModalDismissed> = mutableListOf(),
    )

    private fun newGateway(tick: Long = 5L): Pair<InMemoryGuiModalGateway, Captured> {
        val bus = InMemoryDomainEventBus()
        val cap = Captured()
        bus.subscribe(ModalShown::class) { cap.shown.add(it) }
        bus.subscribe(ModalDismissed::class) { cap.dismissed.add(it) }
        return InMemoryGuiModalGateway(bus) { tick } to cap
    }

    private fun confirmation(): GuiModalSpec = GuiModalSpec.Confirmation(
        titleKey = MessageKey("a"),
        messageKey = MessageKey("b"),
        onConfirm = GuiIntent("c.ok"),
        onCancel = GuiIntent("c.no"),
    )

    private fun notification(): GuiModalSpec = GuiModalSpec.Notification(
        titleKey = MessageKey("a"),
        messageKey = MessageKey("b"),
        dismissAfterTicks = 100L,
    )

    @Test
    fun `showModal publishes ModalShown with kind`() {
        val (g, cap) = newGateway()
        val r = g.showModal(playerId, confirmation())
        assertThat(r.isSuccess).isTrue()
        val handle = (r as Result.Success).value
        assertThat(cap.shown).hasSize(1)
        assertThat(cap.shown[0].handle).isEqualTo(handle)
        assertThat(cap.shown[0].modalKind).isEqualTo("confirmation")
        assertThat(cap.shown[0].atTick).isEqualTo(5L)
    }

    @Test
    fun `showModal tags each variant correctly`() {
        val (g, cap) = newGateway()
        g.showModal(playerId, confirmation())
        g.showModal(playerId, notification())
        assertThat(cap.shown.map { it.modalKind })
            .containsExactly("confirmation", "notification")
    }

    @Test
    fun `dismissModal publishes explicit reason once and is idempotent`() {
        val (g, cap) = newGateway()
        val h = (g.showModal(playerId, confirmation()) as Result.Success).value
        g.dismissModal(h)
        g.dismissModal(h)
        assertThat(cap.dismissed).hasSize(1)
        assertThat(cap.dismissed[0].reasonKind).isEqualTo("explicit")
        assertThat(g.activeModals(playerId)).isEmpty()
    }

    @Test
    fun `dismissAllForPlayer fires bulk_dismiss per handle and clears state`() {
        val (g, cap) = newGateway()
        val h1 = (g.showModal(playerId, confirmation()) as Result.Success).value
        val h2 = (g.showModal(playerId, notification()) as Result.Success).value
        g.dismissAllForPlayer(playerId)
        assertThat(cap.dismissed.map { it.handle }).containsExactlyInAnyOrder(h1, h2)
        assertThat(cap.dismissed).allSatisfy { d -> assertThat(d.reasonKind).isEqualTo("bulk_dismiss") }
        assertThat(g.activeModals(playerId)).isEmpty()
    }

    @Test
    fun `dismissAllForPlayer on unknown player is idempotent`() {
        val (g, cap) = newGateway()
        val r = g.dismissAllForPlayer(PlayerId(UUID.randomUUID()))
        assertThat(r.isSuccess).isTrue()
        assertThat(cap.dismissed).isEmpty()
    }

    @Test
    fun `dismissModal on unknown handle is idempotent`() {
        val (g, cap) = newGateway()
        val r = g.dismissModal(ModalHandle("never-issued"))
        assertThat(r.isSuccess).isTrue()
        assertThat(cap.dismissed).isEmpty()
    }

    @Test
    fun `simulateAutoExpire publishes auto_expired and removes handle`() {
        val (g, cap) = newGateway()
        val h = (g.showModal(playerId, notification()) as Result.Success).value
        g.simulateAutoExpire(h)
        assertThat(cap.dismissed).hasSize(1)
        assertThat(cap.dismissed[0].reasonKind).isEqualTo("auto_expired")
        assertThat(g.activeModals(playerId)).isEmpty()
    }

    @Test
    fun `activeModals preserves show-order`() {
        val (g, _) = newGateway()
        val h1 = (g.showModal(playerId, confirmation()) as Result.Success).value
        val h2 = (g.showModal(playerId, notification()) as Result.Success).value
        assertThat(g.activeModals(playerId)).containsExactly(h1, h2)
    }
}
