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
import kotlin.math.floor

/**
 * Break Core: every team owns one core block (stage region `core-<team>`, a
 * single block). Breaking an enemy core eliminates that team; the last team
 * with an intact core wins. Breaking your own core only puts the block back.
 *
 * Config (`content/game-modes/break-core.yaml`): `core_block` (material key).
 */
public class BreakCoreGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<BreakCoreGameMode.Run>(ID, TITLE_KEY, env) {

    public class Run(matchId: MatchId, stage: Stage, public val cores: Map<TeamId, Vec3>) : SiegeRun(matchId, stage) {
        public val eliminated: MutableSet<TeamId> = mutableSetOf()
    }

    private fun coreBlock(): String = env?.string("core_block", DEFAULT_CORE_BLOCK) ?: DEFAULT_CORE_BLOCK

    override fun createRun(match: Match, stage: Stage): Run? {
        val cores = match.teams.mapNotNull { team ->
            stage.region("$REGION_PREFIX${team.id.value}")?.let { team.id to cellOf(it) }
        }.toMap()
        return if (cores.size == match.teams.size) Run(match.id, stage, cores) else null
    }

    override fun start(run: Run, match: Match) {
        run.cores.values.forEach { setBlock(run, it, coreBlock()) }
    }

    override fun broken(run: Run, match: Match, player: PlayerId, team: TeamId?, position: Vec3) {
        val owner = run.cores.entries.firstOrNull { sameCell(it.value, position) }?.key ?: return
        if (owner in run.eliminated) return
        if (owner == team) {
            // Your own core cannot be broken: put it back.
            run.cores[owner]?.let { setBlock(run, it, coreBlock()) }
            return
        }
        run.eliminated += owner
        tellMatch(match, KEY_DESTROYED, mapOf("team" to owner.value))
        val alive = match.teams.map { it.id }.filter { it !in run.eliminated }
        if (alive.size <= 1) win(run, alive.singleOrNull())
    }

    override fun hudLines(run: Run, match: Match): List<HudLine> = match.teams.map { team ->
        HudLine(
            key = MessageKey(if (team.id in run.eliminated) "astera.hud.sidebar.break-core.destroyed" else "astera.hud.sidebar.break-core.intact"),
            team = team.id,
        )
    }

    override fun finish(run: Run) {
        run.cores.values.forEach { setBlock(run, it, "minecraft:air") }
    }

    private fun sameCell(a: Vec3, b: Vec3): Boolean =
        floor(a.x) == floor(b.x) && floor(a.y) == floor(b.y) && floor(a.z) == floor(b.z)

    public companion object {
        public val ID: GameModeId = GameModeId("break-core")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.break-core.title")
        private val KEY_DESTROYED: MessageKey = MessageKey("astera.gamemode.break-core.core-destroyed")
        private const val REGION_PREFIX: String = "core-"
        private const val DEFAULT_CORE_BLOCK: String = "minecraft:emerald_block"
    }
}
