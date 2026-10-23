package com.astarworks.astera.match.domain.norma

/** Why `/astera norma choose` was refused. */
public sealed interface NormaError {
    /** The player is not in a live Norma match. */
    public data object NotInNormaMatch : NormaError

    /** The player already stands at the top level: there is nothing left to choose. */
    public data object AtMaxLevel : NormaError
}
