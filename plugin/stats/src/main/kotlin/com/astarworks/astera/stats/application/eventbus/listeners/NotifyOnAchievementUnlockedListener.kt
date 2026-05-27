package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import org.slf4j.LoggerFactory

/**
 * On [AchievementUnlocked], send a chat unlock message + push a subtitle
 * HUD popup stub (Wave H 5content-depth, ADR-0125).
 *
 * # Why a second listener on this event
 *
 * The existing [RefreshNameplateOnAchievementUnlockedListener] (Wave 5flex,
 * ADR-0102) updates the player's nameplate badge cache. That is a
 * projection-rebuild concern. This listener is a separate concern: surface
 * the unlock to the player (chat + HUD subtitle). Splitting keeps each
 * subscriber narrow and lets either side be swapped independently.
 *
 * # Placement
 *
 * The event ([com.astarworks.astera.stats.domain.event.AchievementUnlocked])
 * lives in the stats plugin's domain package. ADR-0022 sibling-isolation
 * forbids core → stats imports, so the listener has to live in stats too
 * (parallel to `RefreshNameplateOnAchievementUnlockedListener`).
 *
 * # Phase 1 PoC stubs
 *
 * - Chat: real `IPlayerGateway.sendMessage` → MiniMessage rendered by
 *   `IMessageRenderer`. Already wired in `AsteraStatsPlugin`.
 * - HUD popup: `HudLayout.Subtitle` through `IHudGateway`. `InMemoryHudGateway`
 *   records the call and publishes `HudShown`; Wave 6b's Paper popup adapter
 *   carries the payload through to the screen unchanged.
 *
 * # Idempotency
 *
 * `AchievementUnlocked` is published exactly once per (player, achievement)
 * pair by `UnlockAchievementUseCase` (subsequent attempts surface
 * `AchievementError.AlreadyUnlocked` and DO NOT re-publish). Re-handling
 * the same event would yield a duplicate chat line, which is harmless
 * but not exercised in production.
 */
public class NotifyOnAchievementUnlockedListener(
    private val playerGateway: IPlayerGateway,
    private val messages: IMessageRenderer,
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: AchievementUnlocked) {
        val chatKey = MessageKey("astera.achievement.unlocked.chat")
        val rendered = messages.render(
            playerId = event.playerId,
            key = chatKey,
            placeholders = mapOf(
                "achievement" to event.achievementId.value,
            ),
        )
        playerGateway.sendMessage(event.playerId, rendered)

        val layout = HudLayout.Subtitle(
            component = HudComponent.Text(
                messageKey = MessageKey("astera.achievement.unlocked.subtitle"),
                color = HudColor.GOLD,
            ),
            fadeInTicks = 10,
            stayTicks = 60,
            fadeOutTicks = 10,
        )
        hudGateway.showFor(event.playerId, layout).also { result ->
            if (result is com.astarworks.astera.core.domain.Result.Failure) {
                log.debug(
                    "HUD achievement-unlocked subtitle failed for {} ({}): {}",
                    event.playerId,
                    event.achievementId,
                    result.error,
                )
            }
        }
    }
}
