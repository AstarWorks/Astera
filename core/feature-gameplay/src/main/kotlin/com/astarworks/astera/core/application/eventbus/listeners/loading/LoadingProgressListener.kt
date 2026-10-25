package com.astarworks.astera.core.application.eventbus.listeners.loading

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerArrivedAtIsland
import com.astarworks.astera.core.domain.event.PlayerReturnedToLobby
import com.astarworks.astera.core.domain.event.PlayerTraveledToPveWorld
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Loading-screen subtitle placeholder for cross-world transitions
 * (Wave I 5match-wireup-fix, ADR-0126 §E).
 *
 * # Why this listener exists
 *
 * The three Round 8 travel use cases (`TravelToPveWorldUseCase`,
 * `TravelToSurvivalIslandUseCase`, `ReturnToLobbyUseCase`) publish their
 * "arrived" events ([PlayerTraveledToPveWorld], [PlayerArrivedAtIsland],
 * [PlayerReturnedToLobby]) **after** the teleport succeeds. The world
 * load + chunk-stream happens in the few hundred ms between the
 * use-case call and the event firing; before this listener landed, the
 * player saw nothing during the gap. Private-beta playtesters
 * consistently asked "did the teleport actually go through?" — this
 * listener answers visibly.
 *
 * # Scope (Phase 1)
 *
 * Phase 1 PoC ships only the **post-arrival "Loading world…" frame**:
 * a single [HudLayout.Subtitle] push with a long stay time that fades
 * out as the player's first-frame chunks finish streaming. Actual
 * percentage tracking (0 % → 100 % async-load instrumentation) is
 * deferred to Wave 6b's PaperWorldGateway integration — the
 * Paper/Bukkit `World.loadChunk` family is async there, and the
 * progress polling is gateway-impl-shaped.
 *
 * The subtitle title region is one-shot per transition; we don't need
 * to track per-player handles because [HudLayout.Subtitle] is rendered
 * by the gateway as a fire-and-forget title push (Wave 6b's
 * PaperHudGateway translates to `Player.showTitle`). Re-pushing on a
 * second transition simply replaces the in-flight title.
 *
 * # Per-destination message
 *
 * Three distinct MessageKeys so the i18n catalog can phrase each
 * destination naturally ("Entering the open world…" vs "Arriving at
 * your island…" vs "Returning to lobby…"). Wave 6b can split further
 * if specific worlds want bespoke text.
 *
 * # Failure mode
 *
 * Per-player HUD push failures log at DEBUG and continue — typically
 * means the player just disconnected mid-teleport, which is harmless.
 */
public class LoadingProgressListener(
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun onTraveledToPve(event: PlayerTraveledToPveWorld) {
        push(event.playerId, KEY_PVE)
    }

    public fun onArrivedAtIsland(event: PlayerArrivedAtIsland) {
        push(event.playerId, KEY_ISLAND)
    }

    public fun onReturnedToLobby(event: PlayerReturnedToLobby) {
        push(event.playerId, KEY_LOBBY)
    }

    private fun push(playerId: PlayerId, key: MessageKey) {
        val layout = HudLayout.Subtitle(
            component = HudComponent.Text(messageKey = key),
            fadeInTicks = FADE_IN,
            stayTicks = STAY,
            fadeOutTicks = FADE_OUT,
        )
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "LoadingProgress push failed for {} (key={}): {}",
                playerId, key.value, outcome.error,
            )
        }
    }

    public companion object {
        /** Subtitle fade-in (10 ticks ~ 0.5 s, smooth onset). */
        public const val FADE_IN: Int = 10

        /** Stay duration covers typical world-load (~2 s on a hot cache). */
        public const val STAY: Int = 40

        /** Fade-out blends into first-frame gameplay. */
        public const val FADE_OUT: Int = 10

        public val KEY_PVE: MessageKey = MessageKey("astera.match.loading.pve-world")
        public val KEY_ISLAND: MessageKey = MessageKey("astera.match.loading.island")
        public val KEY_LOBBY: MessageKey = MessageKey("astera.match.loading.lobby")
    }
}
