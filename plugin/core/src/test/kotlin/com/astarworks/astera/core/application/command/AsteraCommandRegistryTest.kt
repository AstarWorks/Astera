package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.domain.permission.Permission
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AsteraCommandRegistryTest {

    private fun leaf(name: String, vararg args: CommandSpec.Argument) =
        CommandSpec.Literal(name = name, subcommands = args.toList(), executor = { CommandResult.Success })
            .requirePermission(Permission("astera.test.$name"), CommandAccess.EVERYONE)

    @Test
    fun `contributions to the same area merge and keep each owner's leaves`() {
        val registry = AsteraCommandRegistry()
        registry.register("A", listOf("admin"), leaf("health"))
        registry.register("B", listOf("admin"), leaf("reload"))
        registry.register("B", leaf("shop"))

        val root = registry.snapshot()
        assertThat(root.name).isEqualTo("astera")
        assertThat(root.literals.map { it.name }).containsExactly("admin", "shop")
        assertThat(CommandPaths.find(root, listOf("admin"))!!.literals.map { it.name }).containsExactly("health", "reload")
    }

    @Test
    fun `nested subtrees are merged level by level`() {
        val registry = AsteraCommandRegistry()
        registry.register("A", listOf("admin"), CommandSpec.Literal(name = "stage", subcommands = listOf(leaf("list"))))
        registry.register("B", listOf("admin"), CommandSpec.Literal(name = "stage", subcommands = listOf(leaf("select"))))
        val stage = CommandPaths.find(registry.snapshot(), listOf("admin", "stage"))!!
        assertThat(stage.literals.map { it.name }).containsExactly("list", "select")
    }

    @Test
    fun `two executors for one path are rejected and leave the tree untouched`() {
        val registry = AsteraCommandRegistry()
        registry.register("A", listOf("queue"), leaf("join"))
        val before = registry.snapshot()
        assertThatThrownBy { registry.register("B", listOf("queue"), leaf("join")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("/astera queue join")
        assertThat(registry.snapshot()).isEqualTo(before)
    }

    @Test
    fun `argument chains cannot be declared twice for one node`() {
        val registry = AsteraCommandRegistry()
        registry.register("A", CommandSpec.Literal(name = "x", subcommands = listOf(CommandSpec.Argument("a", ArgumentType.PlayerName), leaf("one"))))
        assertThatThrownBy {
            registry.register("B", CommandSpec.Literal(name = "x", subcommands = listOf(CommandSpec.Argument("b", ArgumentType.PlayerName), leaf("two"))))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unregister by handle and by owner remove only that owner's nodes`() {
        val registry = AsteraCommandRegistry()
        val a = registry.register("A", listOf("admin"), leaf("health"))
        registry.register("B", listOf("admin"), leaf("reload"))
        a.unregister()
        assertThat(a.isActive).isFalse()
        assertThat(CommandPaths.find(registry.snapshot(), listOf("admin"))!!.literals.map { it.name }).containsExactly("reload")
        registry.unregisterOwner("B")
        assertThat(registry.snapshot().literals).isEmpty()
        assertThat(registry.owners()).isEmpty()
    }

    @Test
    fun `listeners hear every change and can be removed`() {
        val registry = AsteraCommandRegistry()
        var changes = 0
        val handle = registry.addListener { changes++ }
        val reg = registry.register("A", leaf("one"))
        reg.unregister()
        registry.rootExecutor = { CommandResult.Success }
        assertThat(changes).isEqualTo(3)
        handle.unregister()
        registry.register("A", leaf("two"))
        assertThat(changes).isEqualTo(3)
    }

    @Test
    fun `the root carries the help permission and the bare executor`() {
        val registry = AsteraCommandRegistry()
        val exec: suspend (CommandContext) -> CommandResult = { CommandResult.Success }
        registry.rootExecutor = exec
        val root = registry.snapshot()
        assertThat(root.executor).isSameAs(exec)
        assertThat(root.permission).isEqualTo(AsteraCommandRegistry.ROOT_PERMISSION)
        assertThat(root.access).isEqualTo(CommandAccess.EVERYONE)
    }

    @Test
    fun `a mandatory argument after an optional one is rejected`() {
        assertThatThrownBy {
            CommandSpec.Literal(
                name = "bad",
                subcommands = listOf(
                    CommandSpec.Argument("a", ArgumentType.PlayerName, optional = true),
                    CommandSpec.Argument("b", ArgumentType.PlayerName),
                ),
                executor = { CommandResult.Success },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
