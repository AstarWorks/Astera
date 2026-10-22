package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.command.CommandPaths
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.CommandTreeRunner
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.services.getOrThrow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0184: the one `/astera` tree, walked and exercised through the very registry, authorizer
 * and invoker the Paper Brigadier bridge uses, with every plugin's real wiring mounted.
 */
class CommandTreeTest {

    private val permissionNode = Regex("^astera(\\.[a-z0-9_-]+){2,}$")

    private fun leaves(sim: GameSim): List<Pair<List<String>, CommandSpec.Literal>> {
        val out = mutableListOf<Pair<List<String>, CommandSpec.Literal>>()
        CommandPaths.walk(sim.commandTree) { path, node -> if (node.executor != null) out += path to node }
        return out
    }

    private fun areas(sim: GameSim): Map<String, List<String>> =
        sim.commandTree.literals.associate { area ->
            val actions = mutableListOf<String>()
            CommandPaths.walk(area) { path, node -> if (node.executor != null) actions += path.joinToString(" ") }
            area.name to (if (area.executor != null) listOf("") else emptyList()) + actions
        }

    @Test
    fun `the tree has exactly the documented areas and no hyphenated roots`() {
        scenario("command-tree-areas") {
            val tree = areas(sim)
            assertThat(tree.keys).containsExactlyInAnyOrder(
                "help", "menu", "travel", "vault", "lang", "volume", "friend", "party", "chat", "tutorial",
                "news", "patch", "report", "queue", "match", "items", "shop", "economy", "stats", "mod", "admin",
            )
            assertThat(tree.getValue("queue")).containsExactlyInAnyOrder("join", "leave", "status")
            assertThat(tree.getValue("match")).contains(
                "start", "end", "list", "phase", "entry", "abort", "cancel", "restart",
                "replay start", "replay stop", "replay export",
            )
            assertThat(tree.getValue("items")).containsExactly("give")
            assertThat(tree.getValue("economy")).containsExactlyInAnyOrder("balance", "grant", "withdraw", "transfer")
            assertThat(tree.getValue("stats")).containsExactlyInAnyOrder("show", "leaderboard", "xp grant")
            assertThat(tree.getValue("admin")).contains(
                "maintenance", "teleport", "reload", "audit", "promote", "demote", "config", "health",
                "spawn-mob", "backup", "restart", "announce", "panel", "stage list", "stage select", "validate",
                "feedback list", "feedback triage", "slow-query report",
            )
            assertThat(tree.getValue("mod")).contains("kick", "mute", "unmute", "ban", "tempban", "unban", "warn")
            assertThat(tree.keys.none { it.startsWith("astera-") }).isTrue()
        }
    }

    @Test
    fun `every leaf has a permission an executor and an i18n description`() {
        scenario("command-tree-contract") {
            val renderer = sim.services.getOrThrow<IMessageRenderer>()
            val problems = mutableListOf<String>()
            val all = leaves(sim)
            assertThat(all.size).isGreaterThan(60)
            for ((path, node) in all) {
                val label = "/astera " + path.joinToString(" ")
                val perm = node.permission
                if (perm == null) problems += "$label: no permission"
                else if (!permissionNode.matches(perm.id)) problems += "$label: permission '${perm.id}' not astera.<area>.<action>"
                if (CommandPaths.usage(path, node).isBlank()) problems += "$label: no usage"
            }
            // Description of every node (leaf or area), flat key astera.command.help.<path-joined-by-dash>.
            CommandPaths.walk(sim.commandTree) { path, _ ->
                val key = CommandPaths.descriptionKey(path)
                if (renderer.render(null, key) == key.value) problems += "/astera ${path.joinToString(" ")}: missing i18n ${key.value}"
            }
            assertThat(problems).`as`("command tree contract violations").isEmpty()
        }
    }

    @Test
    fun `basic player commands are open to everyone and admin ones are operator only`() {
        scenario("command-tree-access") {
            val byPath = leaves(sim).associate { (path, node) -> path.joinToString(" ") to node }
            for (open in listOf("queue join", "queue leave", "queue status", "shop", "vault",
                "lang", "friend add", "party invite", "match list", "stats show", "economy balance", "help", "menu")) {
                val node = byPath[open] ?: error("missing $open")
                assertThat(node.access.name).`as`(open).isEqualTo("EVERYONE")
            }
            for (admin in listOf("items give", "match start", "economy grant", "admin health", "admin reload", "mod ban", "admin maintenance")) {
                val node = byPath[admin] ?: error("missing $admin")
                assertThat(node.access.name).`as`(admin).isEqualTo("OPERATOR")
            }
        }
    }

