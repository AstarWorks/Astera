package com.astarworks.astera.core.domain.permission

/** Unique identifier for a role, e.g. "admin", "moderator", "player". */
@JvmInline
public value class RoleId(public val value: String) {
    init {
        require(value.isNotBlank()) { "RoleId must not be blank" }
    }
}
