package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv
import com.astarworks.astera.match.application.gamemode.siege.SiegeGameMode
import com.astarworks.astera.match.application.gamemode.siege.SiegeRun
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Carry Mini TNT: small TNT pieces sit on the stage's pickup spots (`pickup_regions`, default the
 * `wool-a/b/c` and `tnt-spawn` zones). Walking into a spot with a piece on it picks it up (up to
 * `carry_limit` at a time); carrying them into an enemy `base-<team>` zone delivers them, one point each.
 * A taken piece grows back after `respawn_seconds`. The carrier dying loses what they carried. First team
 * to `minis_to_win` delivered pieces wins. Reuses the carry-tnt region/marker machinery ([SiegeGameMode]).
 */
public class CarryMiniTntGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<CarryMiniTntGameMode.Run>(ID, TITLE_KEY, env) {

    public class Run(matchId: MatchId, stage: Stage, public val spots: Map<String, Vec3>) : SiegeRun(matchId, stage) {
        /** Spot region -> server tick it was taken at; absent = the piece is lying there. */
        public val takenAt: MutableMap<String, Long> = mutableMapOf()
        public val carried: MutableMap<PlayerId, Int> = mutableMapOf()
        public val delivered: MutableMap<TeamId, Int> = mutableMapOf()
    }

    private fun goal(): Int = env?.int("minis_to_win", DEFAULT_GOAL) ?: DEFAULT_GOAL

    private fun carryLimit(): Int = env?.int("carry_limit", DEFAULT_CARRY_LIMIT) ?: DEFAULT_CARRY_LIMIT

    private fun respawnTicks(): Long = (env?.int("respawn_seconds", DEFAULT_RESPAWN_SECONDS) ?: DEFAULT_RESPAWN_SECONDS) * 20L

    override fun createRun(match: Match, stage: Stage): Run? {
        if (match.teams.any { stage.region("$BASE_PREFIX${it.id.value}") == null }) return null
        val names = env?.string("pickup_regions", DEFAULT_PICKUPS) ?: DEFAULT_PICKUPS
        val spots = names.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { name -> stage.region(name)?.let { name to cellOf(it) } }
            .toMap()
        if (spots.isEmpty()) return null
        return Run(match.id, stage, spots)
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
        val base = teamOfRegion(region, BASE_PREFIX, match) ?: return
        val have = run.carried[player] ?: 0
        if (have == 0 || base == team) return
        run.carried.remove(player)
        val score = (run.delivered[team] ?: 0) + have
        run.delivered[team] = score
        tellMatch(match, KEY_DELIVERED, mapOf("team" to team.value, "count" to have.toString(), "score" to score.toString(), "goal" to goal().toString()))
        if (score >= goal()) win(run, team)
    }

    override fun killed(run: Run, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {
        if (run.carried.remove(victim) == null) return
        tellPlayer(victim, KEY_DROPPED)
    }

    override fun tickRun(run: Run, match: Match, tick: Long) {
        val now = env?.now?.invoke() ?: return
        val grown = run.takenAt.filterValues { now - it >= respawnTicks() }.keys
        for (region in grown) {
            run.takenAt.remove(region)
            run.spots[region]?.let { setBlock(run, it, MARKER_BLOCK) }
        }
    }

    override fun standing(run: Run, match: Match, team: TeamId): Int = run.delivered[team] ?: 0

    override fun hudLines(run: Run, match: Match): List<HudLine> =
        match.teams.map { team ->
            HudLine(
                key = MessageKey("astera.hud.sidebar.carry-mini-tnt.team"),
                placeholders = mapOf("score" to (run.delivered[team.id] ?: 0).toString(), "goal" to goal().toString()),
                team = team.id,
            )
        } + HudLine(
            MessageKey("astera.hud.sidebar.carry-mini-tnt.loose"),
            placeholders = mapOf("count" to (run.spots.size - run.takenAt.size).toString()),
        )

    override fun finish(run: Run) {
        for (cell in run.spots.values) setBlock(run, cell, floorBlock())
    }

    public companion object {
        public val ID: GameModeId = GameModeId("carry-mini-tnt")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.title")
        private val KEY_PICKED_UP: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.picked-up")
        private val KEY_DELIVERED: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.delivered")
        private val KEY_DROPPED: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.dropped")
        private const val BASE_PREFIX: String = "base-"
        private const val MARKER_BLOCK: String = "minecraft:tnt"
        private const val DEFAULT_PICKUPS: String = "wool-a,wool-b,wool-c,tnt-spawn"
        private const val DEFAULT_GOAL: Int = 10
        private const val DEFAULT_CARRY_LIMIT: Int = 4
        private const val DEFAULT_RESPAWN_SECONDS: Int = 15
    }
}
