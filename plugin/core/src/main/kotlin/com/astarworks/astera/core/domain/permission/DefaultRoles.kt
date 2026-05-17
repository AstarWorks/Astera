package com.astarworks.astera.core.domain.permission

/** Built-in role definitions seeded into [InMemoryPermissionService] on startup. */
public object DefaultRoles {

    public val ADMIN: RoleDefinition = RoleDefinition(
        id = RoleId("admin"),
        displayName = "Administrator",
        permissions = setOf(Permission("*")),
    )

    public val MODERATOR: RoleDefinition = RoleDefinition(
        id = RoleId("moderator"),
        displayName = "Moderator",
        permissions = setOf(
            Permission("astera.weapons.give"),
            Permission("astera.match.start"),
            Permission("astera.match.stop"),
            Permission("astera.stats.view"),
            Permission("astera.economy.view"),
        ),
    )

    public val PLAYER: RoleDefinition = RoleDefinition(
        id = RoleId("player"),
        displayName = "Player",
        permissions = setOf(
            Permission("astera.stats.view"),
        ),
    )

    public fun builtin(): Set<RoleDefinition> = setOf(ADMIN, MODERATOR, PLAYER)
}
