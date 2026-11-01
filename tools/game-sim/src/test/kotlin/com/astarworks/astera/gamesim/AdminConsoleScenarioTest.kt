package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.admin.panel.AdminCommandConsole
import com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry
import com.astarworks.astera.core.application.command.CommandAccess
import com.astarworks.astera.core.application.command.CommandPaths
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.admin.IAdminPlayerGateway
import com.astarworks.astera.core.application.service.admin.InMemoryAdminPlayerGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.ServerRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0233: the admin panel's command console against the real wired object graph.
 *
 * The parity test is the contract of the whole feature: every operator leaf of the live `/astera`
 * tree must have a registered panel action. A new operator command fails this test until it gets an
 * [AdminCommandCatalog][com.astarworks.astera.core.application.admin.panel.AdminCommandCatalog] entry,
 * or an allowlist entry here with a written reason.
 */
class AdminConsoleScenarioTest {

    /** Operator leaves that deliberately have no console action. Each needs a reason. */
    private val allowlist: Map<String, String> = mapOf(
        "admin panel" to "it opens the panel itself; the console is reached from the panel hub, there is nothing to run inside it",
    )

    private fun operatorLeaves(sim: GameSim): List<String> {
        val out = mutableListOf<String>()
        CommandPaths.walk(sim.commandTree) { path, node ->
            if (node.executor != null && node.access == CommandAccess.OPERATOR) out += path.joinToString(" ")
        }
        return out
    }

    private fun parityProblems(sim: GameSim): List<String> {
        val registry = sim.services.getOrThrow<IAdminPanelRegistry>()
        return operatorLeaves(sim)
            .filter { it !in allowlist && registry.actionFor(it) == null }
            .map { "operator command '/astera $it' has no admin panel action (add it to AdminCommandCatalog)" }
    }

    private fun ScenarioScope.makeAdmin(entity: SimEntity) {
        val r = sim.services.getOrThrow<IPermissionService>().assign(entity.id, RoleId("admin"))
        check(r is Result.Success) { "assign admin: $r" }
    }

    private fun ScenarioScope.roster(vararg who: SimEntity) {
        val gateway = sim.services.getOrThrow<IAdminPlayerGateway>() as InMemoryAdminPlayerGateway
        who.forEach { gateway.join(it.id, it.name) }
    }

    private fun ScenarioScope.click(who: SimEntity, key: String, vararg payload: Pair<String, String>) {
        val view = checkNotNull(sim.server.view(who.id))
        sim.services.getOrThrow<IGuiIntentRouter>().dispatch(view, GuiIntent(key, payload.toMap()))
    }

    private fun ScenarioScope.spec(who: SimEntity) =
        checkNotNull(sim.services.getOrThrow<IGuiAdapter>().currentSpec(checkNotNull(sim.server.view(who.id)))) { "no gui open" }

    private fun ScenarioScope.intents(who: SimEntity): List<GuiIntent> =
        spec(who).slots.values.filterIsInstance<GuiSlot.Static>().flatMap { listOfNotNull(it.onLeftClick, it.onRightClick) }

    @Test
    fun `every operator command has a registered panel action`() {
        scenario("admin-console-parity") {
            val problems = parityProblems(sim).toMutableList()
            val leaves = operatorLeaves(sim)
            val registry = sim.services.getOrThrow<IAdminPanelRegistry>()

            // No stale entries: an action or allowlist line whose command no longer exists is a bug too.
            registry.actions().filter { !it.isSpecial && it.path !in leaves }
                .forEach { problems += "panel action '${it.path}' points at a command that does not exist" }
            allowlist.keys.filter { it !in leaves }
                .forEach { problems += "allowlist entry '$it' is stale" }
            allowlist.forEach { (path, reason) -> assertThat(reason).`as`("reason for $path").hasSizeGreaterThan(20) }

            // Presets and the player argument must name real arguments of their command.
            registry.actions().filter { !it.isSpecial }.forEach { action ->
                val node = CommandPaths.find(sim.commandTree, action.segments)
                val names = node?.arguments?.map { it.name }.orEmpty()
                action.presets.keys.filter { it !in names }
                    .forEach { problems += "'${action.path}': preset for unknown argument '$it'" }
                if (action.playerArg != null && action.playerArg !in names) {
                    problems += "'${action.path}': playerArg '${action.playerArg}' is not an argument"
                }
            }
            assertThat(problems).`as`("admin panel parity").isEmpty()
        }
    }

