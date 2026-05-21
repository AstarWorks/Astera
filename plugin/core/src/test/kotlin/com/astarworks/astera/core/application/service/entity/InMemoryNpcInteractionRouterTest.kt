package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcInteractionClick
import com.astarworks.astera.core.domain.gui.GuiIntent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryNpcInteractionRouterTest {

    private fun handle() = EntityHandle(UUID.randomUUID(), "minimal")

    @Test
    fun `right and left click bindings are independent`() {
        val router = InMemoryNpcInteractionRouter()
        val h = handle()
        val shop: GuiIntent = GuiIntent("shop.open", mapOf("shopId" to "iron-shop"))
        val loadout: GuiIntent = GuiIntent("loadout.edit", mapOf("slot" to "MAIN"))

        router.bindRightClick(h, shop)
        router.bindLeftClick(h, loadout)

        assertThat(router.resolve(h, NpcInteractionClick.RIGHT)).isEqualTo(shop)
        assertThat(router.resolve(h, NpcInteractionClick.LEFT)).isEqualTo(loadout)
    }

    @Test
    fun `bind replaces any prior binding for the same click`() {
        val router = InMemoryNpcInteractionRouter()
        val h = handle()
        val first: GuiIntent = GuiIntent("first.action")
        val second: GuiIntent = GuiIntent("second.action")

        router.bindRightClick(h, first)
        router.bindRightClick(h, second)

        assertThat(router.resolve(h, NpcInteractionClick.RIGHT)).isEqualTo(second)
    }

    @Test
    fun `unbind drops every click variant for the handle`() {
        val router = InMemoryNpcInteractionRouter()
        val h = handle()
        router.bindRightClick(h, GuiIntent("a.click"))
        router.bindLeftClick(h, GuiIntent("b.click"))

        router.unbind(h)

        assertThat(router.resolve(h, NpcInteractionClick.RIGHT)).isNull()
        assertThat(router.resolve(h, NpcInteractionClick.LEFT)).isNull()
        assertThat(router.all()).isEmpty()
    }

    @Test
    fun `unbind on an unknown handle is a no-op`() {
        val router = InMemoryNpcInteractionRouter()
        router.unbind(handle())
        assertThat(router.all()).isEmpty()
    }

    @Test
    fun `all returns a defensive snapshot`() {
        val router = InMemoryNpcInteractionRouter()
        val h = handle()
        router.bindRightClick(h, GuiIntent("x.click"))

        val snapshot = router.all()
        router.unbind(h)

        // The previously-returned snapshot is unaffected by the unbind.
        assertThat(snapshot).hasSize(1)
    }
}
