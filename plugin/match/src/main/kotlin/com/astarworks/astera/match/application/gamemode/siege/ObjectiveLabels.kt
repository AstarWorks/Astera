package com.astarworks.astera.match.application.gamemode.siege

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.Stage

/**
 * Floating labels that tell a player what each structure of the map is ("your base", "enemy goal: carry it here",
 * "stand here to capture"). Placed when a siege match starts and re-resolved every few seconds; the text is rendered per
 * viewer (language, ours / theirs) and grouped so the host spawns one display per distinct text.
 *
 * What gets a label is decided by the mode id and the stage's region ids: `base-<team>` / `deliver-<team>` (bases and
 * goals), `tnt-spawn`, `wool-*` (capture zones, or the pickup spots of carry-mini-tnt), `jewelry-<team>`,
 * `basket-<team>`, `clam-<n>`, plus every team's spawn points. Regions the stage lacks simply get no label.
 */
public class ObjectiveLabels(private val env: SiegeEnv, private val modeKey: String) {

    /** One label: [owner] = the team it belongs to (`null` = neutral); [ours] is shown to the owner's members, [theirs] to everybody else. */
    private class Spec(val key: String, val at: Vec3, val owner: TeamId?, val ours: String, val theirs: String)

    public fun place(match: Match, stage: Stage) {
        val labels = env.labels() ?: return
        for (spec in specs(match, stage)) {
            val variants = mutableMapOf<String, MutableSet<PlayerId>>()
            for (team in match.teams) for (member in team.members) {
                val mine = spec.owner == null || spec.owner == team.id
                val key = MessageKey("astera.label.${if (mine) spec.ours else spec.theirs}")
                val colour = ObjectiveKit.tagOf(spec.owner?.let { ObjectiveKit.colorOf(it) } ?: "yellow")
                val text = env.messages.render(member, key, mapOf("c" to colour))
                if (text.isBlank() || text.startsWith("astera.")) continue
                variants.getOrPut(text) { mutableSetOf() } += member
            }
            labels.show(match.id, spec.key, stage.world, spec.at, variants)
        }
    }

    public fun clear(match: MatchId) {
        env.labels()?.clearMatch(match)
    }

    private fun specs(match: Match, stage: Stage): List<Spec> = buildList {
        val teams = match.teams.map { it.id }
        val hasGoal = teams.any { stage.region("$DELIVER${it.value}") != null }
        for (team in teams) {
            stage.region("$BASE${team.value}")?.let { base ->
                val theirs = if (hasGoal) "base.theirs" else goalKey()
                add(Spec("base-${team.value}", up(base), team, "base.ours", theirs))
            }
            stage.region("$DELIVER${team.value}")?.let { add(Spec("deliver-${team.value}", up(it), team, "base.ours", goalKey())) }
            stage.region("$BASKET${team.value}")?.let { add(Spec("basket-${team.value}", up(it), team, "basket.ours", "basket.theirs")) }
            if (modeKey == STEAL_JEWELRY) {
                stage.region("$JEWELRY${team.value}")?.let { add(Spec("jewelry-${team.value}", up(it), team, "jewelry.ours", "jewelry.theirs")) }
            }
            spawnCentre(stage, team)?.let { add(Spec("spawn-${team.value}", it, team, "spawn.ours", "spawn.theirs")) }
        }
        if (modeKey == CARRY_TNT) stage.region(TNT_SPAWN)?.let { add(Spec("tnt-spawn", up(it), null, "tnt.pickup", "tnt.pickup")) }
        val wool = stage.regionsWithPrefix(WOOL)
        val woolKey = if (modeKey == CARRY_MINI_TNT) "mini-tnt.pickup" else if (modeKey == CAPTURE_WOOL) "wool.zone" else null
        if (woolKey != null) for (region in wool) add(Spec(region.name!!, up(region), null, woolKey, woolKey))
        for (region in stage.regionsWithPrefix(CLAM)) add(Spec(region.name!!, up(region), null, "clam", "clam"))
    }

    /** Label of the enemy-side goal of this mode's bases (`base.theirs` for modes where the base is nothing special). */
    private fun goalKey(): String = when (modeKey) {
        CARRY_TNT -> "tnt.goal"
        CARRY_MINI_TNT -> "mini-tnt.goal"
        else -> "base.theirs"
    }

    private fun up(region: Region): Vec3 = ObjectiveKit.centreOf(region, LABEL_LIFT)

    private fun spawnCentre(stage: Stage, team: TeamId): Vec3? {
        val points = stage.spawnPoints.filter { it.team == team }
        if (points.isEmpty()) return null
        return Vec3(points.map { it.position.x }.average(), points.maxOf { it.position.y } + SPAWN_LIFT, points.map { it.position.z }.average())
    }

    private companion object {
        const val BASE = "base-"
        const val DELIVER = "deliver-"
        const val BASKET = "basket-"
        const val JEWELRY = "jewelry-"
        const val CLAM = "clam-"
        const val WOOL = "wool-"
        const val TNT_SPAWN = "tnt-spawn"
        const val CARRY_TNT = "carry-tnt"
        const val CARRY_MINI_TNT = "carry-mini-tnt"
        const val CAPTURE_WOOL = "capture-wool"
        const val STEAL_JEWELRY = "steal-jewelry"
        const val LABEL_LIFT = 3.5
        const val SPAWN_LIFT = 3.5
    }
}
