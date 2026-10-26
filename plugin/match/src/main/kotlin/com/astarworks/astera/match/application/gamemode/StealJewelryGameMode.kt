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
 * Steal Jewelry: each team keeps `gems_per_team` jewels on its pedestal (stage
 * region `jewelry-<team>`). Walking into an enemy pedestal zone takes one of
 * its jewels; walking into your own `base-<team>` zone banks what you carry.
 * A carrier who dies loses the jewels back to their pedestals. A team wins by
 * banking every enemy jewel.
 */
public class StealJewelryGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<StealJewelryGameMode.Run>(ID, TITLE_KEY, env) {

    public class Run(matchId: MatchId, stage: Stage, public val pedestals: Map<TeamId, Vec3>, gems: Int) :
        SiegeRun(matchId, stage) {
        /** Jewels still on each team's pedestal. */
        public val onPedestal: MutableMap<TeamId, Int> = pedestals.keys.associateWith { gems }.toMutableMap()

        /** Jewels each player carries, tagged with the team they were stolen from. */
        public val carried: MutableMap<PlayerId, MutableList<TeamId>> = mutableMapOf()

        /** Jewels each team has banked. */
        public val banked: MutableMap<TeamId, Int> = mutableMapOf()
    }

    private fun gemsPerTeam(): Int = env?.int("gems_per_team", DEFAULT_GEMS) ?: DEFAULT_GEMS

    override fun createRun(match: Match, stage: Stage): Run? {
        val pedestals = match.teams.mapNotNull { team ->
            stage.region("$PEDESTAL_PREFIX${team.id.value}")?.let { team.id to cellOf(it) }
        }.toMap()
        if (pedestals.size != match.teams.size) return null
        if (match.teams.any { stage.region("$BASE_PREFIX${it.id.value}") == null }) return null
        return Run(match.id, stage, pedestals, gemsPerTeam())
    }

    override fun start(run: Run, match: Match) {
        run.pedestals.values.forEach { setBlock(run, it, PEDESTAL_BLOCK) }
    }

    override fun entered(run: Run, match: Match, player: PlayerId, team: TeamId, region: String) {
        if (!live(match)) return
        teamOfRegion(region, PEDESTAL_PREFIX, match)?.let { owner ->
            steal(run, match, player, team, owner)
            return
        }
        val home = teamOfRegion(region, BASE_PREFIX, match) ?: return
        if (home == team) deposit(run, match, player, team)
    }

    private fun steal(run: Run, match: Match, player: PlayerId, team: TeamId, owner: TeamId) {
        if (owner == team || (run.onPedestal[owner] ?: 0) <= 0) return
        run.onPedestal[owner] = run.onPedestal.getValue(owner) - 1
        run.carried.getOrPut(player) { mutableListOf() } += owner
        tellMatch(match, KEY_STOLEN, mapOf("team" to team.value, "victim" to owner.value))
    }

    private fun deposit(run: Run, match: Match, player: PlayerId, team: TeamId) {
        val gems = run.carried.remove(player)?.takeIf { it.isNotEmpty() } ?: return
        val total = (run.banked[team] ?: 0) + gems.size
        run.banked[team] = total
        val goal = gemsPerTeam() * (match.teams.size - 1)
        tellMatch(match, KEY_BANKED, mapOf("team" to team.value, "score" to total.toString(), "goal" to goal.toString()))
        if (total >= goal) win(run, team)
    }

    override fun killed(run: Run, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {
        val gems = run.carried.remove(victim)?.takeIf { it.isNotEmpty() } ?: return
        gems.forEach { owner -> run.onPedestal[owner] = (run.onPedestal[owner] ?: 0) + 1 }
        tellMatch(match, KEY_RECOVERED, mapOf("team" to (victimTeam?.value ?: "")))
    }

    override fun standing(run: Run, match: Match, team: TeamId): Int = run.banked[team] ?: 0

    override fun hudLines(run: Run, match: Match): List<HudLine> {
        val goal = gemsPerTeam() * (match.teams.size - 1)
        return match.teams.map { team ->
            HudLine(
                key = MessageKey("astera.hud.sidebar.steal-jewelry.team"),
                placeholders = mapOf(
                    "score" to (run.banked[team.id] ?: 0).toString(),
                    "goal" to goal.toString(),
                    "left" to (run.onPedestal[team.id] ?: 0).toString(),
                ),
                team = team.id,
            )
        }
    }

    override fun finish(run: Run) {
        run.pedestals.values.forEach { setBlock(run, it, floorBlock()) }
    }

    public companion object {
        public val ID: GameModeId = GameModeId("steal-jewelry")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.steal-jewelry.title")
        private val KEY_STOLEN: MessageKey = MessageKey("astera.gamemode.steal-jewelry.stolen")
        private val KEY_BANKED: MessageKey = MessageKey("astera.gamemode.steal-jewelry.banked")
        private val KEY_RECOVERED: MessageKey = MessageKey("astera.gamemode.steal-jewelry.recovered")
        private const val PEDESTAL_PREFIX: String = "jewelry-"
        private const val BASE_PREFIX: String = "base-"
        private const val PEDESTAL_BLOCK: String = "minecraft:diamond_block"
        private const val DEFAULT_GEMS: Int = 3
    }
}
