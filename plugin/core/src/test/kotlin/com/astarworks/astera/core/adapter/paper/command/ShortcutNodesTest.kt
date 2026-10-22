package com.astarworks.astera.core.adapter.paper.command

import com.astarworks.astera.core.application.command.CommandShortcuts
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.brigadier.builder.RequiredArgumentBuilder.argument
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.RootCommandNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression: real 26.3 clients and Velocity rejected the command tree ("Failed to decode
 * clientbound/minecraft:commands", "Stopped cycling; the root node can't be built").
 *
 * The command packet numbers nodes by *structural* equality (`CommandNode.equals` ignores
 * redirects). A childless `/vault` shortcut redirecting to `/astera vault` is equal to its
 * target, shares its packet id and therefore redirects to itself. [packetIds] reproduces that
 * numbering; the validators mirror vanilla's `ClientboundCommandsPacket.validateEntries`.
 */
class ShortcutNodesTest {

    private val run = Command<Unit> { Command.SINGLE_SUCCESS }

    /** `/astera` with childless (menu, vault, shop) and argument-bearing (lang, queue join) targets. */
    private fun asteraTree() = literal<Unit>("astera").executes(run)
        .then(literal<Unit>("menu").executes(run))
        .then(literal<Unit>("vault").executes(run))
        .then(literal<Unit>("shop").executes(run))
        .then(literal<Unit>("lang").executes(run).then(argument<Unit, String>("language", StringArgumentType.word()).executes(run)))
        .then(literal<Unit>("queue").then(literal<Unit>("join").executes(run).then(argument<Unit, String>("mode", StringArgumentType.word()).executes(run))))
        .build()

    private fun withShortcuts(astera: CommandNode<Unit>): RootCommandNode<Unit> {
        val root = RootCommandNode<Unit>()
        root.addChild(astera)
        ShortcutNodes.build(astera, CommandShortcuts.TABLE).forEach { root.addChild(it) }
        return root
    }

    /** Node -> packet id, deduplicating structurally equal nodes exactly like the packet encoder. */
    private fun packetIds(root: CommandNode<Unit>): Map<CommandNode<Unit>, Int> {
        val ids = LinkedHashMap<CommandNode<Unit>, Int>()
        val queue = ArrayDeque<CommandNode<Unit>>().apply { add(root) }
        while (queue.isNotEmpty()) { // breadth first, as the packet encoder does: shallow nodes claim the id first
            val node = queue.removeFirst()
            if (ids.containsKey(node)) continue
            ids[node] = ids.size
            queue.addAll(node.children)
            node.redirect?.let(queue::add)
        }
        return ids
    }

    /** Vanilla/Velocity rule: a redirect target must be buildable first, so redirects must not form a cycle in packet-id space. */
    private fun assertValidPacket(root: CommandNode<Unit>) {
        val ids = packetIds(root)
        val redirectOf = HashMap<Int, Int>()
        ids.forEach { (node, id) -> node.redirect?.let { redirectOf.putIfAbsent(id, ids.getValue(it)) } }
        for (start in redirectOf.keys) {
            val seen = mutableSetOf(start)
            var next = redirectOf[start]
            while (next != null) {
                assertThat(seen.add(next)).describedAs("redirect cycle through packet node %d", start).isTrue()
                next = redirectOf[next]
            }
        }
        // children must also be resolvable bottom-up: no node may be its own descendant
        fun descends(node: CommandNode<Unit>, path: Set<Int>) {
            val id = ids.getValue(node)
            assertThat(id in path).describedAs("child cycle at %s", node.name).isFalse()
            node.children.forEach { descends(it, path + id) }
        }
        descends(root, emptySet())
    }

    @Test
    fun `shortcut tree has no self or cyclic redirect in the packet`() {
        assertValidPacket(withShortcuts(asteraTree()))
    }

    @Test
    fun `childless target gets a plain executable literal, not a redirect`() {
        val astera = asteraTree()
        val shortcuts = ShortcutNodes.build(astera, CommandShortcuts.TABLE).associateBy { it.literal }
        for (name in listOf("menu", "vault", "shop")) {
            assertThat(shortcuts.getValue(name).redirect).describedAs(name).isNull()
            assertThat(shortcuts.getValue(name).command).describedAs(name).isNotNull()
        }
    }

    @Test
    fun `target with children keeps the redirect`() {
        val astera = asteraTree()
        val shortcuts = ShortcutNodes.build(astera, CommandShortcuts.TABLE).associateBy { it.literal }
        assertThat(shortcuts.getValue("lang").redirect).isSameAs(astera.getChild("lang"))
        assertThat(shortcuts.getValue("play").redirect).isSameAs(astera.getChild("queue").getChild("join"))
    }

    @Test
    fun `shortcut whose owner plugin is absent is skipped`() {
        val astera = literal<Unit>("astera").executes(run).then(literal<Unit>("menu").executes(run)).build()
        assertThat(ShortcutNodes.build(astera, CommandShortcuts.TABLE).map { it.literal }).containsExactly("menu")
    }

    @Test
    fun `late registration merged into the live root keeps the packet valid`() {
        val root = withShortcuts(asteraTree())
        val live = root.getChild("astera")
        val late = literal<Unit>("astera")
            .then(literal<Unit>("skill").then(literal<Unit>("preview").executes(run)
                .then(argument<Unit, String>("skill-id", StringArgumentType.word()).executes(run)
                    .then(argument<Unit, String>("options", StringArgumentType.greedyString()).executes(run)
                        .then(literal<Unit>("again").executes(run))))))
            .then(literal<Unit>("menu").executes(run))
            .build()
        late.children.toList().forEach { live.addChild(it) }
        assertValidPacket(root)
    }
}
