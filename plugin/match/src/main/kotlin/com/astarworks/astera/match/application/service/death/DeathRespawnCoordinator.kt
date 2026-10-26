package com.astarworks.astera.match.application.service.death

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.application.port.outbound.spectator.ISpectator
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.match.application.eventbus.listeners.respawn.EliminatedTeamSpectators
import com.astarworks.astera.match.application.service.ISpawnSelector
import com.astarworks.astera.match.application.service.SpawnStrategy
import com.astarworks.astera.match.application.usecase.respawn.RespawnSpec
import com.astarworks.astera.match.application.usecase.respawn.RespawnUseCase
import com.astarworks.astera.match.domain.death.DeathRespawnConfig
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * In-match death -> respawn experience (F3-5).
 *
 * On [PlayerKilled] of a match participant whose team is still in the game:
 *
 *  1. Invulnerable for the whole dead window (no further kills / DoT while dead).
 *  2. Death screen: title "You died", subtitle "Killed by X with <weapon>".
 *  3. Death cam: spectator mode with the camera on the killer for
 *     `death_cam_seconds`, then free-cam; left / right click cycles teammates
 *     ([SpectatorTargetCycler], driven by the Paper input listener).
 *  4. Respawn countdown on the action bar (`respawn_delay_seconds`).
 *  5. Respawn via [RespawnUseCase] at a team spawn ([SpawnStrategy.Spread]): HP
 *     restored to the container's max (ADR-0149), spawn protection for
 *     `spawn_protection_seconds` (ended by the player's first attack, see
 *     [onDamageDealt]).
 *
 * Statuses are cleared on death by combat's `ClearStatusesOnPlayerKilledListener`.
 * Deaths of already eliminated teams are routed to [EliminatedTeamSpectators]
 * (they spectate until the match ends instead of respawning). Deaths outside a
 * match are ignored here (`ReturnToLobbyOnOutOfMatchDeathListener`).
 *
 * `respawn_delay_seconds = 0` skips the death screen and respawns immediately.
 * A match ending during the countdown cancels it and returns the player to
 * normal gameplay mode.
 */
