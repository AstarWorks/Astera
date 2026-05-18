package com.astarworks.astera.combat.domain.armor

/** Stable identifier of an [ArmorSpec]. Mirrors `SkillId` conventions: kebab-case, namespace-free. */
@JvmInline
public value class ArmorId(public val value: String) {
    init {
        require(value.isNotBlank()) { "ArmorId must not be blank" }
        require(value.matches(VALID)) { "ArmorId must be lower kebab-case (a-z, 0-9, -): '$value'" }
    }

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
