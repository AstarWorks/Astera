package com.astarworks.astera.core.adapter.paper.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode

/**
 * Root-level shortcut literals (`/menu`, `/play`, ... ADR-0234) for a built `/astera` tree.
 *
 * Generic in the Brigadier source type so the shape can be verified without a server.
 *
 * A shortcut redirects into the one tree. The exception is a target with no children: the
 * client-bound command packet de-duplicates nodes by *structural* equality (Brigadier
 * `CommandNode.equals` ignores redirects), so a childless, same-named, same-command redirect
 * node is equal to its own target, collapses into the same packet entry, and then redirects
 * to itself. The vanilla client ("impossible command tree") and Velocity ("Stopped cycling")
 * reject that. Such a shortcut is emitted as a plain executable literal instead, which is
 * indistinguishable for a childless target.
 */
internal object ShortcutNodes {

    /** Shortcut nodes for every [table] entry whose target exists below [root]; absent targets are skipped. */
    fun <S> build(
        root: CommandNode<S>,
        table: Map<String, List<String>>,
    ): List<LiteralCommandNode<S>> = table.mapNotNull { (name, target) ->
        var found: CommandNode<S>? = root
        for (segment in target) found = found?.getChild(segment)
        val node = found ?: return@mapNotNull null // owner plugin not loaded: the shortcut does not exist either
        val shortcut = LiteralArgumentBuilder.literal<S>(name)
            .requires(node.requirement)
            .executes(node.command)
        if (node.children.isNotEmpty()) shortcut.redirect(node)
        shortcut.build()
    }
}
