package com.astarworks.astera.core.adapter.paper.command

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.AsteraCommandRegistry
import com.astarworks.astera.core.application.command.CommandAccess
import com.astarworks.astera.core.application.command.CommandActor
import com.astarworks.astera.core.application.command.CommandAuthorizer
import com.astarworks.astera.core.application.command.CommandInvoker
import com.astarworks.astera.core.application.command.CommandPaths
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.AssignRoleError
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.RevokeRoleError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.permission.RoleDefinition
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeScheduler
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.exceptions.CommandSyntaxException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Spec -> Brigadier translation (ADR-0184) driven by a real `CommandDispatcher` with a fake
 * source, so tree shape, suggestions, permission filtering and execution are verified without
 * a server.
 */
class BrigadierTreeBuilderTest {

    private class Source(val actor: CommandActor, val online: List<String> = listOf("alice", "bob")) {
        val replies = mutableListOf<Pair<MessageKey, Map<String, String>>>()
    }

    private val none = object : IPermissionService {
        override fun assign(playerId: PlayerId, roleId: RoleId): Result<Unit, AssignRoleError> = Result.success(Unit)
        override fun revoke(playerId: PlayerId, roleId: RoleId): Result<Unit, RevokeRoleError> = Result.success(Unit)
        override fun check(playerId: PlayerId, permission: Permission): Boolean = false
        override fun rolesOf(playerId: PlayerId): Set<RoleId> = emptySet()
        override fun definitionOf(roleId: RoleId): RoleDefinition? = null
        override fun allDefinitions(): Collection<RoleDefinition> = emptyList()
    }

    private val registry = AsteraCommandRegistry()
    private val authorizer = CommandAuthorizer { none }
    private val invoker = CommandInvoker(authorizer, FakeScheduler())
    private val executed = mutableListOf<Map<String, Any>>()

    private val env = object : BrigadierTreeBuilder.Env<Source> {
        override val authorizer: CommandAuthorizer get() = this@BrigadierTreeBuilderTest.authorizer
        override fun actor(source: Source): CommandActor = source.actor
        override fun resolve(path: List<String>): CommandSpec.Literal? = CommandPaths.find(registry.snapshot(), path)
        override fun brigadierType(type: ArgumentType) = checkNotNull(BrigadierTreeBuilder.plainType(type))
        override fun convert(source: Source, type: ArgumentType, raw: Any): Any? =
            if (type is ArgumentType.Choice) type.values.firstOrNull { it.equals(raw as? String, true) } else raw
        override fun onlinePlayerNames(source: Source): List<String> = source.online
        override fun execute(path: List<String>, node: CommandSpec.Literal, source: Source, args: Map<String, Any>) {
            runBlocking { invoker.invoke(node, source.actor, args) { k, p -> source.replies += k to p } }
        }
        override fun usage(source: Source, path: List<String>, node: CommandSpec.Literal) {
            source.replies += CommandInvoker.USAGE to mapOf("usage" to CommandPaths.usage(path, node))
        }
    }

    private fun dispatcher(): CommandDispatcher<Source> =
        CommandDispatcher<Source>().also { it.root.addChild(BrigadierTreeBuilder(env).build(registry.snapshot()).build()) }

    private fun leaf(name: String, access: CommandAccess, perm: String, vararg args: CommandSpec.Argument) =
        CommandSpec.Literal(
            name = name,
            subcommands = args.toList(),
            executor = { ctx ->
                executed += ctx.args
                ctx.reply(MessageKey("test.ran"), mapOf("who" to name))
                CommandResult.Success
            },
        ).requirePermission(Permission(perm), access)

    private val player = Source(CommandActor(PlayerId(UUID.randomUUID())))
    private val operator = Source(CommandActor(PlayerId(UUID.randomUUID()), isOperator = true))

    private fun suggestions(d: CommandDispatcher<Source>, src: Source, line: String): List<String> {
        val parse = d.parse(line, src)
        return d.getCompletionSuggestions(parse).get().list.map { it.text }
    }

    /** What a client would see below [path]: Brigadier hides children whose `requires` fails. */
    private fun visible(d: CommandDispatcher<Source>, src: Source, vararg path: String): List<String> {
        var node = d.root.getChild("astera")
        for (p in path) node = node.getChild(p)
        return node.children.filter { it.canUse(src) }.map { it.name }
    }

    private fun mountDemo() {
        registry.register(
            "Demo", listOf("items"),
            leaf(
                "give", CommandAccess.OPERATOR, "astera.items.give",
                CommandSpec.Argument("target", ArgumentType.PlayerName),
                CommandSpec.Argument("item", ArgumentType.ItemIdArg, completer = { listOf("greatsword", "dagger") }),
                CommandSpec.Argument("amount", ArgumentType.IntegerArg(min = 1, max = 64), optional = true),
            ),
        )
        registry.register(
            "Demo", listOf("queue"),
            leaf("join", CommandAccess.EVERYONE, "astera.queue.join", CommandSpec.Argument("lane", ArgumentType.Choice("norma", "custom:annihilation"))),
        )
    }

    @Test
    fun `optional trailing argument makes both arities executable`() {
        mountDemo()
        val d = dispatcher()
        d.execute("astera items give alice greatsword", operator)
        d.execute("astera items give alice dagger 5", operator)
        assertThat(executed).hasSize(2)
        assertThat(executed[0]).containsEntry("target", "alice").containsEntry("item", "greatsword").doesNotContainKey("amount")
        assertThat(executed[1]).containsEntry("amount", 5)
        assertThat(operator.replies.map { it.first.value }).containsExactly("test.ran", "test.ran")
    }

