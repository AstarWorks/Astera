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
