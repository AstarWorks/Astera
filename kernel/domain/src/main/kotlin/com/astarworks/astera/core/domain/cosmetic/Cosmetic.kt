package com.astarworks.astera.core.domain.cosmetic

import com.astarworks.astera.core.domain.i18n.MessageKey

/** What a [Cosmetic] changes. One of each kind can be equipped at a time. */
public enum class CosmeticKind {
    /** A line of text shown before the name in chat, the tab list and the profile. */
    TITLE,

    /** The colour of the player's name in chat and the tab list (lobby; a match keeps the team colour). */
    NAME_COLOR,

    /** A particle burst at the place an opponent was killed. */
    KILL_EFFECT,
}

/**
 * One entry of `content/cosmetics/` (ECO-02 / ECO-04): purely visual, never a stat.
 *
 * Ownership is the permanent entitlement [unlockId] (`title:<id>` / `cosmetic:<id>`), the same
 * namespace level rewards and achievements grant into. [priceGold] is the GOLD price in the
 * cosmetics shop; `null` means the cosmetic is only granted (level / achievement), never sold.
 *
 * [value] depends on the kind: unused for titles, a named colour or `#RRGGBB` for name colours,
 * a vanilla particle key for kill effects (emitted [count] times).
 */
public data class Cosmetic(
    val id: String,
    val kind: CosmeticKind,
    val nameKey: MessageKey,
    val priceGold: Long? = null,
    val value: String = "",
    val count: Int = 12,
) {
    init {
        require(ID_PATTERN.matches(id)) { "cosmetic id must be kebab-case: '$id'" }
        require(priceGold == null || priceGold > 0) { "cosmetic '$id' price must be positive or absent" }
        require(count in 1..64) { "cosmetic '$id' count must be in 1..64" }
        if (kind != CosmeticKind.TITLE) require(value.isNotBlank()) { "cosmetic '$id' needs a value" }
    }

    /** The entitlement string stored in `player_unlocks`. */
    val unlockId: String get() = if (kind == CosmeticKind.TITLE) "title:$id" else "cosmetic:$id"

    public companion object {
        public val ID_PATTERN: Regex = Regex("[a-z][a-z0-9-]*")
    }
}

/** What a player has equipped, by cosmetic id; `null` = nothing in that slot. */
public data class CosmeticLoadout(
    val title: String? = null,
    val nameColor: String? = null,
    val killEffect: String? = null,
) {
    public fun idOf(kind: CosmeticKind): String? = when (kind) {
        CosmeticKind.TITLE -> title
        CosmeticKind.NAME_COLOR -> nameColor
        CosmeticKind.KILL_EFFECT -> killEffect
    }

    public fun with(kind: CosmeticKind, id: String?): CosmeticLoadout = when (kind) {
        CosmeticKind.TITLE -> copy(title = id)
        CosmeticKind.NAME_COLOR -> copy(nameColor = id)
        CosmeticKind.KILL_EFFECT -> copy(killEffect = id)
    }

    public companion object {
        public val EMPTY: CosmeticLoadout = CosmeticLoadout()
    }
}
