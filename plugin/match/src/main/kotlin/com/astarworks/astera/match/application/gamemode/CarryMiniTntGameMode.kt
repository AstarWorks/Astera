package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv
import com.astarworks.astera.match.application.gamemode.siege.SiegeGameMode
import com.astarworks.astera.match.application.gamemode.siege.SiegeRun
import com.astarworks.astera.match.application.gamemode.siege.TntCarrier
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Carry Mini TNT (RTM "collect 10 mini-TNTs into 1 larger TNT", ADR-0029): small TNT pieces sit on the stage's pickup
 * spots (`pickup_regions`). Walking into a spot picks a piece up (up to `carry_limit` at a time); carrying them into
 * your OWN `base-<team>` zone puts them into the team's pool. A taken piece grows back after `respawn_seconds`; a
 * carrier dying loses what they carried.
 *
 * When a pool holds `minis_per_tnt` pieces they combine into one real TNT at the `combine_region` (default `tnt-spawn`).
 * Only the owning team can pick it up; it is then carried and delivered exactly like carry-tnt ([TntCarrier]): into an
 * enemy base, and the carrier dying returns it to its spawn. Only one combined TNT exists at a time; a pool that fills
 * meanwhile waits. First team to `tnts_to_win` delivered TNTs wins.
 */
public class CarryMiniTntGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<CarryMiniTntGameMode.Run>(ID, TITLE_KEY, env) {

    public class Run(matchId: MatchId, stage: Stage, public val spots: Map<String, Vec3>, public val tntCell: Vec3) : SiegeRun(matchId, stage) {
        /** Spot region -> server tick it was taken at; absent = the piece is lying there. */
        public val takenAt: MutableMap<String, Long> = mutableMapOf()
        public val carried: MutableMap<PlayerId, Int> = mutableMapOf()
        public val pool: MutableMap<TeamId, Int> = mutableMapOf()
        public val delivered: MutableMap<TeamId, Int> = mutableMapOf()

        /** The team the combined TNT on the field belongs to; `null` = none exists. */
        public var tntOwner: TeamId? = null
        public val tnt: TntCarrier = TntCarrier()
    }

    private fun tntsToWin(): Int = env?.int("tnts_to_win", DEFAULT_TNTS_TO_WIN) ?: DEFAULT_TNTS_TO_WIN

    private fun minisPerTnt(): Int = env?.int("minis_per_tnt", DEFAULT_MINIS_PER_TNT) ?: DEFAULT_MINIS_PER_TNT

    private fun carryLimit(): Int = env?.int("carry_limit", DEFAULT_CARRY_LIMIT) ?: DEFAULT_CARRY_LIMIT

    private fun respawnTicks(): Long = (env?.int("respawn_seconds", DEFAULT_RESPAWN_SECONDS) ?: DEFAULT_RESPAWN_SECONDS) * 20L

    private fun combineRegion(): String = env?.string("combine_region", DEFAULT_COMBINE_REGION) ?: DEFAULT_COMBINE_REGION

