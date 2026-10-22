package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.match.application.port.outbound.IBarrierGateway
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
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
 * @property annihilationConfig tunables of the Annihilation mode (phase timers,
 *   pillar HP, ...). The simulator shortens them to play a whole match in a
 *   scenario; the `default_config` of `content/game-modes/annihilation.yaml` is
 *   not wired in yet.
 */
public class MatchPlatformPorts(
    public val replayExportRoot: Path,
    public val barrierGateway: ((ServiceRegistry) -> IBarrierGateway)? = null,
    public val annihilationConfig: AnnihilationConfig = AnnihilationConfig.DEFAULT,
)
