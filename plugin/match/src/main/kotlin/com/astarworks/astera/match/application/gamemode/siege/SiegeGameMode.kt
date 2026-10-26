package com.astarworks.astera.match.application.gamemode.siege

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.match.domain.gamemode.MatchHudStatus
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.event.BlockBroken
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.domain.event.EntityEnteredRegion
import com.astarworks.astera.match.domain.event.EntityLeftRegion
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.GameModeEvent
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.domain.mode.GameModeId
import kotlin.math.floor
import org.slf4j.LoggerFactory

/**
 * What a siege mode needs from the host (WP11). Built once by `MatchWiring`;
 * a mode constructed without one (the reflective `GameModeContents` instance)
 * is inert.
 *
 * @property config the mode's `default_config` from `content/game-modes/<id>.yaml`.
 * @property now current server tick (for framework event stamps).
 */
public class SiegeEnv(
    public val eventBus: IDomainEventBus,
    public val matches: IMatchRepository,
    public val stages: IStageRegistry,
    public val lifecycle: IMatchLifecycle,
    public val world: IWorldGateway?,
    public val messages: IMessageRenderer,
    public val players: IPlayerGateway,
    public val config: Map<String, String>,
    public val now: () -> Long,
) {
    public fun int(key: String, default: Int): Int = config[key]?.toIntOrNull() ?: default

    public fun string(key: String, default: String): String = config[key]?.takeIf { it.isNotBlank() } ?: default
}

/** Per-match state of one siege mode. [ended] latches once the mode declared a result. */
public open class SiegeRun(public val matchId: MatchId, public val stage: Stage) {
    public var ended: Boolean = false
}

/**
 * Shared skeleton of the objective ("siege") modes: break-core, carry-tnt,
 * capture-wool, steal-jewelry, battle-loyal.
 *
 * A mode is a pure reaction to match-scoped domain events — region
 * transitions ([EntityEnteredRegion] / [EntityLeftRegion], derived from
 * `PlayerMoved`), [BlockBroken] and [PlayerKilled] — over the **named regions
 * of the match's stage** (`core-<team>`, `base-<team>`, `wool-*`, ...; see
 * `content/stages/README.md`). It owns its per-match state, declares the
 * winner through [EndReason.TeamVictory], and never touches Bukkit.
 *
 * Hooks run only for matches that started under this mode, so several modes
 * can be registered side by side.
 */
