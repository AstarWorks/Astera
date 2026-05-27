package com.astarworks.astera.match.application.eventbus.listeners.spectator

import com.astarworks.astera.core.application.port.outbound.IPlayerLocationLookup
import com.astarworks.astera.core.application.port.outbound.cinematic.IFreelookGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.match.domain.event.PlayerEnteredSpectator
import org.slf4j.LoggerFactory

/**
 * On [PlayerEnteredSpectator], enable the freelook camera so the spectator
 * can pan around the match without first-person attachment (Wave 5match-hud,
 * ADR-0118 §9).
 *
 * # Anchor position resolution
 *
 * The freelook camera needs an anchor [Vec3]. The event itself does not
 * carry a position (replay-determinism: the spectator transition is a
 * logical fact, not a spatial one — same argument as `LootDropped`). The
 * listener resolves the position via the Wave 5complete
 * [IPlayerLocationLookup] port. When the lookup misses (untracked player —
 * never moved this session, or offline at boot) the listener falls back
 * to the world origin [Vec3.ZERO]; the Wave 6b adapter snaps to the
 * spectator spawn anchor on its next reconciliation.
 *
 * # Target switching (1 / 2 / 3 keys)
 *
 * Phase 1 PoC: only the freelook gateway port is wired here. Number-key
 * target switching is a Paper input-listener concern (Wave 6b) — the
 * adapter subscribes to `PlayerInteractEvent` for slot 0..8 and calls a
 * `IFreelookGateway.anchor(playerId, targetPlayerPosition)`-style API.
 * Per Round 10 wireup scope, the port commitment lives here; the input
 * listener lands when Paper hooks land.
 *
 * # Distinct from `EnterSpectatorUseCase.worldGateway.spectatorSpawn`
 *
 * The use case's `IWorldGateway.spectatorSpawn` teleports the player's
 * body to the spectator vantage point. The freelook gateway then
 * controls *where the camera looks from*. The two are complementary —
 * the use case owns position, this listener owns camera state.
 */
public class SpectatorCameraControlListener(
    private val freelookGateway: IFreelookGateway,
    private val locationLookup: IPlayerLocationLookup,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerEnteredSpectator) {
        val anchor = locationLookup.locate(event.playerId)?.position ?: Vec3.ZERO
        when (val outcome = freelookGateway.enableFreelook(event.playerId, anchor)) {
            is Result.Success -> log.debug(
                "Freelook enabled for spectator {} at {} (match {})",
                event.playerId,
                anchor,
                event.matchId,
            )
            is Result.Failure -> log.debug(
                "Freelook enable failed for spectator {} (match {}): {}",
                event.playerId,
                event.matchId,
                outcome.error,
            )
        }
    }
}
