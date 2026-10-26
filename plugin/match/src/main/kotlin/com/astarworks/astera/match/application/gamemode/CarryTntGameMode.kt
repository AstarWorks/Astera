package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.match.domain.gamemode.HudLine
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
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Carry TNT: one TNT sits in the `tnt-spawn` zone. Walking into the zone while
 * nobody carries it picks it up; carrying it into an enemy `base-<team>` zone
 * scores a delivery for the carrier's team and puts the TNT back. The carrier
 * dying also returns it. First team to `deliveries_to_win` deliveries wins.
 */
public class CarryTntGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<CarryTntGameMode.Run>(ID, TITLE_KEY, env) {

    public class Run(matchId: MatchId, stage: Stage, public val spawnCell: Vec3) : SiegeRun(matchId, stage) {
        public var carrier: PlayerId? = null
        public val deliveries: MutableMap<TeamId, Int> = mutableMapOf()
    }

    private fun deliveriesToWin(): Int = env?.int("deliveries_to_win", DEFAULT_DELIVERIES) ?: DEFAULT_DELIVERIES

    override fun createRun(match: Match, stage: Stage): Run? {
        val spawn = stage.region(SPAWN_REGION) ?: return null
        if (match.teams.any { stage.region("$BASE_PREFIX${it.id.value}") == null }) return null
        return Run(match.id, stage, cellOf(spawn))
    }

    override fun start(run: Run, match: Match) {
        setBlock(run, run.spawnCell, MARKER_BLOCK)
    }

    override fun entered(run: Run, match: Match, player: PlayerId, team: TeamId, region: String) {
        if (!live(match)) return
        if (region == SPAWN_REGION) {
            if (run.carrier == null) {
                run.carrier = player
                tellMatch(match, KEY_PICKED_UP, mapOf("team" to team.value))
            }
            return
        }
        val base = teamOfRegion(region, BASE_PREFIX, match) ?: return
        if (run.carrier != player || base == team) return
        run.carrier = null
        val score = (run.deliveries[team] ?: 0) + 1
        run.deliveries[team] = score
        tellMatch(match, KEY_DELIVERED, mapOf("team" to team.value, "score" to score.toString(), "goal" to deliveriesToWin().toString()))
        if (score >= deliveriesToWin()) win(run, team)
    }

    override fun killed(run: Run, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {
        if (run.carrier != victim) return
        run.carrier = null
        tellMatch(match, KEY_DROPPED, mapOf("team" to (victimTeam?.value ?: "")))
    }

    override fun standing(run: Run, match: Match, team: TeamId): Int = run.deliveries[team] ?: 0

    override fun hudLines(run: Run, match: Match): List<HudLine> {
        val carrierTeam = run.carrier?.let { c -> match.teams.firstOrNull { c in it.members }?.id }
        val teamLines = match.teams.map { team ->
            HudLine(
                key = MessageKey("astera.hud.sidebar.carry-tnt.team"),
                placeholders = mapOf("score" to (run.deliveries[team.id] ?: 0).toString(), "goal" to deliveriesToWin().toString()),
                team = team.id,
            )
        }
        val carrier = if (carrierTeam != null) {
            HudLine(MessageKey("astera.hud.sidebar.carry-tnt.carried"), team = carrierTeam)
        } else {
            HudLine(MessageKey("astera.hud.sidebar.carry-tnt.loose"))
        }
        return teamLines + carrier
    }

    override fun finish(run: Run) {
        setBlock(run, run.spawnCell, floorBlock())
    }

    public companion object {
        public val ID: GameModeId = GameModeId("carry-tnt")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.carry-tnt.title")
        private val KEY_PICKED_UP: MessageKey = MessageKey("astera.gamemode.carry-tnt.picked-up")
        private val KEY_DELIVERED: MessageKey = MessageKey("astera.gamemode.carry-tnt.delivered")
        private val KEY_DROPPED: MessageKey = MessageKey("astera.gamemode.carry-tnt.dropped")
        private const val SPAWN_REGION: String = "tnt-spawn"
        private const val BASE_PREFIX: String = "base-"
        private const val MARKER_BLOCK: String = "minecraft:tnt"
        private const val DEFAULT_DELIVERIES: Int = 3
    }
}
