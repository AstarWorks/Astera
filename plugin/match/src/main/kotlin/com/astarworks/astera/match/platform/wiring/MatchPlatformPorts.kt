package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.port.outbound.spectator.ISpectator
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.match.application.port.outbound.IBarrierGateway
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions
import com.astarworks.astera.match.application.port.outbound.IObjectiveStage
import com.astarworks.astera.match.application.port.outbound.IStarDropWorld
import com.astarworks.astera.match.application.port.outbound.INormaPresentation
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import java.nio.file.Path

/**
 * Host-specific ports [MatchWiring.install] consumes (ADR-0174).
 *
 * @property barrierGateway optional overlay registered at priority 100 over
 *   the InMemory default (Paper: `PaperBarrierGateway`, BlockDisplay visual).
 *   `null` keeps the InMemory impl. Factory because the Paper impl resolves
 *   `IScheduler` / `IDomainEventBus` from the registry at registration time.
 * @property spectator the host's spectator-mode port (Paper: `PaperSpectator`, the simulator: its own
 *   recorder), registered at priority 100. `null` disables death cam / real spectating.
 * @property playerPositions live player position source for in-match star pickup (Paper: a Bukkit read that
 *   skips dead / spectating players). `null` falls back to the `PlayerMoved`-fed `IPlayerLocationLookup`.
 * @property starDropWorld the star drop as a pickable item on the map (Paper: a glowing NETHER_STAR item entity). `null` = no drops exist (headless).
 * @property objectiveStage carrier glow / carried block / world markers / boss bars of the siege modes (Paper: Display entities and boss bars). `null` = headless.
 * @property normaPresentation titles / sounds / particles around Norma level-ups and the goal picker. `null` = silent.
 * @property replayExportRoot directory `/astera match replay export` writes to
 *   (Paper: `<plugin dataFolder>/replays`; the simulator: a temp dir).
 * @property annihilationConfig tunables of the Annihilation mode (phase timers,
 *   pillar HP, ...). `null` (production) reads the `default_config` of
 *   `content/game-modes/annihilation.yaml` over the domain defaults; a non-null
 *   value (the simulator, to play a whole match in a scenario) wins outright.
 */
public class MatchPlatformPorts(
    public val replayExportRoot: Path,
    public val barrierGateway: ((ServiceRegistry) -> IBarrierGateway)? = null,
    public val annihilationConfig: AnnihilationConfig? = null,
    public val spectator: ((ServiceRegistry) -> ISpectator)? = null,
    public val playerPositions: ((ServiceRegistry) -> IMatchPlayerPositions)? = null,
    public val starDropWorld: ((ServiceRegistry) -> IStarDropWorld)? = null,
    /** Ore generator pops as item stacks on the map (Paper: a dropped item entity). `null` = item generators drop nothing (headless). */
    /** Sets the vanilla block of Mid ore nodes / their placeholders. `null` = nodes are tracked but nothing is placed (headless). */
    public val oreNodeWorld: ((ServiceRegistry) -> com.astarworks.astera.match.application.port.outbound.IOreNodeWorld)? = null,
    public val generatorItemDrop: ((ServiceRegistry) -> com.astarworks.astera.match.application.port.outbound.IGeneratorItemDrop)? = null,
    public val objectiveStage: ((ServiceRegistry) -> IObjectiveStage)? = null,
    public val normaPresentation: ((ServiceRegistry) -> INormaPresentation)? = null,
    /** Floating labels over bases, spawns, objectives and pillars (Paper: TextDisplays shown per viewer). `null` = no labels (headless). */
    public val worldLabels: ((ServiceRegistry) -> com.astarworks.astera.match.application.port.outbound.IWorldLabels)? = null,
)
