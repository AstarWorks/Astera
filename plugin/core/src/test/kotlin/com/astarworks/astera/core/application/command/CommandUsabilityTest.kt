package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Pure tests for the usability helpers of ADR-0234: usage block, shortcuts, confirm guard. */
class CommandUsabilityTest {

    private class MapRenderer(private val texts: Map<String, String>) : IMessageRenderer {
        override fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): String =
            texts[key.value] ?: key.value

        override fun renderLore(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): List<String> =
            listOf(render(playerId, key, placeholders))

        override fun renderText(playerId: PlayerId?, text: LocalizedText, placeholders: Map<String, String>): String = ""

        override fun renderLocalizedLore(
            playerId: PlayerId?,
            lore: LocalizedLore,
            placeholders: Map<String, String>,
        ): List<String> = emptyList()
    }

    private val join = CommandSpec.Literal(
        name = "join",
        subcommands = listOf(CommandSpec.Argument("lane", ArgumentType.StringArg())),
        executor = { CommandResult.Success },
    )

    @Test
    fun `usage block carries syntax description and example when the language files have them`() {
        val renderer = MapRenderer(
            mapOf(
                "astera.command.help.queue-join" to "Join a lane",
                "astera.command.example.queue-join" to "/astera queue join norma",
            ),
        )
        val replies = CommandUsage.replies(listOf("queue", "join"), join, emptyList(), renderer, null)
        assertThat(replies.map { it.first }).containsExactly(
            CommandInvoker.USAGE,
            CommandUsage.DESCRIPTION,
            CommandUsage.EXAMPLE,
        )
        assertThat(replies[0].second["usage"]).isEqualTo("/astera queue join <lane>")
        assertThat(replies[2].second["example"]).isEqualTo("/astera queue join norma")
    }

    @Test
    fun `missing description and example are left out and sub-commands are listed sorted`() {
        val group = CommandSpec.Literal(name = "queue", subcommands = listOf(join))
        val replies = CommandUsage.replies(listOf("queue"), group, listOf(join), MapRenderer(emptyMap()), null)
        assertThat(replies.map { it.first }).containsExactly(CommandInvoker.USAGE, CommandUsage.SUB)
        assertThat(replies[1].second["subs"]).isEqualTo("join")
    }

    @Test
    fun `shortcuts expand to the full path and leave other lines alone`() {
        assertThat(CommandShortcuts.expand(listOf("play", "norma"))).containsExactly("queue", "join", "norma")
        assertThat(CommandShortcuts.expand(listOf("PLAY"))).containsExactly("queue", "join")
        assertThat(CommandShortcuts.expand(listOf("lang", "ja"))).containsExactly("lang", "ja")
        assertThat(CommandShortcuts.expand(listOf("queue", "leave"))).containsExactly("queue", "leave")
        assertThat(CommandShortcuts.expand(emptyList())).isEmpty()
    }

    private fun ctx(sender: PlayerId?, vararg args: Pair<String, Any>) =
        CommandContext(sender = sender, args = mapOf(*args), scheduler = FakeScheduler())

    @Test
    fun `confirm guard asks players but never the console or a confirmed player`() {
        val player = PlayerId(UUID.randomUUID())
        val asked = CommandConfirm.guard(ctx(player), "/astera match abort m-1")
        assertThat(asked?.messageKey).isEqualTo(CommandConfirm.REQUIRED)
        assertThat(asked?.placeholders).containsEntry("command", "/astera match abort m-1 --confirm")
        assertThat(CommandConfirm.guard(ctx(player, "confirm" to "--confirm"), "/astera match abort m-1")).isNull()
        assertThat(CommandConfirm.guard(ctx(null), "/astera match abort m-1")).isNull()
    }
}
