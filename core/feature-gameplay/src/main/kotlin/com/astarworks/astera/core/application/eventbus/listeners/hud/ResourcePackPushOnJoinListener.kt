package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.hud.ResourcePackSpec
import org.slf4j.LoggerFactory

/**
 * Pushes the Astera resource pack to every player that joins (ADR-0182).
 * Without a configured pack (`ASTERA_RESOURCE_PACK_URL` unset) the server never
 * asks for one and the HUD stays on its text fallback. The spec is read per join
 * so `/astera admin pack reload` (F3-9) takes effect for later joins too.
 */
public class ResourcePackPushOnJoinListener(
    private val hudGateway: IHudGateway,
    private val pack: () -> ResourcePackSpec?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerJoinedServer) {
        val pack = pack() ?: return
        when (val outcome = hudGateway.pushResourcePack(event.playerId, pack)) {
            is Result.Success -> log.info("Resource pack pushed to {} ({}).", event.playerId.uuid, pack.url)
            is Result.Failure -> log.warn("Resource pack push failed for {}: {}", event.playerId, outcome.error)
        }
    }
}
