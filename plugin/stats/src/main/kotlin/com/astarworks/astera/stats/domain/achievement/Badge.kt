package com.astarworks.astera.stats.domain.achievement

import com.astarworks.astera.core.domain.i18n.MessageKey

@JvmInline
public value class BadgeId(public val value: String) {
    init {
        require(VALID.matches(value)) { "BadgeId must be lower-kebab-case: '$value'" }
    }
    override fun toString(): String = value
    public companion object { private val VALID = Regex("^[a-z][a-z0-9-]*$") }
}

/**
 * Inline icon a player may display in lobbies / scoreboards (Wave 5e).
 *
 * Like [Title] but renders as an item icon rather than text. Awarded
 * through achievement chains.
 */
public data class Badge(
    val id: BadgeId,
    val displayKey: MessageKey,
    val iconItemId: String,
    val unlockAchievementId: AchievementId?,
)
