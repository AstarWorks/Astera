package com.astarworks.astera.core.application.admin.panel

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandAuthorizer
import com.astarworks.astera.core.application.command.CommandInvoker
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.admin.InMemoryAdminTextPrompt
import com.astarworks.astera.core.application.service.DefaultGuiIntentRouter
import com.astarworks.astera.core.application.service.InMemoryAuditLogRepository
import com.astarworks.astera.core.application.service.admin.InMemoryAdminPlayerGateway
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeAdminPermissionService
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AdminCommandConsoleTest {

    private val admin = FakeMcPlayer(PlayerId(UUID.randomUUID()), "admin")
    private val rookie = FakeMcPlayer(PlayerId(UUID.randomUUID()), "rookie")
    private val permissions = FakeAdminPermissionService(mutableSetOf(admin.id))
    private val audit = InMemoryAuditLogRepository()
    private val gui = FakeGuiAdapter()
    private val registry = InMemoryAdminPanelRegistry()
    private val players = InMemoryAdminPlayerGateway().apply {
        join(admin.id, "admin")
        join(rookie.id, "rookie")
    }
    private val prompt = InMemoryAdminTextPrompt()
    private val router = DefaultGuiIntentRouter { gui }
    private val guard = AdminPanelGuard(permissions, FakeMessageRenderer(), audit, isOperator = players::isOperator)
    private val calls = mutableListOf<String>()
    private val authorizer = CommandAuthorizer { permissions }

    private val tree = CommandSpec.Literal(
        name = "astera",
        subcommands = listOf(
            CommandSpec.Literal(
                name = "mod",
                subcommands = listOf(
                    CommandSpec.Literal(
                        name = "ban",
                        subcommands = listOf(
                            CommandSpec.Argument("player", ArgumentType.PlayerName),
                            CommandSpec.Argument("reason-key", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
                        ),
                        executor = { ctx ->
                            calls += "ban ${ctx.required<String>("player")} ${ctx.required<String>("reason-key")}"
                            ctx.reply(MessageKey("test.banned"), mapOf("player" to ctx.required<String>("player")))
                            CommandResult.Success
                        },
                    ).requirePermission(Permission("astera.mod.ban")),
                ),
            ),
            CommandSpec.Literal(
                name = "economy",
                subcommands = listOf(
                    CommandSpec.Literal(
                        name = "grant",
                        subcommands = listOf(
                            CommandSpec.Argument("player", ArgumentType.PlayerName),
                            CommandSpec.Argument("currency", ArgumentType.Choice("gold", "gem")),
                            CommandSpec.Argument("amount", ArgumentType.IntegerArg(min = 1, max = 10_000)),
                        ),
                        executor = { ctx ->
                            calls += "grant ${ctx.required<String>("player")} ${ctx.required<String>("currency")} ${ctx.required<Int>("amount")}"
                            CommandResult.Failure(MessageKey("test.denied-by-rule"))
                        },
                    ).requirePermission(Permission("astera.admin.currency.grant")),
                ),
            ),
        ),
    )

    private val console = AdminCommandConsole(
        guard = guard,
        registry = registry,
        guiAdapter = gui,
        players = players,
        messages = FakeMessageRenderer(),
        prompt = { prompt },
        root = { tree },
        invoker = { CommandInvoker(authorizer, FakeScheduler()) },
        authorizer = { authorizer },
        scheduler = FakeScheduler(),
        transfers = { null },
        lobbyRepaster = { null },
    ).also { it.install(router) }

    private fun click(who: FakeMcPlayer, key: String, vararg payload: Pair<String, String>) =
        router.dispatch(who, GuiIntent(key, payload.toMap()))

    private fun intents(spec: GuiSpec): List<GuiIntent> =
        spec.slots.values.filterIsInstance<GuiSlot.Static>().flatMap { listOfNotNull(it.onLeftClick, it.onRightClick) }

    private fun findIntent(who: FakeMcPlayer, key: String, predicate: (GuiIntent) -> Boolean = { true }): GuiIntent =
        intents(checkNotNull(gui.currentSpec(who))).first { it.key == key && predicate(it) }

    @Test
    fun `categories list only what the viewer can run on this server`() {
        click(admin, AdminCommandConsole.OPEN)
        val spec = checkNotNull(gui.currentSpec(admin))
        assertThat(spec.id).isEqualTo(AdminCommandConsole.CATS_ID)
        val cats = intents(spec).filter { it.key == AdminCommandConsole.CATEGORY }.map { it.payload["cat"] }
        assertThat(cats).containsExactly("moderation", "economy")
    }

    @Test
    fun `non-admin clicks open nothing and run nothing`() {
        click(rookie, AdminCommandConsole.OPEN)
        click(rookie, AdminCommandConsole.FORM, "a" to "mod ban")
        click(rookie, AdminCommandConsole.RUN, "confirm" to "1")
        assertThat(gui.currentSpec(rookie)).isNull()
        assertThat(calls).isEmpty()
        assertThat(rookie.messages).hasSize(3).allMatch { it.contains("astera.admin.panel.denied") }
    }

    @Test
    fun `permission is re-checked on every click`() {
        click(admin, AdminCommandConsole.FORM, "a" to "mod ban", "target" to rookie.id.toString())
        permissions.admins.remove(admin.id)
        click(admin, AdminCommandConsole.RUN, "confirm" to "1")
        assertThat(calls).isEmpty()
    }

    @Test
    fun `ban form prefills the target, takes a reason preset, asks to confirm, runs and audits`() {
        click(admin, AdminCommandConsole.FORM, "a" to "mod ban", "target" to rookie.id.toString())
        assertThat(checkNotNull(gui.currentSpec(admin)).id).isEqualTo(AdminCommandConsole.FORM_ID)

        // The reason picker offers the preset buttons; pick the first one.
        click(admin, AdminCommandConsole.PICK, "arg" to "reason-key")
        val reason = findIntent(admin, AdminCommandConsole.SET) { it.payload["v"]?.endsWith("reason.spam") == true }
        assertThat(reason.payload).containsEntry("r", "1")
        click(admin, reason.key, *reason.payload.map { it.key to it.value }.toTypedArray())

        // Destructive: first run only shows the confirm page.
        click(admin, AdminCommandConsole.RUN)
        assertThat(checkNotNull(gui.currentSpec(admin)).id).isEqualTo(AdminCommandConsole.CONFIRM_ID)
        assertThat(calls).isEmpty()

        click(admin, AdminCommandConsole.RUN, "confirm" to "1")
        assertThat(calls).containsExactly("ban rookie astera.admin.panel.cmd.reason.spam")
        assertThat(checkNotNull(gui.currentSpec(admin)).id).isEqualTo(AdminCommandConsole.RESULT_ID)
        assertThat(admin.messages.last()).contains("test.banned")
        val logged = audit.recent(10).filterIsInstance<AuditEvent.AdminActionPerformed>().single()
        assertThat(logged.action).isEqualTo("cmd:mod ban")
        assertThat(logged.actor).isEqualTo(admin.id)
        assertThat(logged.detail).contains("ok")
    }

    @Test
    fun `running with a missing required argument does not execute`() {
        click(admin, AdminCommandConsole.FORM, "a" to "economy grant")
        click(admin, AdminCommandConsole.RUN)
        assertThat(calls).isEmpty()
        assertThat(checkNotNull(gui.currentSpec(admin)).id).isEqualTo(AdminCommandConsole.FORM_ID)
        assertThat(admin.messages.last()).contains("form.run.missing")
    }

    @Test
    fun `typed amounts go through the chat prompt, are validated and a failure is reported`() {
        click(admin, AdminCommandConsole.FORM, "a" to "economy grant", "target" to rookie.id.toString())
        click(admin, AdminCommandConsole.SET, "arg" to "currency", "v" to "gold")

        click(admin, AdminCommandConsole.TYPE, "arg" to "amount")
        assertThat(prompt.isWaiting(admin.id)).isTrue()
        prompt.submit(admin.id, "99999")
        assertThat(admin.messages.last()).contains("astera.admin.panel.cmd.invalid")

        click(admin, AdminCommandConsole.TYPE, "arg" to "amount")
        prompt.submit(admin.id, "250")
        click(admin, AdminCommandConsole.RUN)

        assertThat(calls).containsExactly("grant rookie gold 250")
        val outcome = checkNotNull(gui.currentSpec(admin))
        assertThat(outcome.id).isEqualTo(AdminCommandConsole.RESULT_ID)
        assertThat(outcome.slots.values.filterIsInstance<GuiSlot.Static>().any { it.itemId.value == "indicator-off" }).isTrue()
        assertThat(admin.messages.last()).contains("test.denied-by-rule")
    }

    @Test
    fun `cancelling a prompt returns to the form without changing it`() {
        click(admin, AdminCommandConsole.FORM, "a" to "economy grant")
        click(admin, AdminCommandConsole.TYPE, "arg" to "amount")
        prompt.submit(admin.id, "cancel")
        assertThat(checkNotNull(gui.currentSpec(admin)).id).isEqualTo(AdminCommandConsole.FORM_ID)
        click(admin, AdminCommandConsole.RUN)
        assertThat(calls).isEmpty()
    }

    @Test
    fun `the player page lists actions that take a player and passes the target`() {
        click(admin, AdminCommandConsole.PLAYER, AdminPanelUi.PAYLOAD_TARGET to rookie.id.toString())
        val spec = checkNotNull(gui.currentSpec(admin))
        assertThat(spec.id).isEqualTo(AdminCommandConsole.PLAYER_PAGE_ID)
        val forms = intents(spec).filter { it.key == AdminCommandConsole.FORM }
        assertThat(forms.map { it.payload["a"] }).containsExactlyInAnyOrder("mod ban", "economy grant")
        assertThat(forms).allMatch { it.payload["target"] == rookie.id.toString() }
    }

    @Test
    fun `every catalog entry has a unique path and a known category`() {
        val paths = AdminCommandCatalog.defaults().map { it.path }
        assertThat(paths).doesNotHaveDuplicates()
        assertThat(AdminCommandCatalog.defaults().filter { !it.isSpecial }.map { it.segments.size }).allMatch { it in 2..3 }
    }
}
