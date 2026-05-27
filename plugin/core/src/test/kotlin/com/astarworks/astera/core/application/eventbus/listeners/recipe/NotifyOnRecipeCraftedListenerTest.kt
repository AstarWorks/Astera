package com.astarworks.astera.core.application.eventbus.listeners.recipe

import com.astarworks.astera.core.domain.event.RecipeCrafted
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.recipe.RecipeId
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class NotifyOnRecipeCraftedListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `handle sends chat message rendered with recipe placeholders`() {
        val player = FakePlayerGateway()
        val renderer = FakeMessageRenderer()
        val hud = FakeHudGateway()
        val listener = NotifyOnRecipeCraftedListener(player, renderer, hud)

        listener.handle(
            RecipeCrafted(
                playerId = playerId,
                recipeId = RecipeId("iron-sword"),
                output = ItemId("iron-sword"),
                outputCount = 1,
                atTick = 500L,
            ),
        )

        assertThat(renderer.calls).hasSize(1)
        val call = renderer.calls.first()
        assertThat(call.playerId).isEqualTo(playerId)
        assertThat(call.key).isEqualTo(MessageKey("astera.recipe.crafted.chat"))
        assertThat(call.placeholders).containsEntry("recipe", "iron-sword")
        assertThat(call.placeholders).containsEntry("item", "iron-sword")
        assertThat(call.placeholders).containsEntry("count", "1")

        assertThat(player.messages).hasSize(1)
        assertThat(player.messages.first().first).isEqualTo(playerId)
    }

    @Test
    fun `handle pushes Subtitle HUD with crafted-subtitle key`() {
        val player = FakePlayerGateway()
        val renderer = FakeMessageRenderer()
        val hud = FakeHudGateway()
        val listener = NotifyOnRecipeCraftedListener(player, renderer, hud)

        listener.handle(
            RecipeCrafted(
                playerId = playerId,
                recipeId = RecipeId("golden-apple"),
                output = ItemId("golden-apple"),
                outputCount = 2,
                atTick = 700L,
            ),
        )

        val shown = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        assertThat(shown).hasSize(1)
        assertThat(shown.first().playerId).isEqualTo(playerId)
        val layout = shown.first().layout
        assertThat(layout).isInstanceOf(HudLayout.Subtitle::class.java)
        val text = (layout as HudLayout.Subtitle).component as HudComponent.Text
        assertThat(text.messageKey).isEqualTo(MessageKey("astera.recipe.crafted.subtitle"))
    }

    @Test
    fun `outputCount placeholder mirrors the event field exactly`() {
        val player = FakePlayerGateway()
        val renderer = FakeMessageRenderer()
        val hud = FakeHudGateway()
        val listener = NotifyOnRecipeCraftedListener(player, renderer, hud)

        listener.handle(
            RecipeCrafted(
                playerId = playerId,
                recipeId = RecipeId("smelt-iron"),
                output = ItemId("iron-ingot"),
                outputCount = 4,
                atTick = 900L,
            ),
        )

        assertThat(renderer.calls.first().placeholders["count"]).isEqualTo("4")
    }
}