public abstract class SiegeGameMode<R : SiegeRun>(
    private val modeId: GameModeId,
    private val titleKey: MessageKey,
    protected val env: SiegeEnv?,
) : IGameMode {

    private val log = LoggerFactory.getLogger(javaClass)
    private val runs: MutableMap<MatchId, R> = mutableMapOf()

    final override fun id(): GameModeId = modeId

    final override fun titleKey(): MessageKey = titleKey

    override fun defaultPhases(): List<IGameModePhase> = PHASES

    override fun tickPeriodTicks(): Long = 20L

    /** Bus subscriptions that route events into the hooks; empty when the mode is inert. */
    public fun subscribe(): List<IDomainEventBus.Subscription> {
        val e = env ?: return emptyList()
        return listOf(
            e.eventBus.subscribe(EntityEnteredRegion::class, EventDelivery.Global) { ev ->
                dispatch(ev.matchId) { run, match ->
                    teamOf(match, ev.playerId)?.let { entered(run, match, ev.playerId, it, ev.regionId) }
                }
            },
            e.eventBus.subscribe(EntityLeftRegion::class, EventDelivery.Global) { ev ->
                dispatch(ev.matchId) { run, _ -> left(run, ev.playerId, ev.regionId) }
            },
            e.eventBus.subscribe(BlockBroken::class, EventDelivery.Global) { ev ->
                dispatch(ev.matchId) { run, match ->
                    if (live(match)) broken(run, match, ev.playerId, teamOf(match, ev.playerId), ev.position)
                }
            },
            e.eventBus.subscribe(PlayerKilled::class, EventDelivery.Global) { ev ->
                val match = e.matches.findMatchOf(ev.victim) ?: return@subscribe
                dispatch(match.id) { run, m ->
                    if (live(m)) killed(run, m, ev.victim, teamOf(m, ev.victim), ev.killer, ev.killer?.let { k -> teamOf(m, k) })
                }
            },
        )
    }

    final override fun onMatchStarted(context: GameModeContext) {
        val e = env ?: return
        val match = e.matches.findById(context.matchId) ?: return
        val stage = e.stages.find(match.stageId) ?: return
        val run = createRun(match, stage) ?: run {
            log.warn("{}: stage '{}' has no objective regions for this mode; match {} runs without an objective.", modeId, stage.id, match.id)
            return
        }
        runs[match.id] = run
        start(run, match)
        e.eventBus.publish(GameModeEvent.GameModeStarted(matchId = match.id, modeId = modeId, atTick = e.now()))
    }

    override fun onPhaseChanged(context: GameModeContext, oldPhase: IGameModePhase, newPhase: IGameModePhase) {
        val e = env ?: return
        if (oldPhase == newPhase || context.matchId !in runs) return
        e.eventBus.publish(
            GameModeEvent.GameModePhaseChanged(
                matchId = context.matchId,
                modeId = modeId,
                oldPhase = oldPhase,
                newPhase = newPhase,
                atTick = e.now(),
            ),
        )
    }

    final override fun onTick(context: GameModeContext, tick: Long) {
        val e = env ?: return
        val run = runs[context.matchId] ?: return
        val match = e.matches.findById(context.matchId) ?: return
        if (live(match)) tickRun(run, match, tick)
    }

    final override fun hudStatus(match: Match, nowTick: Long): MatchHudStatus {
        val run = runs[match.id] ?: return MatchHudStatus(lines = MatchHudStatus.scoreLines(match))
        return MatchHudStatus(lines = hudLines(run, match))
    }

    final override fun objectiveStanding(match: Match, team: TeamId): Int? {
        val run = runs[match.id] ?: return null
        return standing(run, match, team)
    }

    final override fun onMatchEnded(context: GameModeContext) {
        val e = env ?: return
        val run = runs.remove(context.matchId) ?: return
        finish(run)
        e.eventBus.publish(GameModeEvent.GameModeEnded(matchId = context.matchId, modeId = modeId, atTick = e.now()))
    }

    // ---- hooks ----------------------------------------------------------

    /** Builds the per-match state from the stage, or `null` when the stage lacks this mode's regions. */
    protected abstract fun createRun(match: Match, stage: Stage): R?

    protected open fun start(run: R, match: Match) {}

    protected open fun entered(run: R, match: Match, player: PlayerId, team: TeamId, region: String) {}

    protected open fun left(run: R, player: PlayerId, region: String) {}

    protected open fun broken(run: R, match: Match, player: PlayerId, team: TeamId?, position: Vec3) {}

    protected open fun killed(run: R, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {}

    /** Objective state for the match sidebar (WP14); defaults to the plain team score. */
    protected open fun hudLines(run: R, match: Match): List<HudLine> = MatchHudStatus.scoreLines(match)

    /** How far [team] is along this mode's objective, for the Norma layer's time-limit ranking; `null` = no objective score. */
    protected open fun standing(run: R, match: Match, team: TeamId): Int? = null

    /** Once per [tickPeriodTicks] while the match is ACTIVE / SUDDEN_DEATH. */
    protected open fun tickRun(run: R, match: Match, tick: Long) {}

    /** Match ended: remove whatever [start] put into the world. */
    protected open fun finish(run: R) {}

    // ---- helpers for the modes -------------------------------------------

    protected fun live(match: Match): Boolean =
        match.phase == MatchPhase.ACTIVE || match.phase == MatchPhase.SUDDEN_DEATH

    protected fun teamOf(match: Match, player: PlayerId): TeamId? =
        match.teams.firstOrNull { player in it.members }?.id

    /** Declares [winner] (`null` = draw) and ends the match once. */
    protected fun win(run: R, winner: TeamId?) {
        endWith(run, EndReason.TeamVictory(winner))
    }

    /** Ends the match once with a mode-specific [reason] (PvE results carry wave counts, ADR-0249). */
    protected fun endWith(run: R, reason: EndReason) {
        val e = env ?: return
        if (run.ended) return
        run.ended = true
        e.lifecycle.end(run.matchId, reason)
    }

    /** Sends [key] to every current member of the match. */
    protected fun tellMatch(match: Match, key: MessageKey, params: Map<String, String> = emptyMap()) {
        for (team in match.teams) for (member in team.members) tellPlayer(member, key, params)
    }

    protected fun tellPlayer(player: PlayerId, key: MessageKey, params: Map<String, String> = emptyMap()) {
        val e = env ?: return
        e.players.sendMessage(player, e.messages.render(player, key, params))
    }

    protected fun setBlock(run: R, cell: Vec3, type: String) {
        env?.world?.placeBlock(run.stage.world, cell, BlockType(type))
    }

    /** Block cell an objective marker occupies for [region]: its centre column at the region's lowest layer. */
    protected fun cellOf(region: Region): Vec3 = Vec3(
        floor((region.min.x + region.max.x) / 2),
        floor(region.min.y),
        floor((region.min.z + region.max.z) / 2),
    )

    protected fun floorBlock(): String = env?.string("floor_block", DEFAULT_FLOOR_BLOCK) ?: DEFAULT_FLOOR_BLOCK

    /** The team a `<prefix><teamId>` region belongs to, or `null` when [region] does not match. */
    protected fun teamOfRegion(region: String, prefix: String, match: Match): TeamId? =
        region.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.let { id -> match.teams.firstOrNull { it.id.value == id }?.id }

    /** Runs [action] on the live run of [matchId]; ignored when the match is unknown, ended, or runs another mode. */
    protected fun dispatch(matchId: MatchId, action: (R, Match) -> Unit) {
        val e = env ?: return
        val run = runs[matchId] ?: return
        val match = e.matches.findById(matchId) ?: return
        if (match.phase == MatchPhase.ENDED) return
        action(run, match)
    }

    public companion object {
        public const val DEFAULT_FLOOR_BLOCK: String = "minecraft:grass_block"

        private val PHASES: List<IGameModePhase> = listOf(
            IGameModePhase.Lobby,
            IGameModePhase.Countdown,
            IGameModePhase.Active,
            IGameModePhase.SuddenDeath,
            IGameModePhase.Ended,
        )
    }
}
