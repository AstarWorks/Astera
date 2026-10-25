package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv
import com.astarworks.astera.match.application.gamemode.siege.SiegeGameMode
import com.astarworks.astera.match.application.gamemode.siege.SiegeRun
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.match.domain.norma.NormaConfig

/**
 * Skirmish: plain team deathmatch on any stage whose only win path is the Norma layer (MODE-15).
 *
 * Norma is not a mode: stars, kill bursts and Norma levels are a standard layer of every game mode
 * (`content/norma-layer/layer.yaml`). Skirmish is the mode that has no objective of its own, so the layer is all that
 * decides it: the first team with a member at `norma_max_level` wins, otherwise the time limit ranks the teams by Norma
 * level, stars carried, then kills. Kills are mirrored into `Match.score` purely for display (sidebar, result screen);
 * they are not an objective ([objectiveStanding] stays `null`).
 */
public class SkirmishGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<SiegeRun>(ID, TITLE_KEY, env) {

    override fun createRun(match: Match, stage: Stage): SiegeRun = SiegeRun(match.id, stage)

    override fun killed(run: SiegeRun, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {
        val e = env ?: return
        if (killerTeam == null || victimTeam == null || killerTeam == victimTeam) return
        e.matches.save(match.copy(score = match.score.withIncrement(killerTeam, 1)))
    }

    /** The win condition (the top Norma level) on top, then every team's kills. */
    override fun hudLines(run: SiegeRun, match: Match): List<HudLine> {
        val target = NormaConfig.parse(env?.config.orEmpty()).maxLevel.toString()
        return buildList {
            add(HudLine(GOAL_LINE, mapOf("max" to target)))
            match.teams.forEach { team ->
                add(
                    HudLine(
                        key = TEAM_LINE,
                        placeholders = mapOf("score" to match.score.valueFor(team.id).toString()),
                        team = team.id,
                    ),
                )
            }
        }
    }

    public companion object {
        /** Stable id used by `content/game-modes/skirmish.yaml` + queue + HUD. */
        public val ID: GameModeId = GameModeId("skirmish")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.skirmish.title")
        private val GOAL_LINE: MessageKey = MessageKey("astera.hud.sidebar.skirmish.goal")
        private val TEAM_LINE: MessageKey = MessageKey("astera.hud.sidebar.skirmish.team")
    }
}
