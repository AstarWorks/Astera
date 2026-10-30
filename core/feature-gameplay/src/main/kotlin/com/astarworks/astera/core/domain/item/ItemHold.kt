package com.astarworks.astera.core.domain.item

/** What a weapon does to a target's movement (the weapon hold table). Shown as one tooltip row; the skills implement it. */
public enum class HoldKind(public val key: String) {
    NONE("none"),
    KNOCKBACK("knockback"),
    PULL("pull"),
    KNOCKBACK_PULL("knockback_pull"),
    KNOCKBACK_STOP("knockback_stop"),
    SLOW("slow"),
    ROOT("root"),
    FREEZE("freeze"),
    SHOCK("shock"),
    ;

    public companion object {
        public fun fromKey(key: String): HoldKind? = entries.firstOrNull { it.key == key }
    }
}

public enum class HoldLevel(public val key: String) {
    LIGHT("light"),
    MEDIUM("medium"),
    HEAVY("heavy"),
    ;

    public companion object {
        public fun fromKey(key: String): HoldLevel? = entries.firstOrNull { it.key == key }
    }
}

/** The hold-table entry of an item: a kind with an optional strength, either a [level] or a duration in [ticks] (never both; none has neither). */
public data class ItemHold(val kind: HoldKind, val level: HoldLevel? = null, val ticks: Int? = null) {
    init {
        require(level == null || ticks == null) { "ItemHold: give a level or ticks, not both" }
        require(kind != HoldKind.NONE || (level == null && ticks == null)) { "ItemHold: none has no level or ticks" }
        require(ticks == null || ticks > 0) { "ItemHold.ticks must be positive" }
    }
}
