package com.astarworks.astera.stats.domain.norma

public sealed class NormaTarget {
    public data class Kills(val count: Long) : NormaTarget()
    public data class Damage(val amount: Long) : NormaTarget()
    public data class MatchWins(val count: Long) : NormaTarget()
    public data class XpGain(val amount: Long) : NormaTarget()
}
