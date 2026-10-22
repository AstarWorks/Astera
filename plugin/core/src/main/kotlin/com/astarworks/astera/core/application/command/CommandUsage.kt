package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * The reply block shown when a command line did not resolve to a runnable node
 * (ADR-0232): the expected syntax, what the command does, the sub-commands to
 * pick from, and a copy-paste example when the language files carry one
 * (`astera.command.example.<path joined by '-'>`).
 */
public object CommandUsage {

    public val SUB: MessageKey = MessageKey("astera.command.usage-sub")
    public val DESCRIPTION: MessageKey = MessageKey("astera.command.usage-description")
    public val EXAMPLE: MessageKey = MessageKey("astera.command.usage-example")

    public fun exampleKey(path: List<String>): MessageKey =
        MessageKey("astera.command.example." + path.joinToString("-"))

    /**
     * @param visibleChildren sub-commands of [node] the actor may see (already filtered).
     * @param renderer used to look up the description and the optional example for [viewer].
     */
    public fun replies(
        path: List<String>,
        node: CommandSpec.Literal,
        visibleChildren: List<CommandSpec.Literal>,
        renderer: IMessageRenderer,
        viewer: PlayerId?,
    ): List<Pair<MessageKey, Map<String, String>>> {
        val out = mutableListOf<Pair<MessageKey, Map<String, String>>>()
        out += CommandInvoker.USAGE to mapOf("usage" to CommandPaths.usage(path, node))
        if (path.isNotEmpty()) {
            val descKey = CommandPaths.descriptionKey(path)
            val description = renderer.render(viewer, descKey)
            if (description != descKey.value) out += DESCRIPTION to mapOf("description" to description)
        }
        if (visibleChildren.isNotEmpty()) {
            out += SUB to mapOf("subs" to visibleChildren.map { it.name }.sorted().joinToString(" | "))
        }
        if (path.isNotEmpty()) {
            val key = exampleKey(path)
            val example = renderer.render(viewer, key)
            if (example != key.value) out += EXAMPLE to mapOf("example" to example)
        }
        return out
    }
}