    @Test
    fun `completion hides what the sender may not use`() {
        scenario("command-tree-completion") {
            player("alice")
            val plain = sim.complete("alice", "")
            assertThat(plain).contains("queue", "help", "shop", "vault", "match")
            assertThat(plain).doesNotContain("admin", "mod", "items")
            assertThat(sim.complete("alice", "economy ")).containsExactly("balance")
            assertThat(sim.complete("alice", "match ")).containsExactlyInAnyOrder("list")
            assertThat(sim.complete("alice", "admin ")).isEmpty()

            val op = sim.complete("alice", "", operator = true)
            assertThat(op).contains("admin", "mod", "items", "economy", "queue")
            assertThat(sim.complete("alice", "admin ", operator = true)).contains("health", "reload", "maintenance")
        }
    }

    @Test
    fun `arguments complete with ids from the live registries`() {
        scenario("command-tree-suggestions") {
            player("alice")
            player("bob")
            assertThat(sim.complete("alice", "queue join ")).contains("norma", "annihilation", "break-core")
            assertThat(sim.complete("alice", "queue join an")).containsExactly("annihilation")
            assertThat(sim.complete("alice", "items give ", operator = true)).containsExactlyInAnyOrder("alice", "bob")
            assertThat(sim.complete("alice", "items give bob ", operator = true)).contains("greatsword", "dagger")
            assertThat(sim.complete("alice", "admin config ", operator = true)).containsExactlyInAnyOrder("get", "set")
            assertThat(sim.complete("alice", "admin maintenance ", operator = true)).containsExactlyInAnyOrder("on", "off", "status")
            assertThat(sim.complete("alice", "lang ")).contains("en", "ja")
            assertThat(sim.complete("alice", "admin reload ", operator = true)).isNotEmpty()
            assertThat(sim.complete("alice", "match start ", operator = true)).contains("example-arena")
        }
    }

    @Test
    fun `help lists only what the sender may use`() {
        scenario("command-tree-help") {
            player("alice")
            val renderer = sim.services.getOrThrow<IMessageRenderer>()
            val plain = sim.command("alice", "help")
            assertThat(plain.succeeded).isTrue()
            val areaLines = plain.replies.filter { it.first.value == "astera.command.help.area-line" }.map { it.second["area"] }
            assertThat(areaLines).contains("queue", "shop").doesNotContain("admin", "mod")

            val bare = sim.command("alice", "")
            assertThat(bare.succeeded).isTrue()
            assertThat(bare.replies.map { it.first.value }).contains("astera.command.help.header")

            val queueHelp = sim.command("alice", "help queue")
            val usages = queueHelp.replies.filter { it.first.value == "astera.command.help.line" }.map { it.second["usage"] }
            assertThat(usages).containsExactlyInAnyOrder("/astera queue join <lane>", "/astera queue leave", "/astera queue status")
            val rendered = queueHelp.replies.filter { it.first.value == "astera.command.help.line" }
                .map { renderer.render(null, it.first, it.second) }
            assertThat(rendered).allSatisfy { assertThat(it).doesNotContain("astera.command.help.") }

            // admin area is not even known to a plain player
            val denied = sim.command("alice", "help admin")
            assertThat(denied.succeeded).isFalse()
            assertThat(sim.command("alice", "help admin", operator = true).succeeded).isTrue()
        }
    }

    @Test
    fun `queue join leave and status execute and reply`() {
        scenario("command-queue-exec") {
            player("alice")
            val join = sim.command("alice", "queue join norma")
            assertThat(join.succeeded).`as`(join.replies.toString()).isTrue()
            assertThat(join.replies.map { it.first.value }).contains("astera.match.queue.join.success")

            val status = sim.command("alice", "queue status")
            assertThat(status.succeeded).isTrue()
            assertThat(status.replies.map { it.first.value }).contains("astera.match.queue.status.line")

            val again = sim.command("alice", "queue join norma")
            assertThat(again.succeeded).isFalse()
            assertThat(again.replies.map { it.first.value }).contains("astera.match.queue.join.already-queued")

            val leave = sim.command("alice", "queue leave")
            assertThat(leave.succeeded).isTrue()
            assertThat(leave.replies.map { it.first.value }).contains("astera.match.queue.dequeue.success")
            assertThat(sim.command("alice", "queue leave").replies.map { it.first.value })
                .contains("astera.match.queue.dequeue.not-enqueued")

            // a bare lane key and the wire form both work
            sim.command("alice", "queue leave")
            assertThat(sim.command("alice", "queue join annihilation").succeeded).isTrue()
            sim.command("alice", "queue leave")
            assertThat(sim.command("alice", "queue join custom:annihilation").succeeded).isTrue()
            sim.command("alice", "queue leave")

            val bogus = sim.command("alice", "queue join nonsense")
            assertThat(bogus.replies.map { it.first.value }).contains("astera.match.queue.unknown-mode")
        }
    }

