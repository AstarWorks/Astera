package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/** Colour of a boss bar; the host maps it to its own enum. */
public enum class ObjectiveBarColor { WHITE, RED, BLUE, GREEN, YELLOW, PURPLE, PINK }

/**
 * How the objective of a siege mode looks in the world and on the carrier's body.
 *
 * @property block namespaced block id shown (a TNT block, a diamond block, ...).
 * @property glow glow colour name (`red`, `blue`, `gold`, ...) of a world marker; `null` = white.
 * @property scale edge length of the shown block, in blocks.
 * @property spin a world marker turns slowly and bobs (a treasure on a pedestal); only meaningful for markers.
 * @property label rendered MiniMessage line floating over a world marker (a count at a pedestal); `null` = none.
 */
public data class ObjectiveLook(
    val block: String,
    val glow: String? = null,
    val scale: Double = 0.8,
    val spin: Boolean = false,
    val label: String? = null,
)

/**
 * The visible stage of an objective mode: the carrier's glow and carried block, the markers lying in the world
 * (the loose / dropped objective) and the boss bars (objective status, pickup / deposit progress). Everything is
 * per match and torn down by [clearMatch]; the hosts own the entities (Paper: Display entities and boss bars).
 */
public interface IObjectiveStage {
    /** [player] glows and shows [look] on the head while carrying; `null` clears both. [count] pieces are stacked over the head (capped by the host). */
    public fun setCarrying(player: PlayerId, look: ObjectiveLook?, count: Int = 1)

    /**
     * Places or moves the world marker [key] of [match] (a glowing block that stays visible through walls). Calling it
     * again with the same key moves the marker and refreshes its [ObjectiveLook.label] and spin step.
     */
    public fun showMarker(match: MatchId, key: String, world: WorldId, at: Vec3, look: ObjectiveLook)

    public fun removeMarker(match: MatchId, key: String)

    /** Shows or updates boss bar [key] for [player]; [title] is a rendered MiniMessage string, [progress] 0..1. */
    public fun showBar(player: PlayerId, key: String, title: String, progress: Double, color: ObjectiveBarColor)

    public fun hideBar(player: PlayerId, key: String)

    /** Match over: removes every marker of [match] and every carried look / bar of [players]. */
    public fun clearMatch(match: MatchId, players: Collection<PlayerId>)
}
