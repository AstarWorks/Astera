package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.gui.GuiId

/**
 * `gui.open {gui: <id>}` opens a registered GUI spec (the lobby kit's "Game Menu" compass and
 * crafting-grid buttons use it), and the lobby menu kit layout is loaded on backends that host the lobby
 * (ADR-0185).
 */
internal fun CoreWiringState.wireGuiOpenIntentAndMenuKit() {
    val registry = guiContents
    ctx.registrations += guiIntentRouter.register("gui.open") { intent, player ->
        val id = intent.payload["gui"]
        val spec = id?.let { runCatching { GuiId(it) }.getOrNull() }?.let { gid ->
            registry.find(gid)
        }
        if (spec == null) {
            logger.warning("gui.open: no GUI spec for payload gui='$id'")
        } else if (!guiAdapter.open(player, spec)) {
            logger.fine("gui.open: adapter rejected '${spec.id.value}' for ${player.id}")
        }
    }
    if (!ctx.serverRole.hostsLobby) return
    val kitContents = com.astarworks.astera.core.application.contents.LobbyMenuKitContents()
    lobbyMenuKitContents = kitContents
    services.register<com.astarworks.astera.core.application.contents.LobbyMenuKitContents>(kitContents)
    // Registered on the reload dispatcher in wireContentAndRepositories (the registry does not exist yet here).
    val report = kitContents.reload(contentDir)
    if (report.failed == 0) {
        logger.info("Lobby menu kit loaded: ${report.loaded} entries")
    } else {
        logger.warning(
            "Lobby menu kit unavailable (${report.failures.firstOrNull()?.message}); lobby players get no kit " +
                "until '/astera admin reload lobby-kit' succeeds.",
        )
    }
}
