package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.admin.panel.AdminCommandConsole
import com.astarworks.astera.core.application.admin.panel.AdminCorePanel
import com.astarworks.astera.core.application.admin.panel.AdminPanelGuard
import com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry
import com.astarworks.astera.core.application.admin.panel.InMemoryAdminPanelRegistry
import com.astarworks.astera.core.application.admin.panel.OpenAdminPanelUseCase
import com.astarworks.astera.core.application.admin.panel.buildAdminPanelCommandSpec
import com.astarworks.astera.core.application.port.outbound.IServerTransferGateway
import com.astarworks.astera.core.application.port.outbound.admin.IAdminPlayerGateway
import com.astarworks.astera.core.application.port.outbound.admin.IAdminTextPrompt
import com.astarworks.astera.core.application.port.outbound.admin.ILobbyRepaster
import com.astarworks.astera.core.application.port.outbound.admin.InMemoryAdminTextPrompt
import com.astarworks.astera.core.application.port.outbound.maintenance.IMaintenanceModeService
import com.astarworks.astera.core.application.service.admin.InMemoryAdminPlayerGateway
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.application.usecase.admin.ReloadAllContentUseCase

/**
 * F1-D (ADR-0183): the `/astera admin panel` command. Registers the shared pieces
 * feature plugins plug into ([IAdminPanelRegistry], [AdminPanelGuard],
 * [IAdminPlayerGateway]), Core's own pages and the `/astera admin panel` command.
 * Must run after the GUI router, permission service, audit repo, reload
 * registry and health check exist.
 */
internal fun CoreWiringState.wireAdminPanel() {
    val adminPlayers: IAdminPlayerGateway = ports.adminPlayerGateway
        ?: InMemoryAdminPlayerGateway()
    ctx.registrations += services.register<IAdminPlayerGateway>(adminPlayers, priority = 0)

    val registry: IAdminPanelRegistry = InMemoryAdminPanelRegistry()
    ctx.registrations += services.register<IAdminPanelRegistry>(registry, priority = 0)

    val guard = AdminPanelGuard(
        permissions = permissionService,
        messages = messages,
        audit = auditRepo,
        isOperator = adminPlayers::isOperator,
    )
    ctx.registrations += services.register<AdminPanelGuard>(guard, priority = 0)
    // ADR-0240: the same permission-or-operator rule drives `visible_if` on GUI slots and kit entries.
    ctx.registrations += services.register<com.astarworks.astera.core.application.gui.ViewerVisibility>(
        com.astarworks.astera.core.application.gui.ViewerVisibility(
            permissionService,
            adminPlayers::isOperator,
            queueState = { services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyQueueState>() },
        ),
        priority = 0,
    )

    val panel = AdminCorePanel(
        scheduler = scheduler,
        guard = guard,
        registry = registry,
        guiAdapter = guiAdapter,
        players = adminPlayers,
        maintenance = services.getOrThrow<IMaintenanceModeService>(),
        health = healthCheck,
        reloadAll = ReloadAllContentUseCase(reloadRegistry),
    )
    ctx.registrations += services.register<AdminCorePanel>(panel, priority = 0)
    ctx.registrations += panel.install(guiIntentRouter)

    // ADR-0233: the command console. Everything that only exists after this stage (the invoker, the
    // authorizer, the transfer gateway, plugin command subtrees, the Paper chat prompt / lobby
    // repaster) is resolved lazily at click time.
    ctx.registrations += services.register<IAdminTextPrompt>(InMemoryAdminTextPrompt(), priority = 0)
    val console = AdminCommandConsole(
        guard = guard,
        registry = registry,
        guiAdapter = guiAdapter,
        players = adminPlayers,
        messages = messages,
        prompt = { services.getOrThrow<IAdminTextPrompt>() },
        root = { commandRegistry.snapshot() },
        invoker = { commandInvoker },
        authorizer = { commandAuthorizer },
        scheduler = scheduler,
        transfers = { services.get<IServerTransferGateway>() },
        lobbyRepaster = { services.get<ILobbyRepaster>() },
    )
    ctx.registrations += services.register<AdminCommandConsole>(console, priority = 0)
    ctx.registrations += console.install(guiIntentRouter)

    val openPanel = OpenAdminPanelUseCase(panel, mcServer, guard)
    ctx.registrations += services.register<OpenAdminPanelUseCase>(openPanel, priority = 0)
    commands.register("admin", buildAdminPanelCommandSpec(openPanel))
    logger.info("F1-D admin panel wired (/astera admin panel; hub, players, server, self pages, command console).")
}
