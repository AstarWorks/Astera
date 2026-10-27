package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveKit
import com.astarworks.astera.match.application.port.outbound.ObjectiveLook
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
        showMarkers(run, match)
    }

    /** A glowing, spinning team-coloured gem over each live core: visible through walls so attackers can aim. */
    private fun showMarkers(run: Run, match: Match) {
        for ((team, cell) in run.cores) {
            if (team in run.eliminated) continue
            val look = ObjectiveLook(coreBlock(), ObjectiveKit.colorOf(team), 0.4, spin = true)
            kit.marker(match.id, "$CORE_KEY${team.value}", run.stage.world, Vec3(cell.x + 0.5, cell.y + 1.3, cell.z + 0.5), look)
        }
    }

    override fun tickRun(run: Run, match: Match, tick: Long) {
        val now = env?.now() ?: return
        showMarkers(run, match)
        val total = match.teams.size
        val alive = total - run.eliminated.size
        for ((team, cell) in run.cores) {
            if (team in run.eliminated) continue
            val centre = Vec3(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5)
            kit.beam(run.stage.world, centre, ObjectiveKit.colorOf(team))
            val owners = match.teams.firstOrNull { it.id == team }?.members.orEmpty()
            val intruder = match.teams.filter { it.id != team && it.id !in run.eliminated }.flatMap { it.members }
                .firstNotNullOfOrNull { p -> kit.positionOf(p)?.takeIf { ObjectiveKit.flatDistance(it, centre) <= kit.warnDistance } }
            if (intruder != null && run.due("alarm-${team.value}", now, ALARM_INTERVAL_TICKS)) {
                for (owner in owners) kit.moment(PresentationMoments.OBJECTIVE_CORE_ALARM, owner, mapOf("dir" to kit.arrow(owner, intruder)), intruder, run.stage.world)
            }
        }
        for (team in match.teams) {
            for (member in team.members) {
                kit.statusBar(
                    member, BAR, mapOf("alive" to alive.toString(), "total" to total.toString()),
                    alive.toDouble() / total, ObjectiveKit.barColorOf(ObjectiveKit.colorOf(team.id)),
                )
                val from = kit.positionOf(member) ?: continue
                val enemy = run.cores.filter { (t, _) -> t != team.id && t !in run.eliminated }.values
                    .map { Vec3(it.x + 0.5, it.y + 0.5, it.z + 0.5) }.minByOrNull { ObjectiveKit.flatDistance(from, it) }
                if (enemy != null) kit.compass(member, enemy, HINT_BREAK)
            }
        }
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
        kit.unmarker(match.id, "$CORE_KEY${owner.value}")
        run.cores[owner]?.let { kit.burst(run.stage.world, Vec3(it.x + 0.5, it.y + 0.5, it.z + 0.5), ObjectiveKit.colorOf(owner)) }
        tellMatch(match, KEY_DESTROYED, mapOf("team" to owner.value))
        val alive = match.teams.map { it.id }.filter { it !in run.eliminated }
        if (alive.size <= 1) win(run, alive.singleOrNull())
    }

    /** Alive beats eliminated at the time limit. */
    override fun standing(run: Run, match: Match, team: TeamId): Int = if (team in run.eliminated) 0 else 1

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
        private val BAR: MessageKey = MessageKey("astera.objective.bar.break-core")
        private val HINT_BREAK: MessageKey = MessageKey("astera.objective.hint.break-core.break")
        private const val CORE_KEY: String = "core-"
        private const val ALARM_INTERVAL_TICKS: Long = 60L
        private const val REGION_PREFIX: String = "core-"
        private const val DEFAULT_CORE_BLOCK: String = "minecraft:emerald_block"
    }
}
