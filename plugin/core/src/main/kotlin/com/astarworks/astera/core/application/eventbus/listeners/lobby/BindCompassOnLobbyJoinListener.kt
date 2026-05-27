package com.astarworks.astera.core.application.eventbus.listeners.lobby

import com.astarworks.astera.core.application.port.outbound.inventory.IInventoryMutator
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerReturnedToLobby
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Wave 5lobby-content (ADR-0119) — give every player the menu-compass on
 * lobby (re-)entry.
 *
 * Subscribed to two events because the lobby-entry surface is two-faced:
 *
 *  - [PlayerJoinedServer] — first time the player connects in a session
 *    (Wave 5e Domain bridge).
 *  - [PlayerReturnedToLobby] — post-match teleport (Wave 5wireup G15 +
 *    Match-side G5 teleport listener).
 *
 * In both cases we place the compass into hotbar slot 0 (the canonical
 * "what's this for?" slot). The mutator is idempotent — re-binding the same
 * slot with the same item is a no-op.
 *
 * # Why a fixed slot index
 *
 * Slot 0 is the leftmost hotbar slot; players see it on join without any
 * scroll. The Compass icon is also the de-facto Minecraft convention for
 * "open menu" since vanilla replaced spectator handheld with it. Future
 * waves may make the slot configurable (per-player settings) — for now
 * hard-coded is correct.
 *
 * # Failure handling
 *
 * [IInventoryMutator.setSlot] returns `Result.Failure` only on offline
 * player or out-of-range slot — neither should happen here (the event fires
 * post-join, slot 0 is always valid). On the off-chance the player went
 * offline between event emit and listener dispatch, we log at DEBUG and
 * swallow — the join must not be visibly broken to the player.
 */
public class BindCompassOnLobbyJoinListener(
    private val inventoryMutator: IInventoryMutator,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handleJoin(event: PlayerJoinedServer) {
        bindCompass(event.playerId, source = "join")
    }

    public fun handleReturn(event: PlayerReturnedToLobby) {
        bindCompass(event.playerId, source = "lobby-return")
    }

    private fun bindCompass(playerId: PlayerId, source: String) {
        when (val outcome = inventoryMutator.setSlot(playerId, COMPASS_SLOT, MENU_COMPASS_ID, 1)) {
            is Result.Success ->
                log.debug("Bound menu-compass to slot {} for {} ({})", COMPASS_SLOT, playerId, source)
            is Result.Failure ->
                log.debug(
                    "Could not bind menu-compass for {} ({}): {}",
                    playerId, source, outcome.error,
                )
        }
    }

    public companion object {
        public const val COMPASS_SLOT: Int = 0
        public val MENU_COMPASS_ID: ItemId = ItemId("menu-compass")
    }
}
