package com.astarworks.astera.core.application.command

/**
 * Who holds a command node's permission when nobody granted it explicitly
 * (ADR-0184). Mirrors Bukkit's `PermissionDefault.TRUE` / `OP`.
 */
public enum class CommandAccess {
    /** Basic player commands (queue, shop, vault, friend, ...). Everyone may run and see it. */
    EVERYONE,

    /** Admin commands: operators, the console, and holders of the permission node (role or platform). */
    OPERATOR,
}
