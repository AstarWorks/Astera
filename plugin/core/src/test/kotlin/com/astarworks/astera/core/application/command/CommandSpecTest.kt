package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Pure DSL tests. No Paper harness — these run with `:plugin:application:check`
 * and prove the spec contract is enforceable from the application layer alone
 * (silly-knuth §6.1 E acceptance: "domain/application tests must run without
 * any Paper harness").
 */
class CommandSpecTest {

    @Test
    fun `literal validates its name`() {
        assertThatThrownBy {
            CommandSpec.Literal(name = "  ", executor = { CommandResult.Success })
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            CommandSpec.Literal(name = "Astera", executor = { CommandResult.Success })
        }.isInstanceOf(IllegalArgumentException::class.java) // uppercase rejected
    }

    @Test
    fun `literal must be reachable (has executor or subcommands)`() {
        assertThatThrownBy {
            CommandSpec.Literal(name = "astera") // no executor, no subcommands
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `integer arg rejects inverted bounds`() {
        assertThatThrownBy {
            ArgumentType.IntegerArg(min = 10, max = 1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `arguments and literals are partitioned correctly`() {
        val spec = CommandSpec.Literal(
            name = "astera",
            description = MessageKey("astera.command.root"),
            subcommands = listOf(
                CommandSpec.Literal(name = "give", executor = { CommandResult.Success }),
                CommandSpec.Literal(name = "reload", executor = { CommandResult.Success }),
            ),
        )
        assertThat(spec.arguments).isEmpty()
        assertThat(spec.literals).hasSize(2)
        assertThat(spec.literals.map { it.name }).containsExactly("give", "reload")
    }

    @Test
    fun `mixed argument+literal subcommands are addressable separately`() {
        val spec = CommandSpec.Literal(
            name = "astera",
            subcommands = listOf(
                CommandSpec.Literal(
                    name = "give",
                    subcommands = listOf(
                        CommandSpec.Argument("target", ArgumentType.PlayerSelector),
                        CommandSpec.Argument("weapon", ArgumentType.ItemIdArg),
                    ),
                    executor = { CommandResult.Success },
                ),
            ),
        )

        val give = spec.literals.single()
        assertThat(give.arguments).hasSize(2)
        assertThat(give.arguments.map { it.name }).containsExactly("target", "weapon")
        assertThat(give.literals).isEmpty()
    }

    @Test
    fun `command context reads typed arguments`() {
        val playerId = PlayerId(java.util.UUID.randomUUID())
        val ctx = CommandContext(
            sender = playerId,
            args = mapOf("count" to 7, "label" to "hello"),
            scheduler = NoopScheduler,
        )

        assertThat(ctx.isConsole).isFalse()
        assertThat(ctx.required<Int>("count")).isEqualTo(7)
        assertThat(ctx.required<String>("label")).isEqualTo("hello")
        assertThat(ctx.optional<Int>("missing")).isNull()
    }

    @Test
    fun `command context required throws on type mismatch`() {
        val ctx = CommandContext(sender = null, args = mapOf("count" to "seven"), scheduler = NoopScheduler)
        assertThatThrownBy { ctx.required<Int>("count") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `astera give sample tree builds`() {
        // Scenario from silly-knuth §6.1 E: /astera give <target> <weapon-id>
        val spec = CommandSpec.Literal(
            name = "astera",
            description = MessageKey("astera.command.root"),
            executor = { CommandResult.Success },
            subcommands = listOf(
                CommandSpec.Literal(
                    name = "give",
                    description = MessageKey("astera.command.give"),
                    subcommands = listOf(
                        CommandSpec.Argument("target", ArgumentType.PlayerSelector),
                        CommandSpec.Argument(
                            name = "weapon",
                            type = ArgumentType.ItemIdArg,
                            completer = { CompleterStub.weaponIds(it.remaining) },
                        ),
                    ),
                    executor = { _ -> CommandResult.Success },
                ),
            ),
        )

        assertThat(spec.literals).hasSize(1)
        val give = spec.literals.single()
        assertThat(give.arguments).hasSize(2)
        assertThat(give.arguments[1].completer).isNotNull
    }

    private object NoopScheduler : com.astarworks.astera.core.application.port.outbound.IScheduler {
        override fun currentTick(): Long = 0L
        override fun runLater(
            delay: kotlin.time.Duration,
            block: () -> Unit,
        ): com.astarworks.astera.core.application.port.outbound.IScheduler.TaskHandle = error("not used")
        override fun runRepeating(
            initialDelay: kotlin.time.Duration,
            period: kotlin.time.Duration,
            block: () -> Unit,
        ): com.astarworks.astera.core.application.port.outbound.IScheduler.TaskHandle = error("not used")
        override fun runAsync(
            block: () -> Unit,
        ): com.astarworks.astera.core.application.port.outbound.IScheduler.TaskHandle = error("not used")
        override fun scheduleAt(
            tickAbsolute: Long,
            block: () -> Unit,
        ): com.astarworks.astera.core.application.port.outbound.IScheduler.TaskHandle = error("not used")
        override suspend fun awaitTicks(ticks: Long) = Unit
        override val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        override val asyncDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    }

    private object CompleterStub {
        fun weaponIds(prefix: String): List<String> =
            listOf("example-sword", "example-bow").filter { it.startsWith(prefix) }
    }
}
