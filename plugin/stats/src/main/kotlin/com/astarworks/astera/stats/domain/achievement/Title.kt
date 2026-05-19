package com.astarworks.astera.stats.domain.achievement

import com.astarworks.astera.core.domain.i18n.MessageKey

@JvmInline
public value class TitleId(public val value: String) {
    init {
        require(VALID.matches(value)) { "TitleId must be lower-kebab-case: '$value'" }
    }
    override fun toString(): String = value
    public companion object { private val VALID = Regex("^[a-z][a-z0-9-]*$") }
}

/**
 * Cosmetic title a player may display under their name (Wave 5e).
 *
 * Awarded through achievement chains (Worker γ). Phase A defines the
 * value type only — equip / display logic in Wave 6a.
 */
public data class Title(
    val id: TitleId,
    val displayKey: MessageKey,
    val unlockAchievementId: AchievementId?,
)
