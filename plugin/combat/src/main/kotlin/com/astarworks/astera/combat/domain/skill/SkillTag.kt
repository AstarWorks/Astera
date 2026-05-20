package com.astarworks.astera.combat.domain.skill

/**
 * Free-form classifier on a [SkillSpec] — `damage`, `fire`, `aoe`,
 * `crowd-control`, `ult`, etc. Tags are used by content filters, UI
 * grouping, status-immunity rules, and gameplay analytics; the engine
 * itself does not gate on tags.
 *
 * Lowercase kebab-case to match the rest of Astera's id namespace. Free of
 * a closed enum so designers can introduce new categories without a code
 * change.
 *
 * Wave _skills-2 / ADR-0077.
 */
@JvmInline
public value class SkillTag(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "SkillTag must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
