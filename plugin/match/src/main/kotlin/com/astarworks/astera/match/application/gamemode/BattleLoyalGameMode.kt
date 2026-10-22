package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv
import com.astarworks.astera.match.application.gamemode.siege.SiegeGameMode
import com.astarworks.astera.match.application.gamemode.siege.SiegeRun
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Battle Loyal: plain team deathmatch on any stage. Each kill of an enemy
 * player scores a point for the killer's team (mirrored into `Match.score`
 * for the sidebar); the first team to `score_threshold` points wins.
 */
public class BattleLoyalGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<SiegeRun>(ID, TITLE_KEY, env) {

    private fun threshold(): Int = env?.int("score_threshold", DEFAULT_THRESHOLD) ?: DEFAULT_THRESHOLD

    override fun createRun(match: Match, stage: Stage): SiegeRun = SiegeRun(match.id, stage)

    override fun killed(run: SiegeRun, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {
        val e = env ?: return
        if (killerTeam == null || victimTeam == null || killerTeam == victimTeam) return
        val scored = match.copy(score = match.score.withIncrement(killerTeam, 1))
        e.matches.save(scored)
        val points = scored.score.valueFor(killerTeam)
        tellMatch(match, KEY_SCORED, mapOf("team" to killerTeam.value, "score" to points.toString(), "goal" to threshold().toString()))
        if (points >= threshold()) win(run, killerTeam)
    }

    override fun hudLines(run: SiegeRun, match: Match): List<HudLine> = match.teams.map { team ->
        HudLine(
            key = MessageKey("astera.hud.sidebar.battle-loyal.team"),
            placeholders = mapOf("score" to match.score.valueFor(team.id).toString(), "goal" to threshold().toString()),
            team = team.id,
        )
    }

    public companion object {
        public val ID: GameModeId = GameModeId("battle-loyal")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.battle-loyal.title")
        private val KEY_SCORED: MessageKey = MessageKey("astera.gamemode.battle-loyal.scored")
        private const val DEFAULT_THRESHOLD: Int = 10
    }
}
