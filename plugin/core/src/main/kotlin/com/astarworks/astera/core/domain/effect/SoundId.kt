package com.astarworks.astera.core.domain.effect

/**
 * Stable identifier for a sound, vendor-neutral. Accepts vanilla namespaced
 * keys (`minecraft:entity.lightning_bolt.thunder`), custom resource-pack keys
 * (`astera:skill/fireball/cast`), and plain dotted ids
 * (`entity.player.levelup`).
 *
 * Allowed characters: `[a-z0-9_/.:-]`, must begin with a lowercase letter.
 * The colon, dot, and slash carve out the vanilla / pack / category cases.
 *
 * Wave _skills-2 / ADR-0077. Separate from [SoundSpec] (which adds runtime
 * volume / pitch); SoundId is the catalog identifier referenced by a
 * `Mechanic.PlaySound`.
 */
@JvmInline
public value class SoundId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "SoundId must start lowercase + [a-z0-9_/.:-]: '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9_/.:-]*$")
    }
}