public class DeathRespawnCoordinator(
    private val matches: IMatchRepository,
    private val stages: IStageRegistry,
    private val spawnSelector: ISpawnSelector,
    private val respawnUseCase: RespawnUseCase,
    private val healthGateway: IPlayerHealthGateway,
    private val protection: ISpawnProtectionGateway,
    private val scheduler: IScheduler,
    private val hud: IHudGateway,
    private val messages: IMessageRenderer,
    private val players: IPlayerGateway,
    private val items: IItemRegistry?,
    private val skills: ISkillRegistry? = null,
    private val spectator: ISpectator?,
    private val cycler: SpectatorTargetCycler?,
    private val eliminatedTeams: EliminatedTeamSpectators?,
    private val configOf: (MatchId) -> DeathRespawnConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Pending(val matchId: MatchId, val tasks: MutableList<IScheduler.TaskHandle> = mutableListOf())

    private val pending: MutableMap<PlayerId, Pending> = ConcurrentHashMap()

    /** True while [player] is in a death countdown (spectating, waiting to respawn). */
    public fun isDead(player: PlayerId): Boolean = pending.containsKey(player)

    public fun onKilled(event: PlayerKilled) {
        val victim = event.victim
        val match = matches.findMatchOf(victim)
        if (match == null) {
            log.debug("PlayerKilled for {} not in any active match; skipping match-respawn.", victim)
            return
        }
        if (pending.containsKey(victim)) return
        // Eliminated teams spectate until match end instead of respawning.
        if (eliminatedTeams?.takeOver(match, victim) == true) return

        val config = configOf(match.id)
        if (config.respawnDelaySeconds <= 0) {
            respawn(victim, match.id, config)
            return
        }

        val state = Pending(match.id)
        pending[victim] = state

        // Dead window: untouchable (revoked right before the respawn grants the real protection).
        protection.grantProtection(victim, config.respawnDelaySeconds + 1)

        showDeathScreen(event, config)
        when (val started = spectator?.startSpectating(victim)) {
            null, is Result.Success -> Unit
            is Result.Failure -> log.warn("Death cam for {}: spectator mode failed: {}", victim, started.error)
        }
        val killer = event.killer?.takeIf { it != victim }
        val camTicks = config.effectiveDeathCamSeconds * TICKS_PER_SECOND
        if (killer != null && camTicks > 0 && spectator != null) {
            spectator.setCameraTarget(victim, killer)
            state.tasks += scheduler.runLaterTicks(ExecutionScope.Entity(victim.uuid), camTicks.toLong()) { spectator.releaseCameraTarget(victim) }
        } else {
            spectator?.releaseCameraTarget(victim)
        }

        for (second in 0 until config.respawnDelaySeconds) {
            val remaining = config.respawnDelaySeconds - second
            state.tasks += scheduler.runLaterTicks(ExecutionScope.Entity(victim.uuid), second * TICKS_PER_SECOND.toLong()) { showCountdown(victim, remaining) }
        }
        state.tasks += scheduler.runLaterTicks(ExecutionScope.Entity(victim.uuid), config.respawnDelaySeconds * TICKS_PER_SECOND.toLong()) {
            pending.remove(victim)
            respawn(victim, match.id, config)
        }
    }

    /** Spawn protection ends the moment its holder attacks. */
    public fun onDamageDealt(event: DamageDealt) {
        val attacker = event.attacker ?: return
        if (attacker != event.target && protection.isProtected(attacker)) protection.revokeProtection(attacker)
    }

    /**
     * A team eliminated mid-countdown stops counting down: its members stay spectators
     * ([EliminatedTeamSpectators] switches them) instead of respawning.
     */
    public fun onTeamEliminated(event: TeamEliminated) {
        val members = matches.findById(event.matchId)?.teams?.firstOrNull { it.id == event.teamId }?.members ?: return
        for (player in members) {
            val state = pending.remove(player) ?: continue
            state.tasks.forEach { it.cancel() }
            protection.revokeProtection(player)
        }
    }

    /** A finished match cancels every countdown of that match and returns spectators to normal play. */
    public fun onMatchEnded(event: MatchEnded) {
        val stranded = pending.filterValues { it.matchId == event.matchId }.keys
        for (player in stranded) {
            pending.remove(player)?.tasks?.forEach { it.cancel() }
            protection.revokeProtection(player)
            spectator?.stopSpectating(player)
            cycler?.clear(player)
        }
    }

    private fun respawn(victim: PlayerId, matchId: MatchId, config: DeathRespawnConfig) {
        protection.revokeProtection(victim)
        val match = matches.findById(matchId)
        // Team eliminated during the countdown: stays a spectator (EliminatedTeamSpectators owns it).
        if (match != null && eliminatedTeams?.isEliminated(match, victim) == true) return
        spectator?.stopSpectating(victim)
        cycler?.clear(victim)
        if (match == null) return

        val stage = stages.find(match.stageId)
        if (stage == null) {
            log.warn("Respawn for {} in match {}: unknown stage {}; reviving in place.", victim, match.id, match.stageId)
            reviveInPlace(victim)
            return
        }
        val spawn = spawnSelector.selectFor(victim, match.id, stage, SpawnStrategy.Spread)
        if (spawn == null) {
            log.warn("Respawn for {} in match {}: ISpawnSelector returned null; reviving in place.", victim, match.id)
            reviveInPlace(victim)
            return
        }
        val spec = RespawnSpec(
            playerId = victim,
            matchId = match.id,
            spawnPoint = spawn,
            protectionSeconds = config.spawnProtectionSeconds,
            initialHealth = healthGateway.getMaxHealth(victim) ?: DEFAULT_MAX_HP,
        )
        respawnUseCase.respawn(spec).thenAccept { outcome -> when (outcome) {
            is Result.Success -> log.debug("Respawned {} in match {}.", victim, match.id)
            is Result.Failure -> {
                log.warn("Respawn for {} in match {} failed: {}", victim, match.id, outcome.error)
                reviveInPlace(victim)
            }
        } }
    }

    /** A respawn that cannot place the player must still leave them alive (never stuck at 0 HP in the arena). */
    private fun reviveInPlace(victim: PlayerId) {
        healthGateway.setHealth(victim, healthGateway.getMaxHealth(victim) ?: DEFAULT_MAX_HP, HealthChangeSource.Respawn)
    }

    private fun showDeathScreen(event: PlayerKilled, config: DeathRespawnConfig) {
        val victim = event.victim
        val killer = event.killer?.takeIf { it != victim }
        val weapon = KillWeaponLabel.of(victim, event.source, items, skills, messages)
        val (key, placeholders) = when {
            killer != null && weapon != null -> SUBTITLE_WEAPON to mapOf(
                "killer" to nameOf(killer),
                "weapon" to weapon,
            )
            killer != null -> SUBTITLE_KILLER to mapOf("killer" to nameOf(killer))
            else -> SUBTITLE_GENERIC to emptyMap()
        }
        val stay = (config.respawnDelaySeconds * TICKS_PER_SECOND - FADE_IN_TICKS - FADE_OUT_TICKS).coerceAtLeast(10)
        hud.showFor(
            victim,
            HudLayout.Subtitle(
                component = HudComponent.Text(key, HudColor.GRAY, placeholders),
                title = HudComponent.Text(TITLE, HudColor.RED),
                fadeInTicks = FADE_IN_TICKS,
                stayTicks = stay,
                fadeOutTicks = FADE_OUT_TICKS,
            ),
        )
    }

    private fun showCountdown(victim: PlayerId, secondsLeft: Int) {
        hud.showFor(
            victim,
            HudLayout.ActionBar(
                component = HudComponent.Text(
                    COUNTDOWN,
                    if (secondsLeft <= 1) HudColor.RED else HudColor.YELLOW,
                    mapOf("seconds" to secondsLeft.toString()),
                ),
                ttlTicks = COUNTDOWN_TTL_TICKS,
                priority = 2,
            ),
        )
    }

    private fun nameOf(player: PlayerId): String = players.nameOf(player) ?: player.uuid.toString().take(8)

    public companion object {
        private const val TICKS_PER_SECOND = 20
        private const val FADE_IN_TICKS = 5
        private const val FADE_OUT_TICKS = 10
        private const val COUNTDOWN_TTL_TICKS = 25
        private const val DEFAULT_MAX_HP = 20.0
        private val TITLE = MessageKey("astera.death.title")
        private val SUBTITLE_WEAPON = MessageKey("astera.death.subtitle.weapon")
        private val SUBTITLE_KILLER = MessageKey("astera.death.subtitle.killer")
        private val SUBTITLE_GENERIC = MessageKey("astera.death.subtitle.generic")
        private val COUNTDOWN = MessageKey("astera.death.countdown")
    }
}
