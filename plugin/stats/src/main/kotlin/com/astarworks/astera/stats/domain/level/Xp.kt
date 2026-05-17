package com.astarworks.astera.stats.domain.level

@JvmInline
public value class Xp(public val value: Long) {
    init {
        require(value >= 0) { "Xp must be >= 0, got $value" }
    }

    public operator fun plus(other: Xp): Xp = Xp(value + other.value)

    public companion object {
        public val ZERO: Xp = Xp(0)
    }
}
