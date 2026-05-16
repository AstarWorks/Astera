package com.astarworks.astera.domain.model.match

@JvmInline
public value class TeamId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "TeamId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
