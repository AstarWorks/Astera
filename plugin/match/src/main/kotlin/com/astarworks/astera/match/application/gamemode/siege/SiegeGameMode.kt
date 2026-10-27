package com.astarworks.astera.match.application.gamemode.siege

import com.astarworks.astera.match.domain.gamemode.MatchHudStatus
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IActionBarChannel
import com.astarworks.astera.core.application.port.outbound.IHologramGuide
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.IPlayerSpeedGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.domain.event.BlockBroken
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions
import com.astarworks.astera.match.application.port.outbound.IObjectiveStage
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
 * @property presentation the game-moment player (cue / title / particles), resolved lazily; `null` = silent.
 * @property positions live player positions (dead / offline = `null`) for pickup and deposit channels; `null` = no channels run.
 * @property poses where a player looks, for the objective compass arrow; `null` = no arrow.
 * @property speed walk-speed port for the carrier slowdown; `null` = carriers are not slowed.
 * @property actionBar toast channel of the objective compass and warnings; `null` = no toasts.
 * @property stage the visible objective stage (carrier glow, world markers, boss bars); `null` = headless.
 * @property guide the per-player hologram guide arrow (ADR-0295); `null` = no hologram.
 * @property settings player preferences; the guide is off for players who hid the HUD; `null` = everyone sees it.
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
    public val presentation: () -> IPresentation? = { null },
    public val positions: () -> IMatchPlayerPositions? = { null },
    public val poses: () -> IPlayerPoseLookup? = { null },
    public val speed: () -> IPlayerSpeedGateway? = { null },
    public val actionBar: () -> IActionBarChannel? = { null },
    public val stage: () -> IObjectiveStage? = { null },
    public val guide: () -> IHologramGuide? = { null },
    public val settings: () -> IPlayerSettingsRepository? = { null },
) {
    public fun int(key: String, default: Int): Int = config[key]?.toIntOrNull() ?: default

    public fun string(key: String, default: String): String = config[key]?.takeIf { it.isNotBlank() } ?: default
}

/** Per-match state of one siege mode. [ended] latches once the mode declared a result. */
public open class SiegeRun(public val matchId: MatchId, public val stage: Stage) {
    public var ended: Boolean = false

    /** Server tick the next objective reminder is due at; 0 = not scheduled yet. */
    public var reminderAt: Long = 0L

    /** Server tick the slow (once a second) [SiegeGameMode.tickRun] last ran at. */
    internal var slowAt: Long = 0L

    private val nextAt: MutableMap<String, Long> = mutableMapOf()

    /** Count model: each team's remaining count (starts at `count_start`, objective progress takes it down, 0 = knockout). */
    public val counts: MutableMap<TeamId, Int> = mutableMapOf()

    /** Regular time left in ticks as of the last clock call; `-1` = the clock has not reported yet. */
    public var remainingTicks: Long = -1L
        internal set

    /** Ticks overtime has been running; `-1` = not in overtime. */
    public var overtimeTicks: Long = -1L
        internal set

    internal var overtimeStarted: Boolean = false
    internal var leader: TeamId? = null
    internal val lowShown: MutableSet<TeamId> = mutableSetOf()
    internal val timeShown: MutableSet<Int> = mutableSetOf()
    internal var lastTickSecond: Int = -1

    /** True once per [intervalTicks] for [key]; the first call is due at once. */
    public fun due(key: String, now: Long, intervalTicks: Long): Boolean {
        if (now < (nextAt[key] ?: 0L)) return false
        nextAt[key] = now + intervalTicks
        return true
    }
}

