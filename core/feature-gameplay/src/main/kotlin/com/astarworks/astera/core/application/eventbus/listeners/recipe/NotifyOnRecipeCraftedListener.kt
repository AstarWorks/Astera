package com.astarworks.astera.core.application.eventbus.listeners.recipe

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.event.RecipeCrafted
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * On [RecipeCrafted], send the player a chat confirmation + push a
 * subtitle HUD stub (Wave H 5content-depth, ADR-0125).
 *
 * # Why this listener
 *
 * `CraftRecipeUseCase` publishes [RecipeCrafted] (Wave 5s, ADR-0091) once
 * the output item has been counted as produced, but Phase 1 PoC had no
 * subscriber that surfaced anything to the player. This listener closes
 * the gap with two effects:
 *
 *   1. **Chat broadcast** — single-player message via `IPlayerGateway.sendMessage`
 *      after `IMessageRenderer` resolves `astera.recipe.crafted.chat` for the
 *      player's locale. Other players in the world do not receive it — a
 *      successful craft is a personal event, not a server-wide broadcast.
 *   2. **HUD subtitle stub** — pushes a `HudLayout.Subtitle` through
 *      `IHudGateway`. `InMemoryHudGateway` records the call; Wave 6b's
 *      `PaperHudGateway` carries the payload through unchanged.
 *
 * # Achievement progress bridge
 *
 * Recipe-driven `ItemAcquired` re-publishing already lives in
 * `CraftRecipeUseCase` (see [RecipeCrafted] KDoc §Recipe-driven `ItemAcquired`),
 * so this listener does not double-publish. Advancement progression keeps
 * flowing through the existing `TrackAdvancementProgressListener` chain.
 *
 * # Idempotency
 *
 * `RecipeCrafted` is published once per successful craft; re-handling
 * yields duplicate chat / HUD calls, which `InMemoryHudGateway` and
 * `IPlayerGateway` both accept idempotently.
 */
public class NotifyOnRecipeCraftedListener(
    private val playerGateway: IPlayerGateway,
    private val messages: IMessageRenderer,
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: RecipeCrafted) {
        val chatKey = MessageKey("astera.recipe.crafted.chat")
        val rendered = messages.render(
            playerId = event.playerId,
            key = chatKey,
            placeholders = mapOf(
                "recipe" to event.recipeId.value,
                "item" to event.output.value,
                "count" to event.outputCount.toString(),
            ),
        )
        playerGateway.sendMessage(event.playerId, rendered)

        val layout = HudLayout.Subtitle(
            component = HudComponent.Text(
                messageKey = MessageKey("astera.recipe.crafted.subtitle"),
                color = HudColor.GREEN,
            ),
            fadeInTicks = 10,
            stayTicks = 40,
            fadeOutTicks = 10,
        )
        hudGateway.showFor(event.playerId, layout).also { result ->
            if (result is com.astarworks.astera.core.domain.Result.Failure) {
                log.debug(
                    "HUD recipe-crafted subtitle failed for {} ({}): {}",
                    event.playerId,
                    event.recipeId,
                    result.error,
                )
            }
        }
    }
}