    @Test
    fun `items give delivers with and without the optional amount and reports errors`() {
        scenario("command-items-give") {
            val alice = player("alice")
            val denied = sim.command("alice", "items give alice greatsword")
            assertThat(denied.succeeded).isFalse()
            // hidden from a plain player: the line does not resolve, so they get the usage of the area
            assertThat(denied.outcome).isInstanceOf(CommandTreeRunner.Outcome.Usage::class.java)

            val ok = sim.command("alice", "items give alice greatsword", operator = true)
            assertThat(ok.succeeded).`as`(ok.replies.toString()).isTrue()
            assertThat(ok.replies.single().first.value).isEqualTo("astera.items.command.give.success")
            assertThat(ok.replies.single().second).containsEntry("amount", "1")
            assertThat(sim.server.view(alice.id)!!.givenItems).isNotEmpty()

            val withAmount = sim.command("alice", "items give alice dagger 5", operator = true)
            assertThat(withAmount.replies.single().second).containsEntry("amount", "5")

            val missing = sim.command("alice", "items give alice no-such-item", operator = true)
            assertThat(missing.succeeded).isFalse()
            assertThat(missing.replies.single().first.value).isEqualTo("astera.items.command.give.not_found")

            val console = sim.consoleCommand("items give alice greatsword")
            assertThat(console.succeeded).isTrue()
            assertThat(console.replies.single().first.value).isEqualTo("astera.items.command.give.success")
        }
    }

    @Test
    fun `match list replies even with no match and lists a started one`() {
        scenario("command-match-list") {
            player("alice")
            val none = sim.command("alice", "match list")
            assertThat(none.succeeded).isTrue()
            assertThat(none.replies.single().first.value).isEqualTo("astera.command.reply.match-list-none")

            val start = sim.command("alice", "match start example-arena", operator = true)
            assertThat(start.succeeded).`as`(start.replies.toString()).isTrue()
            val list = sim.command("alice", "match list")
            assertThat(list.replies.map { it.first.value }).contains("astera.command.reply.match-list-header", "astera.command.reply.match-list-line")
        }
    }

    @Test
    fun `permission denial and usage errors are replied not swallowed`() {
        scenario("command-denial-usage") {
            player("alice")
            // a hidden node is "unknown" to a plain player: usage reply of the closest node
            val unknown = sim.command("alice", "admin health")
            assertThat(unknown.outcome).isInstanceOf(CommandTreeRunner.Outcome.Usage::class.java)
            assertThat(unknown.replies.single().first.value).isEqualTo("astera.command.usage")

            val missingArg = sim.command("alice", "queue join")
            assertThat(missingArg.outcome).isInstanceOf(CommandTreeRunner.Outcome.Usage::class.java)
            assertThat(missingArg.replies.single().second["usage"]).isEqualTo("/astera queue join <lane>")

            val health = sim.command("alice", "admin health", operator = true)
            assertThat(health.succeeded).isTrue()
            assertThat(health.replies.single().first.value).isEqualTo("astera.command.reply.admin-health")

            val badAction = sim.command("alice", "admin maintenance sideways", operator = true)
            assertThat(badAction.outcome).isInstanceOf(CommandTreeRunner.Outcome.Usage::class.java)
        }
    }

    @Test
    fun `economy grant and balance round trip through the real ledger`() {
        scenario("command-economy") {
            player("alice")
            val grant = sim.command("alice", "economy grant alice gold 50", operator = true)
            assertThat(grant.succeeded).`as`(grant.replies.toString()).isTrue()
            val balance = sim.command("alice", "economy balance gold")
            assertThat(balance.succeeded).isTrue()
            assertThat(balance.replies.single().second["currency"]).isEqualTo("GOLD")
            assertThat(balance.replies.single().second["amount"]!!.toLong()).isGreaterThanOrEqualTo(50L)
        }
    }

    @Test
    fun `late registration shows up and removal hides it again`() {
        scenario("command-late-registration") {
            val registry = sim.core.commandRegistry
            val before = registry.snapshot().literals.map { it.name }
            assertThat(before).doesNotContain("late")
            val handle = registry.register(
                "Test-Late",
                CommandSpec.Literal(
                    name = "late",
                    executor = { com.astarworks.astera.core.application.command.CommandResult.Success },
                ),
            )
            assertThat(registry.snapshot().literals.map { it.name }).contains("late")
            handle.unregister()
            assertThat(registry.snapshot().literals.map { it.name }).doesNotContain("late")
            registry.register("Test-Late", CommandSpec.Literal(name = "late2", executor = { com.astarworks.astera.core.application.command.CommandResult.Success }))
            registry.unregisterOwner("Test-Late")
            assertThat(registry.snapshot().literals.map { it.name }).doesNotContain("late2")
        }
    }

    @Test
    fun `two executors claiming one path are rejected without changing the tree`() {
        scenario("command-path-clash") {
            val registry = sim.core.commandRegistry
            val before = registry.snapshot()
            val clash = runCatching {
                registry.register(
                    "Test-Clash",
                    listOf("queue"),
                    CommandSpec.Literal(name = "join", executor = { com.astarworks.astera.core.application.command.CommandResult.Success }),
                )
            }
            assertThat(clash.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(registry.snapshot()).isEqualTo(before)
        }
    }
}
