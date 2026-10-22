package com.astarworks.astera.core.adapter.paper.command

import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandActor
import com.astarworks.astera.core.application.command.CommandAuthorizer
import com.astarworks.astera.core.application.command.CommandInvoker
import com.astarworks.astera.core.application.command.CommandPaths
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.port.inbound.IAsteraCommandRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * Publishes the merged `/astera` tree of an [IAsteraCommandRegistry] to Paper's
 * Brigadier (ADR-0184). Replaces the former per-root `PaperCommandHandler`.
 *
 * Publication has two paths so timing never decides whether a command exists:
 *
 *  1. Paper's `LifecycleEvents.COMMANDS` (server start and `/reload`): the
 *     whole current snapshot is built and registered as the `astera` root.
 *  2. Registry change after that (a feature plugin enabling later, a plugin
 *     disabling): the freshly built tree is merged into the live root node of
 *     the dispatcher (`addChild` is additive) and the command tree is re-sent
 *     to online players. Because execution and visibility re-resolve the node
 *     by path from the live registry ([BrigadierTreeBuilder.Env.resolve]), a
 *     disabled plugin's leftover Brigadier nodes are inert until the next
 *     `COMMANDS` event rebuilds the tree.
 */
public class PaperCommandBridge(
    private val plugin: JavaPlugin,
    private val registry: IAsteraCommandRegistry,
    override val authorizer: CommandAuthorizer,
    private val invoker: CommandInvoker,
    private val scheduler: IScheduler,
    private val renderer: () -> IMessageRenderer,
) : BrigadierTreeBuilder.Env<CommandSourceStack> {

    private val scope = CoroutineScope(SupervisorJob() + scheduler.mainDispatcher)
    private val builder = BrigadierTreeBuilder(this)

    @Volatile
    private var dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>? = null

    /** Subscribe to Paper's COMMANDS event and to registry changes. Call once, during Core's `onEnable`. */
    public fun start() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            dispatcher = registrar.dispatcher
            val root = registry.snapshot()
            registrar.register(
                plugin.pluginMeta,
                builder.build(root).build(),
                "Astera commands: /astera help",
                emptyList(),
            )
        }
        registry.addListener { scheduler.runLater(50.milliseconds) { republish() } }
    }

    /** Merge the current tree into the live dispatcher root (late registration path). */
    private fun republish() {
        val live = dispatcher?.root?.getChild(com.astarworks.astera.core.application.command.AsteraCommandRegistry.ROOT_NAME)
            ?: return // COMMANDS has not fired yet; it will publish the full snapshot
        runCatching {
            val fresh = builder.build(registry.snapshot()).build()
            fresh.children.toList().forEach { live.addChild(it) }
            plugin.server.onlinePlayers.forEach { it.updateCommands() }
        }.onFailure { plugin.logger.warning("Could not republish /astera command tree: ${it.message}") }
    }

    // ---- BrigadierTreeBuilder.Env ---------------------------------------------

    override fun actor(source: CommandSourceStack): CommandActor {
        val sender = source.sender
        val player = (source.executor ?: sender) as? Player
        return CommandActor(
            playerId = player?.let { PlayerId(it.uniqueId) },
            isOperator = sender.isOp || sender !is Player,
            platformHas = { node -> sender.hasPermission(node) },
        )
    }

    override fun resolve(path: List<String>): CommandSpec.Literal? = CommandPaths.find(registry.snapshot(), path)

    override fun brigadierType(type: ArgumentType): com.mojang.brigadier.arguments.ArgumentType<*> {
        if (type is ArgumentType.PlayerSelector) return ArgumentTypes.player()
        val plain = checkNotNull(BrigadierTreeBuilder.plainType(type)) { "No Brigadier type for $type" }
        // The vanilla client only knows vanilla argument types: our free-form token (ids such as
        // `astera:forest-wolf`) is parsed server-side and presented to the client as a `word`.
        return if (plain === TokenArgumentType) PaperTokenArgumentType else plain
    }

    override fun convert(source: CommandSourceStack, type: ArgumentType, raw: Any): Any? = when {
        raw is PlayerSelectorArgumentResolver ->
            runCatching { raw.resolve(source) }.getOrNull().orEmpty().firstOrNull()?.let { PlayerId(it.uniqueId) }
        type is ArgumentType.Choice -> type.values.firstOrNull { it.equals(raw as? String, ignoreCase = true) }
        else -> raw
    }

    override fun onlinePlayerNames(source: CommandSourceStack): List<String> =
        plugin.server.onlinePlayers.map { it.name }

    override fun execute(path: List<String>, node: CommandSpec.Literal, source: CommandSourceStack, args: Map<String, Any>) {
        val actor = actor(source)
        scope.launch { invoker.invoke(node, actor, args, replier(source, actor)) }
    }

    override fun usage(source: CommandSourceStack, path: List<String>, node: CommandSpec.Literal) {
        val actor = actor(source)
        replier(source, actor)(CommandInvoker.USAGE, mapOf("usage" to CommandPaths.usage(path, node)))
    }

    private fun replier(source: CommandSourceStack, actor: CommandActor): (MessageKey, Map<String, String>) -> Unit =
        { key, placeholders ->
            val rendered = renderer().render(actor.playerId, key, placeholders)
            source.sender.sendMessage(AsteraTextDeserializer.deserialize(rendered))
        }
}
