package com.astarworks.astera.match.application.eventbus.listeners.respawn

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.match.application.service.ISpawnSelector
import com.astarworks.astera.match.application.service.SpawnStrategy
import com.astarworks.astera.match.application.usecase.respawn.RespawnSpec
import com.astarworks.astera.match.application.usecase.respawn.RespawnUseCase
import org.slf4j.LoggerFactory

/**
 * On [PlayerKilled], teleport the victim back to a spawn point via
 * [RespawnUseCase] (Wave 5wireup, ADR-0116 §G4).
 *
 * # Phase 1 PoC posture — max-lives ∞
 *
 * Every death triggers a respawn. Final-death → spectator path lives in
 * Wave 6+ once the per-match life ledger lands; today the wave's success
 * metric is "player keeps playing after dying", which a no-limit respawn
 * captures exactly.
 *
 * # Resolution chain
 *
 *  1. Lookup the victim's active match via [IMatchRepository.findMatchOf].
 *     Missing → DEBUG log + skip. The kill happened outside a match
 *     (admin /kill in lobby, PvE world etc.); no match-respawn is owed.
 *  2. Lookup the match's stage via [IStageRegistry.find]. Missing → WARN
 *     log + skip (stage was unloaded between match-start and the kill, a
 *     race-condition guard).
 *  3. Pick a spawn point via [ISpawnSelector.selectFor] with
 *     [SpawnStrategy.Random]. `null` → WARN log + skip.
 *  4. Call [RespawnUseCase.respawn] with default tuning (5s protection,
 *     20.0 HP, no kit, no status effects). Wave 6+ wires per-mode kit
 *     resolution.
 *
 * # Failure
 *
 * Respawn failures are logged at WARN and swallowed — the player stays
 * dead until the next death-respawn cycle picks them up (or admin
 * intervention). This is conservative; the alternative ("re-kill and
 * retry") risks an infinite respawn loop on a broken stage.
 */
public class RespawnOnPlayerKilledListener(
    private val matches: IMatchRepository,
    private val stages: IStageRegistry,
    private val spawnSelector: ISpawnSelector,
    private val respawnUseCase: RespawnUseCase,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerKilled) {
        val victim = event.victim
        val match = matches.findMatchOf(victim)
        if (match == null) {
            log.debug(
                "PlayerKilled for {} not in any active match; skipping match-respawn.",
                victim,
            )
            return
        }

        val stage = stages.find(match.stageId)
        if (stage == null) {
            log.warn(
                "PlayerKilled for {} in match {} references unknown stage {}; cannot respawn.",
                victim,
                match.id,
                match.stageId,
            )
            return
        }

        val spawn: SpawnPoint? = spawnSelector.selectFor(
            player = victim,
            match = match.id,
            stage = stage,
            strategy = SpawnStrategy.Random,
        )
        if (spawn == null) {
            log.warn(
                "PlayerKilled for {} in match {}: ISpawnSelector returned null; cannot respawn.",
                victim,
                match.id,
            )
            return
        }

        val spec = RespawnSpec(
            playerId = victim,
            matchId = match.id,
            spawnPoint = spawn,
        )
        when (val outcome = respawnUseCase.respawn(spec)) {
            is Result.Success -> log.debug("Respawned {} in match {}.", victim, match.id)
            is Result.Failure -> log.warn(
                "Respawn for {} in match {} failed: {}",
                victim,
                match.id,
                outcome.error,
            )
        }
    }
}
