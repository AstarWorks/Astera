package com.astarworks.astera.match.domain.norma

/** Why `/astera norma choose` (or a click on the Norma picker) was refused. */
public sealed interface NormaError {
    /** The player is not in any match (lobby). */
    public data object NotInMatch : NormaError

    /** The player's match runs a mode without the Norma layer (a PvE wave). */
    public data object ModeHasNoNorma : NormaError

    /** The match exists but is not running (waiting for players or already ended). */
    public data object MatchNotRunning : NormaError

    /** The player already stands at the top level: there is nothing left to choose. */
    public data object AtMaxLevel : NormaError
}

/** Why a Norma check (`/astera norma check`) was refused. */
public sealed interface NormaCheckError {
    /** The player is not in any match (lobby). */
    public data object NotInMatch : NormaCheckError

    /** The player's match runs a mode without the Norma layer. */
    public data object ModeHasNoNorma : NormaCheckError

    /** The match is not in an active phase. */
    public data object MatchNotRunning : NormaCheckError

    /** The player already stands at the top level. */
    public data object AtMaxLevel : NormaCheckError

    /** The player is not at a base (or is dead / spectating): go back to your own base. */
    public data object NotAtBase : NormaCheckError

    /** The player stands in an ENEMY team's base. */
    public data object EnemyBase : NormaCheckError

    /** At the own base, but the goal is not met: [missing] more [goal] units (stars or kills) are needed. */
    public data class NotReady(val goal: NormaGoal, val missing: Int) : NormaCheckError
}