    override fun createRun(match: Match, stage: Stage): Run? {
        if (match.teams.any { stage.region("$BASE_PREFIX${it.id.value}") == null }) return null
        val combine = stage.region(combineRegion()) ?: return null
        val names = env?.string("pickup_regions", DEFAULT_PICKUPS) ?: DEFAULT_PICKUPS
        val spots = names.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != combineRegion() }
            .mapNotNull { name -> stage.region(name)?.let { name to cellOf(it) } }
            .toMap()
        if (spots.isEmpty()) return null
        return Run(match.id, stage, spots, cellOf(combine))
    }

    override fun start(run: Run, match: Match) {
        for (cell in run.spots.values) setBlock(run, cell, MARKER_BLOCK)
    }

    override fun entered(run: Run, match: Match, player: PlayerId, team: TeamId, region: String) {
        if (!live(match)) return
        val cell = run.spots[region]
        if (cell != null) {
            val have = run.carried[player] ?: 0
            if (region in run.takenAt || have >= carryLimit()) return
            run.takenAt[region] = env?.now?.invoke() ?: 0L
            run.carried[player] = have + 1
            setBlock(run, cell, floorBlock())
            tellPlayer(player, KEY_PICKED_UP, mapOf("count" to (have + 1).toString(), "limit" to carryLimit().toString()))
            return
        }
        if (region == combineRegion()) {
            if (run.tntOwner == team && run.tnt.pickUp(player)) {
                setBlock(run, run.tntCell, floorBlock())
                tellMatch(match, KEY_TNT_PICKED_UP, mapOf("team" to team.value))
            }
            return
        }
        val base = teamOfRegion(region, BASE_PREFIX, match) ?: return
        if (base == team) deposit(run, match, player, team) else deliverTnt(run, match, player, team, base)
    }

    /** Own base: the pieces the player carries go into the team's pool, and a full pool combines. */
    private fun deposit(run: Run, match: Match, player: PlayerId, team: TeamId) {
        val have = run.carried.remove(player) ?: return
        val total = (run.pool[team] ?: 0) + have
        run.pool[team] = total
        tellMatch(match, KEY_POOLED, mapOf("team" to team.value, "count" to have.toString(), "pool" to total.toString(), "size" to minisPerTnt().toString()))
        combine(run, match)
    }

    /** Enemy base: the combined TNT's carrier scores it (the same rule as carry-tnt). */
    private fun deliverTnt(run: Run, match: Match, player: PlayerId, team: TeamId, base: TeamId) {
        if (run.tntOwner != team || !run.tnt.deliver(player, team, base)) return
        run.tntOwner = null
        val score = (run.delivered[team] ?: 0) + 1
        run.delivered[team] = score
        tellMatch(match, KEY_DELIVERED, mapOf("team" to team.value, "score" to score.toString(), "goal" to tntsToWin().toString()))
        if (score >= tntsToWin()) win(run, team) else combine(run, match)
    }

    /** Fuses `minis_per_tnt` pooled pieces of the first team that has them into the one combined TNT, unless one is already on the field. */
    private fun combine(run: Run, match: Match) {
        if (run.tntOwner != null || run.ended) return
        val team = match.teams.firstOrNull { (run.pool[it.id] ?: 0) >= minisPerTnt() } ?: return
        run.pool[team.id] = (run.pool[team.id] ?: 0) - minisPerTnt()
        run.tntOwner = team.id
        run.tnt.reset()
        setBlock(run, run.tntCell, MARKER_BLOCK)
        tellMatch(match, KEY_COMBINED, mapOf("team" to team.id.value, "size" to minisPerTnt().toString()))
        val presentation = env?.presentation?.invoke() ?: return
        val audience = match.teams.flatMap { it.members }
        for (member in team.members) {
            presentation.play(PresentationMoments.MINI_TNT_COMBINED, member, mapOf("team" to team.id.value), audience = audience - member)
        }
    }

    override fun killed(run: Run, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {
        if (run.tnt.lose(victim)) {
            setBlock(run, run.tntCell, MARKER_BLOCK)
            tellMatch(match, KEY_TNT_DROPPED, mapOf("team" to (victimTeam?.value ?: "")))
        }
        if (run.carried.remove(victim) != null) tellPlayer(victim, KEY_DROPPED)
    }

    override fun tickRun(run: Run, match: Match, tick: Long) {
        val now = env?.now?.invoke() ?: return
        val grown = run.takenAt.filterValues { now - it >= respawnTicks() }.keys
        for (region in grown) {
            run.takenAt.remove(region)
            run.spots[region]?.let { setBlock(run, it, MARKER_BLOCK) }
        }
    }

    override fun standing(run: Run, match: Match, team: TeamId): Int =
        (run.delivered[team] ?: 0) * minisPerTnt() + (run.pool[team] ?: 0) + if (run.tntOwner == team) minisPerTnt() else 0

    override fun hudLines(run: Run, match: Match): List<HudLine> {
        val teamLines = match.teams.map { team ->
            HudLine(
                key = MessageKey("astera.hud.sidebar.carry-mini-tnt.team"),
                placeholders = mapOf(
                    "pool" to (run.pool[team.id] ?: 0).toString(),
                    "size" to minisPerTnt().toString(),
                    "score" to (run.delivered[team.id] ?: 0).toString(),
                    "goal" to tntsToWin().toString(),
                ),
                team = team.id,
            )
        }
        val owner = run.tntOwner
        val tntLine = when {
            owner == null -> HudLine(
                MessageKey("astera.hud.sidebar.carry-mini-tnt.loose"),
                placeholders = mapOf("count" to (run.spots.size - run.takenAt.size).toString()),
            )
            run.tnt.carrier != null -> HudLine(MessageKey("astera.hud.sidebar.carry-mini-tnt.tnt-carried"), team = owner)
            else -> HudLine(MessageKey("astera.hud.sidebar.carry-mini-tnt.tnt-ready"), team = owner)
        }
        return teamLines + tntLine
    }

    override fun finish(run: Run) {
        for (cell in run.spots.values) setBlock(run, cell, floorBlock())
        setBlock(run, run.tntCell, floorBlock())
    }

    public companion object {
        public val ID: GameModeId = GameModeId("carry-mini-tnt")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.title")
        private val KEY_PICKED_UP: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.picked-up")
        private val KEY_POOLED: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.pooled")
        private val KEY_COMBINED: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.combined.broadcast")
        private val KEY_TNT_PICKED_UP: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.tnt-picked-up")
        private val KEY_TNT_DROPPED: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.tnt-dropped")
        private val KEY_DELIVERED: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.delivered")
        private val KEY_DROPPED: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.dropped")
        private const val BASE_PREFIX: String = "base-"
        private const val MARKER_BLOCK: String = "minecraft:tnt"
        private const val DEFAULT_PICKUPS: String = "wool-a,wool-b,wool-c"
        private const val DEFAULT_COMBINE_REGION: String = "tnt-spawn"
        private const val DEFAULT_MINIS_PER_TNT: Int = 10
        private const val DEFAULT_TNTS_TO_WIN: Int = 1
        private const val DEFAULT_CARRY_LIMIT: Int = 4
        private const val DEFAULT_RESPAWN_SECONDS: Int = 15
    }
}
