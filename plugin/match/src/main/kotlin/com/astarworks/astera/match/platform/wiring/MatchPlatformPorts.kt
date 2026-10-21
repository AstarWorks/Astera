package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.match.application.port.outbound.IBarrierGateway
import java.nio.file.Path

/**
 * Host-specific ports [MatchWiring.install] consumes (ADR-0174).
 *
 * @property barrierGateway optional overlay registered at priority 100 over
 *   the InMemory default (Paper: `PaperBarrierGateway`, BlockDisplay visual).
 *   `null` keeps the InMemory impl. Factory because the Paper impl resolves
 *   `IScheduler` / `IDomainEventBus` from the registry at registration time.
 * @property replayExportRoot directory `/astera-match replay export` writes to
 *   (Paper: `<plugin dataFolder>/replays`; the simulator: a temp dir).
 */
public class MatchPlatformPorts(
    public val replayExportRoot: Path,
    public val barrierGateway: ((ServiceRegistry) -> IBarrierGateway)? = null,
)