/**
 * Shared skeleton of the objective ("siege") modes: carry-tnt,
 * capture-wool, steal-jewelry, carry-mini-tnt.
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

    /** Cadence of [fastTick]; modes with channels / drop timers lower it so a half-second hold resolves on time. */
    protected open val fastTickTicks: Long = SLOW_TICKS

    final override fun tickPeriodTicks(): Long = fastTickTicks

    /** The objective feedback helpers (carrier look, markers, bars, compass, channel); only touched while [env] is set. */
    protected val kit: ObjectiveKit by lazy { ObjectiveKit(checkNotNull(env) { "an inert mode has no objective kit" }, modeId.value) }

    /** Bus subscriptions that route events into the hooks; empty when the mode is inert. */
    public fun subscribe(): List<IDomainEventBus.Subscription> {
        val e = env ?: return emptyList()
        return listOf(
            e.eventBus.subscribe(EntityEnteredRegion::class) { ev ->
                dispatch(ev.matchId) { run, match ->
                    teamOf(match, ev.playerId)?.let { entered(run, match, ev.playerId, it, ev.regionId) }
                }
            },
            e.eventBus.subscribe(EntityLeftRegion::class) { ev ->
                dispatch(ev.matchId) { run, _ -> left(run, ev.playerId, ev.regionId) }
            },
            e.eventBus.subscribe(BlockBroken::class) { ev ->
                dispatch(ev.matchId) { run, match ->
                    if (live(match)) broken(run, match, ev.playerId, teamOf(match, ev.playerId), ev.position)
                }
            },
            e.eventBus.subscribe(PlayerKilled::class) { ev ->
                val match = e.matches.findMatchOf(ev.victim) ?: return@subscribe
                dispatch(match.id) { run, m ->
                    kit.playerDown(ev.victim)
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
        if (countModel) for (team in match.teams) run.counts[team.id] = countStart()
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
        if (!live(match)) return
        fastTick(run, match)
        val now = e.now()
        if (now - run.slowAt < SLOW_TICKS) return
        run.slowAt = now
        tickRun(run, match, tick)
        if (countModel) refreshBars(run, match)
        remindObjective(run, match, e)
    }

    /** Every [REMINDER_TICKS] of live play, everyone gets the mode's "how to win" line again (the briefing is long gone from chat by then). */
    private fun remindObjective(run: R, match: Match, e: SiegeEnv) {
        val now = e.now()
        if (run.reminderAt == 0L) run.reminderAt = now + REMINDER_TICKS
        if (now < run.reminderAt || run.ended) return
        run.reminderAt = now + REMINDER_TICKS
        val lineKey = MessageKey("astera.match.briefing.mode.${modeId.value}")
        for (member in match.teams.flatMap { it.members }) {
            val line = e.messages.render(member, lineKey, CountRules.briefingParams(e.config))
            if (line.isBlank() || line.startsWith("astera.")) continue
            tellPlayer(member, MessageKey("astera.match.briefing.reminder"))
            e.players.sendMessage(member, line)
        }
    }

    final override fun hudStatus(match: Match, nowTick: Long): MatchHudStatus {
        val run = runs[match.id] ?: return MatchHudStatus(lines = MatchHudStatus.scoreLines(match))
        if (!countModel) return MatchHudStatus(lines = hudLines(run, match))
        val counts = match.teams.map { HudLine(HUD_COUNT, mapOf("count" to countOf(run, it.id).toString()), it.id) }
        return MatchHudStatus(phaseLine = clockLine(run), lines = counts + hudLines(run, match))
    }

    /** Sidebar header of the count model: time left, or the overtime countdown (`null` until the clock reported). */
    private fun clockLine(run: R): HudLine? = when {
        run.overtimeTicks >= 0L -> HudLine(HUD_OVERTIME, mapOf("time" to clock(overtimeMaxTicks() - run.overtimeTicks)))
        run.remainingTicks >= 0L -> HudLine(HUD_CLOCK, mapOf("time" to clock(run.remainingTicks)))
        else -> null
    }

    private fun clock(ticks: Long): String {
        val seconds = ((ticks.coerceAtLeast(0L) + SLOW_TICKS - 1) / SLOW_TICKS).toInt()
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    final override fun objectiveStanding(match: Match, team: TeamId): Int? {
        val run = runs[match.id] ?: return null
        return if (countModel) countStart() - countOf(run, team) else null
    }

    final override fun onClock(match: Match, remainingTicks: Long, overtimeTicks: Long) {
        if (!countModel) return
        val run = runs[match.id] ?: return
        run.remainingTicks = remainingTicks
        run.overtimeTicks = overtimeTicks
        if (run.ended) return
        if (overtimeTicks >= 0L) {
            if (!run.overtimeStarted) {
                run.overtimeStarted = true
                announce(match, run, null, emptyMap(), null) { MOMENT_OVERTIME_START }
                refreshBars(run, match)
            } else if (run.due("overtime-pulse", env?.now?.invoke() ?: 0L, OVERTIME_PULSE_TICKS)) {
                announce(match, run, null, emptyMap(), null) { MOMENT_OVERTIME_PULSE }
            }
            return
        }
        val sec = ((remainingTicks + SLOW_TICKS - 1) / SLOW_TICKS).toInt()
        for ((mark, moment) in TIME_MARKS) {
            if (sec <= mark && run.timeShown.add(mark) && sec > mark - TIME_MARK_WINDOW) announce(match, run, null, mapOf("sec" to sec.toString()), null) { moment }
        }
        if (sec in 1..9 && sec != run.lastTickSecond) {
            run.lastTickSecond = sec
            announce(match, run, null, mapOf("sec" to sec.toString()), null) { MOMENT_TIME_TICK }
        }
    }

    final override fun overtimeHolds(match: Match): Boolean {
        if (!countModel) return false
        val run = runs[match.id] ?: return false
        if (run.ended || !live(match)) return false
        val best = match.teams.minOfOrNull { countOf(run, it.id) } ?: return false
        val trailing = match.teams.filter { countOf(run, it.id) > best }.ifEmpty { match.teams }
        return trailing.any { holdsObjective(run, match, it.id) }
    }

    final override fun onMatchEnded(context: GameModeContext) {
        val e = env ?: return
        val run = runs.remove(context.matchId) ?: return
        finish(run)
        kit.endMatch(context.matchId, e.matches.findById(context.matchId)?.teams?.flatMap { it.members }.orEmpty())
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

    /** The count model (`count_start`, `count_per_<event>`, knockout, overtime, announcer); PvE-style modes without teams racing turn it off. */
    protected open val countModel: Boolean = true

    /** Overtime: true while [team] holds or carries the objective (is carrying it home, standing in a zone). Only asked about the trailing team(s). */
    protected open fun holdsObjective(run: R, match: Match, team: TeamId): Boolean = false

    /** Every [fastTickTicks] while the match is ACTIVE / SUDDEN_DEATH (channels, drop timers, carrier trail). */
    protected open fun fastTick(run: R, match: Match) {}

    /** Once a second while the match is ACTIVE / SUDDEN_DEATH. */
    protected open fun tickRun(run: R, match: Match, tick: Long) {}

    /** Match ended: remove whatever [start] put into the world. */
    protected open fun finish(run: R) {}

    // ---- count model API for the modes ------------------------------------

    private val config: Map<String, String> get() = env?.config.orEmpty()

    /** `count_start`: where every team's count begins. */
    protected fun countStart(): Int = CountRules.start(config)

    /** What one [event] (`count_per_<event>` in the mode YAML) takes off the scorer's count; 0 when the YAML names none. */
    protected fun countPer(event: String): Int = CountRules.per(config, event)

    protected fun countOf(run: R, team: TeamId): Int = run.counts[team] ?: countStart()

    /** [team] completed [event] [times] times: its count drops by `count_per_<event>` x [times] (see [countDown]). */
    protected fun countEvent(run: R, match: Match, team: TeamId, event: String, times: Int = 1, at: Vec3? = null) {
        countDown(run, match, team, countPer(event) * times, at)
    }

    /**
     * Takes [amount] off [team]'s count (a negative amount gives count back, capped at the start). Refreshes the HUD, announces
     * a lead change and "close to knockout" (per viewer, ours / theirs), and at 0 plays the knockout and declares [team] the winner.
     * Call it from the mode's objective hooks in place of any win check.
     */
    protected fun countDown(run: R, match: Match, team: TeamId, amount: Int, at: Vec3? = null) {
        if (!countModel || run.ended || amount == 0) return
        val before = countOf(run, team)
        val after = (before - amount).coerceIn(0, countStart())
        if (after == before) return
        run.counts[team] = after
        refreshBars(run, match)
        val args = mapOf("count" to after.toString())
        if (after <= 0) {
            announce(match, run, team, args, at) { if (it == team) MOMENT_KNOCKOUT_WIN else MOMENT_KNOCKOUT_LOSE }
            win(run, team)
            return
        }
        checkLead(run, match, args, at)
        if (amount > 0 && after <= CountRules.lowAt(config) && run.lowShown.add(team)) {
            announce(match, run, team, args, at) { if (it == team) MOMENT_COUNT_LOW_OURS else MOMENT_COUNT_LOW_THEIRS }
        }
    }

    /** The objective moved to [taker]'s side (picked up / seized): [taker]'s team hears "taken", everybody else "lost". */
    protected fun announceObjectiveShift(run: R, match: Match, taker: TeamId, at: Vec3? = null) {
        announce(match, run, taker, emptyMap(), at) { if (it == taker) MOMENT_OBJECTIVE_TAKEN else MOMENT_OBJECTIVE_LOST }
    }

    private fun checkLead(run: R, match: Match, args: Map<String, String>, at: Vec3?) {
        val best = match.teams.minOfOrNull { countOf(run, it.id) } ?: return
        val now = match.teams.filter { countOf(run, it.id) == best }.singleOrNull()?.id ?: return
        val before = run.leader
        run.leader = now
        if (before == null || before == now) return
        announce(match, run, now, args, at) { if (it == now) MOMENT_LEAD_GAIN else MOMENT_LEAD_LOSS }
    }

    /**
     * Plays [pick]`(viewer team)` for every member (title / action bar / cue from `events.yaml`) plus the chat line
     * `astera.announce.<moment>.chat` when that key exists. `{ours}` / `{theirs}` are the viewer's / the rival's colour tags
     * ([subject] is the team the event is about; the rival of its own members is the first other team).
     */
    private fun announce(match: Match, run: R, subject: TeamId?, args: Map<String, String>, at: Vec3?, pick: (TeamId) -> String?) {
        val e = env ?: return
        for (team in match.teams) {
            val moment = pick(team.id) ?: continue
            val rival = if (subject != null && team.id != subject) subject else match.teams.firstOrNull { it.id != team.id }?.id
            val perTeam = args + mapOf(
                "ours" to ObjectiveKit.tagOf(ObjectiveKit.colorOf(team.id)),
                "theirs" to ObjectiveKit.tagOf(rival?.let { ObjectiveKit.colorOf(it) }),
            )
            for (member in team.members) {
                kit.moment(moment, member, perTeam + ("item" to kit.itemName(member)), at, run.stage.world)
                val line = e.messages.render(member, MessageKey("astera.announce.$moment.chat"), perTeam)
                if (line.isNotBlank() && !line.startsWith("astera.")) e.players.sendMessage(member, line)
            }
        }
    }

    /** Boss bar `count` of every member: our progress (1 - count / start) in the team colour against the strongest rival's count. */
    private fun refreshBars(run: R, match: Match) {
        val start = countStart()
        val key = if (run.overtimeTicks >= 0L) BAR_OVERTIME else BAR_COUNT
        for (team in match.teams) {
            val ours = countOf(run, team.id)
            val rival = match.teams.filter { it.id != team.id }.minByOrNull { countOf(run, it.id) } ?: continue
            val params = mapOf(
                "ours" to ObjectiveKit.tagOf(ObjectiveKit.colorOf(team.id)),
                "theirs" to ObjectiveKit.tagOf(ObjectiveKit.colorOf(rival.id)),
                "ours_count" to ours.toString(),
                "theirs_count" to countOf(run, rival.id).toString(),
            )
            for (member in team.members) {
                kit.bar(member, COUNT_BAR, key, params, 1.0 - ours.toDouble() / start, ObjectiveKit.barColorOf(ObjectiveKit.colorOf(team.id)))
            }
        }
    }

    private fun overtimeMaxTicks(): Long = CountRules.overtimeMaxSeconds(config) * SLOW_TICKS

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

        /** One second: the cadence of [tickRun]. */
        public const val SLOW_TICKS: Long = 20L

        /** Two minutes of server ticks between objective reminders. */
        private const val REMINDER_TICKS: Long = 2400L

        /** Overtime loop sting cadence (3 s). */
        private const val OVERTIME_PULSE_TICKS: Long = 60L

        /** A 60 / 30 / 10 s mark only announces if the clock first reports within this many seconds of it. */
        private const val TIME_MARK_WINDOW: Int = 5

        private const val COUNT_BAR: String = "count"
        private val BAR_COUNT: MessageKey = MessageKey("astera.hud.count.bar")
        private val BAR_OVERTIME: MessageKey = MessageKey("astera.hud.count.bar-overtime")
        private val HUD_COUNT: MessageKey = MessageKey("astera.hud.sidebar.count")
        private val HUD_CLOCK: MessageKey = MessageKey("astera.hud.sidebar.phase.count-active")
        private val HUD_OVERTIME: MessageKey = MessageKey("astera.hud.sidebar.phase.count-overtime")

        private const val MOMENT_OVERTIME_START: String = PresentationMoments.OVERTIME_START
        private const val MOMENT_OVERTIME_PULSE: String = PresentationMoments.OVERTIME_PULSE
        private const val MOMENT_TIME_TICK: String = PresentationMoments.TIME_TICK
        private const val MOMENT_KNOCKOUT_WIN: String = PresentationMoments.KNOCKOUT_WIN
        private const val MOMENT_KNOCKOUT_LOSE: String = PresentationMoments.KNOCKOUT_LOSE
        private const val MOMENT_COUNT_LOW_OURS: String = PresentationMoments.COUNT_LOW_OURS
        private const val MOMENT_COUNT_LOW_THEIRS: String = PresentationMoments.COUNT_LOW_THEIRS
        private const val MOMENT_OBJECTIVE_TAKEN: String = PresentationMoments.OBJECTIVE_TAKEN
        private const val MOMENT_OBJECTIVE_LOST: String = PresentationMoments.OBJECTIVE_LOST
        private const val MOMENT_LEAD_GAIN: String = PresentationMoments.LEAD_GAIN
        private const val MOMENT_LEAD_LOSS: String = PresentationMoments.LEAD_LOSS

        private val TIME_MARKS: List<Pair<Int, String>> = listOf(
            60 to PresentationMoments.TIME_60,
            30 to PresentationMoments.TIME_30,
            10 to PresentationMoments.TIME_10,
        )

        private val PHASES: List<IGameModePhase> = listOf(
            IGameModePhase.Lobby,
            IGameModePhase.Countdown,
            IGameModePhase.Active,
            IGameModePhase.SuddenDeath,
            IGameModePhase.Ended,
        )
    }
}
