package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.admin.panel.AdminCorePanel
import com.astarworks.astera.core.application.admin.panel.AdminPanelGuard
import com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry
import com.astarworks.astera.core.application.admin.panel.InMemoryAdminPanelRegistry
import com.astarworks.astera.core.application.admin.panel.OpenAdminPanelUseCase
import com.astarworks.astera.core.application.admin.panel.buildAdminPanelCommandSpec
import com.astarworks.astera.core.application.port.outbound.admin.IAdminPlayerGateway
import com.astarworks.astera.core.application.port.outbound.maintenance.IMaintenanceModeService
import com.astarworks.astera.core.application.service.admin.InMemoryAdminPlayerGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.application.usecase.admin.ReloadAllContentUseCase

/**
 * F1-D (ADR-0182): the `/astera-admin` panel. Registers the shared pieces
 * feature plugins plug into ([IAdminPanelRegistry], [AdminPanelGuard],
 * [IAdminPlayerGateway]), Core's own pages and the `/astera-admin` command.
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

    val panel = AdminCorePanel(
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

    val openPanel = OpenAdminPanelUseCase(panel, mcServer, guard)
    ctx.registrations += services.register<OpenAdminPanelUseCase>(openPanel, priority = 0)
    commandHandler.register(buildAdminPanelCommandSpec(openPanel))
    logger.info("F1-D admin panel wired (/astera-admin; hub, players, server, self pages).")
}
