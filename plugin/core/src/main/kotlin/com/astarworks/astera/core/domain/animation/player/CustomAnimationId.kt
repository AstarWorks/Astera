package com.astarworks.astera.core.domain.animation.player

/**
 * Registry id for a custom player animation (Wave 5animation, ADR-0113).
 *
 * Lower-kebab-case (a-z, 0-9, -) — same regex shape as
 * [com.astarworks.astera.core.domain.item.ItemId] / [com.astarworks.astera.core.domain.match.MatchId] so the
 * project-wide id naming is uniform. Phase 4 cosmetic emote system will
 * register entries (`/wave`, `/dance`, `/bow`, …); the Wave 5animation
 * foundation ships the id type only.
 *
 * Examples:
 *  - `astera:wave-emote` — not valid (colon)
 *  - `wave-emote` — valid
 *  - `dance-disco` — valid
 *  - `Wave` — not valid (uppercase)
 */
@JvmInline
public value class CustomAnimationId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "CustomAnimationId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
