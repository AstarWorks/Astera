package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.domain.i18n.MessageKey

/** Naming conventions derived from a node's position in the `/astera` tree (ADR-0184). */
public object CommandPaths {

    /**
     * i18n key of the one-line description of the node at [path] (segments
     * below the root, e.g. `["queue", "join"]`). Flat on purpose: a node can be
     * both a leaf and a parent, which a nested YAML map cannot express.
     * `["queue", "join"]` -> `astera.command.help.queue-join`.
     */
    public fun descriptionKey(path: List<String>): MessageKey =
        MessageKey("astera.command.help." + path.joinToString("-"))

    /** `/astera queue join <lane> [party]` — the usage line of [node] at [path]. */
    public fun usage(path: List<String>, node: CommandSpec.Literal): String {
        val sb = StringBuilder("/").append(AsteraCommandRegistry.ROOT_NAME)
        path.forEach { sb.append(' ').append(it) }
        node.arguments.forEach { arg ->
            sb.append(' ').append(if (arg.optional) "[" else "<").append(arg.name).append(if (arg.optional) "]" else ">")
        }
        return sb.toString()
    }

    /** The node at [path] below [root] (empty path = [root]), or `null`. */
    public fun find(root: CommandSpec.Literal, path: List<String>): CommandSpec.Literal? {
        var node = root
        for (segment in path) node = node.literals.firstOrNull { it.name == segment } ?: return null
        return node
    }

    /** Depth-first walk of every literal node below (and excluding) [root] with its path. */
    public fun walk(root: CommandSpec.Literal, visit: (path: List<String>, node: CommandSpec.Literal) -> Unit) {
        fun go(path: List<String>, node: CommandSpec.Literal) {
            for (child in node.literals) {
                val childPath = path + child.name
                visit(childPath, child)
                go(childPath, child)
            }
        }
        go(emptyList(), root)
    }
}
