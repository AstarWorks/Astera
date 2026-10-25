package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.inbound.IAsteraCommandRegistry
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.domain.permission.Permission

/**
 * Host-neutral [IAsteraCommandRegistry]: collects the subtrees plugins mount
 * under `/astera` and merges them into one [CommandSpec.Literal] root.
 *
 * Pure Kotlin: the Paper adapter (`PaperCommandBridge`) and the headless
 * simulator both consume [snapshot].
 *
 * Merge rules ([snapshot]):
 *  - literals with the same name at the same level are merged recursively,
 *    so `admin` can be contributed to by Core, Items, Match and Economy;
 *  - at most one contribution may supply an executor for a path;
 *  - at most one contribution may supply the argument chain of a node.
 * Violations throw [IllegalArgumentException] from [register] (the tree is left
 * unchanged), so a clashing plugin fails at enable instead of at command time.
 */
public class AsteraCommandRegistry : IAsteraCommandRegistry {

    private class Entry(val owner: String, val parent: List<String>, val spec: CommandSpec.Literal)

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private val listeners = mutableListOf<() -> Unit>()

    /**
     * Executor of the bare `/astera` (usage overview). Set by Core wiring once
     * the message renderer exists; until then the root has no executor of its own.
     */
    @Volatile
    public var rootExecutor: (suspend (CommandContext) -> CommandResult)? = null
        set(value) {
            field = value
            synchronized(lock) { cached = null }
            notifyListeners()
        }

    override fun register(owner: String, parent: List<String>, spec: CommandSpec.Literal): ServiceRegistry.Registration {
        val entry = Entry(owner, parent, spec)
        synchronized(lock) {
            val candidate = entries + entry
            build(candidate) // validates; throws before committing
            entries += entry
            cached = null
        }
        notifyListeners()
        return object : ServiceRegistry.Registration {
            override fun unregister() {
                val removed = synchronized(lock) { entries.remove(entry).also { cached = null } }
                if (removed) notifyListeners()
            }

            override val isActive: Boolean get() = synchronized(lock) { entry in entries }
        }
    }

    override fun unregisterOwner(owner: String) {
        val removed = synchronized(lock) { entries.removeAll { it.owner == owner }.also { cached = null } }
        if (removed) notifyListeners()
    }

    private var cached: CommandSpec.Literal? = null

    override fun snapshot(): CommandSpec.Literal = synchronized(lock) {
        cached ?: build(entries.toList()).also { cached = it }
    }

    override fun addListener(listener: () -> Unit): ServiceRegistry.Registration {
        synchronized(lock) { listeners += listener }
        return object : ServiceRegistry.Registration {
            override fun unregister() {
                synchronized(lock) { listeners -= listener }
            }

            override val isActive: Boolean get() = synchronized(lock) { listener in listeners }
        }
    }

    /** Owners that currently contribute, for diagnostics. */
    public fun owners(): Set<String> = synchronized(lock) { entries.map { it.owner }.toSet() }

    private fun notifyListeners() {
        val copy = synchronized(lock) { listeners.toList() }
        copy.forEach { runCatching { it() } }
    }

    // ---- merge ---------------------------------------------------------------

    private class Node(val name: String) {
        var executor: (suspend (CommandContext) -> CommandResult)? = null
        var permission: Permission? = null
        var access: CommandAccess = CommandAccess.OPERATOR
        var arguments: List<CommandSpec.Argument> = emptyList()
        val children = LinkedHashMap<String, Node>()
    }

    private fun build(all: List<Entry>): CommandSpec.Literal {
        val root = Node(ROOT_NAME)
        for (e in all) {
            var cursor = root
            for (segment in e.parent) cursor = cursor.children.getOrPut(segment) { Node(segment) }
            merge(cursor, e.spec, "/$ROOT_NAME ${e.parent.joinToString(" ")}".trimEnd())
        }
        return toLiteral(root, isRoot = true)
    }

    private fun merge(parent: Node, spec: CommandSpec.Literal, path: String) {
        val here = "$path ${spec.name}"
        val node = parent.children.getOrPut(spec.name) { Node(spec.name) }
        if (spec.executor != null) {
            require(node.executor == null) { "Command path '$here' is claimed by two executors" }
            node.executor = spec.executor
            node.permission = spec.permission
            node.access = spec.access
        } else if (spec.permission != null && node.permission == null) {
            node.permission = spec.permission
            node.access = spec.access
        }
        if (spec.arguments.isNotEmpty()) {
            require(node.arguments.isEmpty()) { "Command path '$here' declares its arguments twice" }
            node.arguments = spec.arguments
        }
        for (child in spec.literals) merge(node, child, here)
    }

    private fun toLiteral(node: Node, isRoot: Boolean): CommandSpec.Literal {
        val executor = if (isRoot) rootExecutor ?: { CommandResult.Success } else node.executor
        val subs = node.arguments + node.children.values.map { toLiteral(it, isRoot = false) }
        return CommandSpec.Literal(
            name = node.name,
            executor = executor,
            subcommands = subs,
            permission = if (isRoot) ROOT_PERMISSION else node.permission,
            access = if (isRoot) CommandAccess.EVERYONE else node.access,
        )
    }

    public companion object {
        public const val ROOT_NAME: String = "astera"
        public val ROOT_PERMISSION: Permission = Permission("astera.help.use")
    }
}