    @Test
    fun `missing mandatory argument prints usage and out of range value does not execute`() {
        mountDemo()
        val d = dispatcher()
        val before = operator.replies.size
        d.execute("astera items give alice", operator)
        assertThat(operator.replies.drop(before).single())
            .isEqualTo(CommandInvoker.USAGE to mapOf("usage" to "/astera items give <target> <item> [amount]"))
        assertThatThrownBy { d.execute("astera items give alice dagger 99", operator) }.isInstanceOf(CommandSyntaxException::class.java)
        assertThat(executed).isEmpty()
    }

    @Test
    fun `ids with a colon are accepted as one token`() {
        mountDemo()
        val d = dispatcher()
        d.execute("astera queue join custom:annihilation", player)
        assertThat(executed.single()).containsEntry("lane", "custom:annihilation")
    }

    @Test
    fun `suggestions come from literals, choices, completers and online players`() {
        mountDemo()
        val d = dispatcher()
        assertThat(visible(d, operator)).containsExactlyInAnyOrder("items", "queue")
        assertThat(suggestions(d, operator, "astera items ")).containsExactly("give")
        assertThat(suggestions(d, operator, "astera items give ")).containsExactlyInAnyOrder("alice", "bob")
        assertThat(suggestions(d, operator, "astera items give bob ")).containsExactlyInAnyOrder("greatsword", "dagger")
        assertThat(suggestions(d, operator, "astera items give bob gr")).containsExactly("greatsword")
        assertThat(suggestions(d, player, "astera queue join ")).containsExactlyInAnyOrder("norma", "custom:annihilation")
        assertThat(suggestions(d, player, "astera queue join cu")).containsExactly("custom:annihilation")
    }

    @Test
    fun `nodes the sender cannot use are hidden from completion and cannot be parsed`() {
        mountDemo()
        val d = dispatcher()
        assertThat(visible(d, player)).containsExactly("queue")
        assertThat(visible(d, operator)).containsExactlyInAnyOrder("items", "queue")
        assertThat(suggestions(d, player, "astera items give ")).isEmpty()
        assertThatThrownBy { d.execute("astera items give alice dagger", player) }.isInstanceOf(CommandSyntaxException::class.java)
        assertThat(executed).isEmpty()
    }

    @Test
    fun `a plugin that disables leaves inert nodes behind`() {
        mountDemo()
        val d = dispatcher()
        registry.unregisterOwner("Demo")
        // the skeleton node still exists in the live dispatcher, but it resolves to nothing:
        assertThat(visible(d, operator)).isEmpty()
        assertThatThrownBy { d.execute("astera items give alice dagger", operator) }.isInstanceOf(CommandSyntaxException::class.java)
        assertThat(executed).isEmpty()
    }

    @Test
    fun `late registrations merge into the live root without disturbing existing nodes`() {
        mountDemo()
        val d = dispatcher()
        registry.register("Late", leaf("status", CommandAccess.EVERYONE, "astera.late.status").copy(name = "late"))
        // before the merge the dispatcher does not know the new area
        assertThat(visible(d, player)).doesNotContain("late")
        // the bridge's republish step: build the fresh tree and merge its children into the live root
        val live = d.root.getChild("astera")
        BrigadierTreeBuilder(env).build(registry.snapshot()).build().children.toList().forEach { live.addChild(it) }
        assertThat(visible(d, player)).containsExactlyInAnyOrder("queue", "late")
        d.execute("astera late", player)
        d.execute("astera queue join norma", player)
        assertThat(executed).hasSize(2)
    }

    @Test
    fun `executor failures are replied through the invoker`() {
        registry.register(
            "Demo",
            CommandSpec.Literal(name = "boom", executor = { CommandResult.Failure(MessageKey("test.failed"), mapOf("x" to "1")) })
                .requirePermission(Permission("astera.demo.boom"), CommandAccess.EVERYONE),
        )
        val d = dispatcher()
        d.execute("astera boom", player)
        assertThat(player.replies).containsExactly(MessageKey("test.failed") to mapOf("x" to "1"))
    }

    @Test
    fun `a group literal without executor prints usage instead of a generic error`() {
        mountDemo()
        val d = dispatcher()
        d.execute("astera queue", player)
        assertThat(player.replies.single().first).isEqualTo(CommandInvoker.USAGE)
        assertThat(executed).isEmpty()
    }

    @Test
    fun `tooltips ride along with the matching suggestions`() {
        registry.register(
            "Demo", listOf("queue"),
            leaf(
                "join", CommandAccess.EVERYONE, "astera.queue.join",
                CommandSpec.Argument(
                    "lane", ArgumentType.StringArg(),
                    completer = { listOf("norma", "annihilation") },
                    tooltips = { mapOf("annihilation" to "2/4") },
                ),
            ),
        )
        val d = dispatcher()
        val list = d.getCompletionSuggestions(d.parse("astera queue join ", player)).get().list
        assertThat(list.map { it.text }).containsExactlyInAnyOrder("norma", "annihilation")
        assertThat(list.first { it.text == "annihilation" }.tooltip?.string).isEqualTo("2/4")
        assertThat(list.first { it.text == "norma" }.tooltip).isNull()
    }
}
