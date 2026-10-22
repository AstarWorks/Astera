package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.gui.lobby.MainMenuGuiSpec
import com.astarworks.astera.core.application.service.lobby.LobbyMenuKitContent
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.lobby.LobbyMenuKit

/**
 * `gui.open {gui: <id>}` opens a registered GUI spec (the lobby kit's "Game Menu" compass and
 * crafting-grid buttons use it; `main-menu` falls back to the built-in spec, same as
 * `/astera menu`), and the lobby menu kit layout is loaded on backends that host the lobby
 * (ADR-0185).
 */
internal fun CoreWiringState.wireGuiOpenIntentAndMenuKit() {
    val registry = guiContents
    ctx.registrations += guiIntentRouter.register("gui.open") { intent, player ->
        val id = intent.payload["gui"]
        val spec = id?.let { runCatching { GuiId(it) }.getOrNull() }?.let { gid ->
            registry.find(gid) ?: MainMenuGuiSpec.SPEC.takeIf { it.id == gid }
        }
        if (spec == null) {
            logger.warning("gui.open: no GUI spec for payload gui='$id'")
        } else if (!guiAdapter.open(player, spec)) {
            logger.fine("gui.open: adapter rejected '${spec.id.value}' for ${player.id}")
        }
    }
    if (!ctx.serverRole.hostsLobby) return
    when (val loaded = LobbyMenuKitContent.load(contentDir)) {
        is Result.Success -> {
            services.register<LobbyMenuKit>(loaded.value)
            logger.info("Lobby menu kit loaded: ${loaded.value.entries.size} entries")
        }
        is Result.Failure ->
            logger.warning("Lobby menu kit unavailable (${loaded.error}); lobby players get no kit.")
    }
}
