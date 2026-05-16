package com.astarworks.astera.domain.model.stage

@JvmInline
public value class StageId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "StageId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
