package com.astarworks.astera.core.domain.permission

/** Immutable specification of what a role is and what permissions it grants. */
public data class RoleDefinition(
    val id: RoleId,
    val displayName: String,
    val permissions: Set<Permission>,
)