    @Test
    fun `parity holds on a lobby backend and a game backend too`() {
        val lobby = GameSim.boot("admin-console-lobby", role = ServerRole.LOBBY, serverName = ServerName("lobby"))
        val game = GameSim.boot("admin-console-game", role = ServerRole.GAME, serverName = ServerName("game"))
        try {
            assertThat(parityProblems(lobby)).isEmpty()
            assertThat(parityProblems(game)).isEmpty()
            assertThat(operatorLeaves(lobby)).contains("admin health")
            assertThat(operatorLeaves(game)).contains("admin health")
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }

    @Test
    fun `every command action opens a form with at most nine argument rows`() {
        scenario("admin-console-forms") {
            val admin = player("admin")
            makeAdmin(admin)
            roster(admin)
            val registry = sim.services.getOrThrow<IAdminPanelRegistry>()
            val opened = mutableListOf<String>()
            for (action in registry.actions().filter { !it.isSpecial }) {
                click(admin, AdminCommandConsole.FORM, "a" to action.path)
                val s = spec(admin)
                assertThat(s.id).`as`(action.path).isEqualTo(AdminCommandConsole.FORM_ID)
                val rows = (9..17).count { s.slotAt(com.astarworks.astera.core.domain.gui.SlotIndex(it)) is GuiSlot.Static }
                val args = CommandPaths.find(sim.commandTree, action.segments)!!.arguments.size
                assertThat(args).`as`("${action.path} arguments").isLessThanOrEqualTo(9)
                assertThat(rows).`as`("${action.path} rows").isEqualTo(args)
                opened += action.path
            }
            assertThat(opened).contains("mod ban", "economy grant", "items give", "match start", "admin reload", "admin rewire", "admin audit")
        }
    }

    @Test
    fun `a player without the panel permission gets nothing from the console`() {
        scenario("admin-console-denied") {
            val rookie = player("rookie")
            val before = checkNotNull(sim.server.view(rookie.id)).givenItems.toList()
            click(rookie, AdminCommandConsole.OPEN)
            click(rookie, AdminCommandConsole.FORM, "a" to "items give", "target" to rookie.id.toString())
            click(rookie, AdminCommandConsole.RUN, "confirm" to "1")
            assertThat(sim.services.getOrThrow<IGuiAdapter>().currentSpec(checkNotNull(sim.server.view(rookie.id)))).isNull()
            assertThat(checkNotNull(sim.server.view(rookie.id)).givenItems).containsExactlyElementsOf(before)
        }
    }

    @Test
    fun `admin gives an item to another player through the console and the run is audited`() {
        scenario("admin-console-items-give") {
            val admin = player("admin")
            val bob = player("bob")
            makeAdmin(admin)
            roster(admin, bob)
            val before = checkNotNull(sim.server.view(bob.id)).givenItems.toList()

            click(admin, AdminCommandConsole.FORM, "a" to "items give", "target" to bob.id.toString())
            // Search narrows the completer's item list to the one wanted (player picker -> value picker).
            click(admin, AdminCommandConsole.PICK, "arg" to "item", "q" to "greatsword")
            val choose = intents(admin).first { it.key == AdminCommandConsole.SET && it.payload["v"] == "greatsword" }
            click(admin, choose.key, *choose.payload.map { it.key to it.value }.toTypedArray())
            click(admin, AdminCommandConsole.RUN)
            advance(2) // the run is dispatched on the player's entity scope, one tick later

            val given = checkNotNull(sim.server.view(bob.id)).givenItems.drop(before.size)
            assertThat(given).contains("greatsword")
            assertThat(spec(admin).id).isEqualTo(AdminCommandConsole.RESULT_ID)
            val logged = sim.services.getOrThrow<IAuditLogRepository>().recent(50)
                .filterIsInstance<AuditEvent.AdminActionPerformed>().filter { it.action == "cmd:items give" }
            assertThat(logged).hasSize(1)
            assertThat(logged.single().actor).isEqualTo(admin.id)
        }
    }

    @Test
    fun `a read-only command shows its output on the result page`() {
        scenario("admin-console-health") {
            val admin = player("admin")
            makeAdmin(admin)
            roster(admin)
            click(admin, AdminCommandConsole.FORM, "a" to "admin health")
            click(admin, AdminCommandConsole.RUN)
            advance(2) // the run is dispatched on the player's entity scope, one tick later
            val s = spec(admin)
            assertThat(s.id).isEqualTo(AdminCommandConsole.RESULT_ID)
            val lines = s.slots.values.filterIsInstance<GuiSlot.Static>()
                .filter { it.displayName?.key?.value == "astera.admin.panel.cmd.result.line" }
            assertThat(lines).isNotEmpty()
        }
    }

    @Test
    fun `a destructive command asks for confirmation and does nothing until confirmed`() {
        scenario("admin-console-confirm") {
            val admin = player("admin")
            makeAdmin(admin)
            roster(admin)
            click(admin, AdminCommandConsole.FORM, "a" to "admin maintenance")
            click(admin, AdminCommandConsole.SET, "arg" to "action", "v" to "on")
            click(admin, AdminCommandConsole.RUN)
            advance(2) // the run is dispatched on the player's entity scope, one tick later
            assertThat(spec(admin).id).isEqualTo(AdminCommandConsole.CONFIRM_ID)
            val audit = sim.services.getOrThrow<IAuditLogRepository>()
            assertThat(audit.recent(50).filterIsInstance<AuditEvent.AdminActionPerformed>().filter { it.action == "cmd:admin maintenance" }).isEmpty()

            click(admin, AdminCommandConsole.RUN, "confirm" to "1")
            advance(2)
            assertThat(spec(admin).id).isEqualTo(AdminCommandConsole.RESULT_ID)
            assertThat(audit.recent(50).filterIsInstance<AuditEvent.AdminActionPerformed>().filter { it.action == "cmd:admin maintenance" }).hasSize(1)
        }
    }
}
