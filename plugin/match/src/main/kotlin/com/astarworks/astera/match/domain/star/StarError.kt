package com.astarworks.astera.match.domain.star

/** Why a match-star operation was refused (no throwables in the application API). */
public sealed interface StarError {
    /** [amount] was zero or negative. */
    public data class InvalidAmount(val amount: Int) : StarError

    /** The player holds [have] stars but [need] were asked for. */
    public data class Insufficient(val have: Int, val need: Int) : StarError
}
