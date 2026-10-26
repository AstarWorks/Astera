package com.astarworks.astera.match.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerArrivedOnServer
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.match.application.usecase.match.ResumeMatchUseCase
import org.slf4j.LoggerFactory

/**
 * Reacts to [PlayerArrivedOnServer] (the join after the inventory restore, so the resume's
 * teleport and kit are not overwritten by the restore) for players whose session is still in
 * [SessionState.Disconnected] from a recent mid-match drop (Wave 5h).
 *
 * Flow:
 * 1. Resolve the player's tracked session. No session → no-op (they're a
 *    fresh joiner; the inventory-restore listener owns that path).
 * 2. If the session has no `currentMatch` or its state is not Disconnected,
 *    no-op (already resumed via another path, or never disconnected).
 * 3. Try [ResumeMatchUseCase.resume]. On failure (match ended during the gap,
 *    phase not resumable, gateway failure) fall back to teleporting them to
 *    the lobby spawn so they're not stranded in whatever world the game
 *    instance left them in.
 *
 * The fallback intentionally fires-and-forgets — at worst the player lands in
 * the default lobby coordinates, which is the same UX as a fresh login. Phase
 * 6b's Paper adapter will additionally clear any per-match cosmetic state at
 * that point.
 */
public class ReconnectDuringMatchListener(
    private val sessions: IPlayerSessionRepository,
    private val resumeMatch: ResumeMatchUseCase,
    private val worldGateway: IWorldGateway,
) {

    private val log = LoggerFactory.getLogger(ReconnectDuringMatchListener::class.java)

    public fun handle(event: PlayerArrivedOnServer) {
        val session = sessions.find(event.playerId) ?: return
        val matchId = session.currentMatch ?: return
        if (session.state !is SessionState.Disconnected) return

        resumeMatch.resume(event.playerId, matchId).thenAccept { result -> when (result) {
            is Result.Success -> {
                // Resume use case publishes PlayerReconnectedToMatch; nothing else
                // to do here.
            }
            is Result.Failure -> {
                log.debug(
                    "ReconnectDuringMatchListener: resume failed player={} match={} error={}; falling back to lobby spawn",
                    event.playerId, matchId, result.error,
                )
                // The claim is dead (match over, not resumable): a plain lobby player from here on.
                sessions.upsert(session.copy(state = SessionState.Lobby, currentMatch = null))
                worldGateway.lobbySpawn(event.playerId)
            }
        } }
    }
}
