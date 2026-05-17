package com.astarworks.astera.stats.domain.level

@JvmInline
public value class Level(public val value: Int) {
    init {
        require(value >= 1) { "Level must be >= 1, got $value" }
    }

    public companion object {
        public val ONE: Level = Level(1)
    }
}
