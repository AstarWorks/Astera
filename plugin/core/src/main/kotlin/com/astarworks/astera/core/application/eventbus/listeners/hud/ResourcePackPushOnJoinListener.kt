package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.hud.ResourcePackSpec
import org.slf4j.LoggerFactory

/**
 * Pushes the Astera resource pack to every player that joins (ADR-0182).
 * Only wired when `ASTERA_RESOURCE_PACK_URL` is configured; without it the
 * server never asks for a pack and the HUD stays on its text fallback.
 */
public class ResourcePackPushOnJoinListener(
    private val hudGateway: IHudGateway,
    private val pack: ResourcePackSpec,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerJoinedServer) {
        when (val outcome = hudGateway.pushResourcePack(event.playerId, pack)) {
            is Result.Success -> Unit
            is Result.Failure -> log.warn("Resource pack push failed for {}: {}", event.playerId, outcome.error)
        }
    }
}
