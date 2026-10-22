package com.astarworks.astera.core.platform

import com.astarworks.astera.core.adapter.paper.packetevents.PacketEventsBootstrap
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.platform.paper.CorePaperBindings
import com.astarworks.astera.core.platform.wiring.CoreWiring
import com.astarworks.astera.core.platform.wiring.CoreWiringResult
import com.astarworks.astera.core.platform.wiring.ServerEnvironment
import com.astarworks.astera.core.platform.wiring.WiringContext
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path

/**
 * Astera-Core plugin entrypoint (ADR-0022).
 *
 * Phase 2 of the multi-plugin refactor: builds the shared services every
 * feature plugin depends on and publishes them via [services] (a
 * [ServiceRegistry]). Feature plugins resolve dependencies in their own
 * `onEnable` via `(server.pluginManager.getPlugin("Astera-Core") as AsteraCorePlugin).services.getOrThrow<...>()`.
 *
 * What Core wires:
 *  - [IScheduler] (Paper-backed)
 *  - [IMessageRenderer] (i18n from `content/languages/`)
 *  - [IBroadcaster] (Phase 2: log-only PaperBroadcaster)
 *  - [IDomainEventBus] (in-process)
 *  - [IItemRegistry] (loaded from `content/items/`; returns `ItemSpec`)
 *  - [IPlayerGateway] (vendor-neutral McPlayerGateway over Paper)
 *  - [IAsteraCommandRegistry] (single /astera Brigadier tree)
 *  - [IMcServer] (vendor-neutral handle for feature plugins that need
 *    direct player lookup)
 *
 * What Core deliberately does NOT wire (feature plugins own these):
 *  - WeaponRegistry / WeaponLoader / FireWeapon / GiveWeapon → `:plugin:weapons`
 *  - CooldownTracker / SkillRegistry / StatusRegistry / DamageRule → `:plugin:combat`
 *  - Match repository / phase tracking → `:plugin:match`
 *  - Currency ledger → `:plugin:economy`
 *  - Stat repositories → `:plugin:stats`
 *
 * Since ADR-0174 the enable sequence is split: [CorePaperBindings] builds the
 * Paper-backed `CorePlatformPorts`, [CoreWiring] (host-neutral, shared with the
 * headless simulator) builds and registers the object graph, then the bindings
 * register the Bukkit listeners.
 *
 * Command tree: Phase 2 stops short of a unified `/astera` root with
 * dynamically-added subcommands. Each feature plugin registers its own
 * top-level command (e.g. `/astera-weapons give …`). A unified root is
 * deferred to Stage 3-改 once the lifecycle question of "when can we know
 * every feature plugin has finished onEnable" is answered.
 */
public class AsteraCorePlugin : JavaPlugin() {

    /**
     * In-process registry of cross-plugin port implementations. Feature
     * plugins look up Core-provided services here in their own `onEnable`
     * and (in Phase 4) register their own overrides — e.g. the persistence
     * plugin overrides in-memory ItemRegistry with a Postgres-backed impl
     * at priority 100.
     */
    public val services: ServiceRegistry = ServiceRegistry().also { registry ->
        // ADR-0195: Persistence registers its overlays in its own onLoad, i.e.
        // before any consumer exists. A registration that still outranks an
        // already-resolved implementation is a split-brain bug: shout.
        registry.lateOverrideListener = { logger.severe(it.message) }
    }

    /** Role and proxy name of this backend (`ASTERA_SERVER_ROLE` / `ASTERA_SERVER_NAME`), read once; the other plugins reuse it. */
    public val serverEnvironment: ServerEnvironment = ServerEnvironment.fromSystem()

    /** Workspace / server content directory (`content/`) resolved at enable. */
    public lateinit var contentDir: Path
        private set

    /**
     * Wave 6c-stub-packetevents (ADR-0170) — set in `onLoad` if the PacketEvents
     * plugin is installed on the target server. Retained so `onDisable` knows
     * whether it should call `PacketEvents.getAPI().terminate()`. Stays `false`
     * when PacketEvents is absent — Astera continues with `InMemoryPacketInterceptor`
     * at priority 0 (CLAUDE.md §4 invariant: boots vanilla).
     */
    private var packetEventsInstalled: Boolean = false

    // Retained for onDisable (ADR-0174): wiring subscriptions / registrations,
    // wiring-owned tasks, and the Paper-side teardown.
    private var wiringContext: WiringContext? = null
    private var wiringResult: CoreWiringResult? = null
    private var paperBindings: CorePaperBindings? = null

    override fun onLoad() {
        // Wave 6c-stub-packetevents (ADR-0170) — PacketEvents 2.x lifecycle phase 1.
        // Called once at plugin load, before onEnable, while every plugin is in
        // a "loaded but not yet enabled" state. PE requires `setAPI + load`
        // here so `onEnable.init()` can hand the now-initialised API to its
        // Netty interceptors. The runtime presence check is intentionally
        // outside `PacketEventsBootstrap` (which references PE classes) so
        // class loading stays lazy: when PacketEvents is absent on the target
        // server, `PacketEventsBootstrap` is never resolved and the
        // `com.github.retrooper.packetevents.*` symbols never load (avoiding
        // NoClassDefFoundError per JLS §5.3.4).
        if (PacketEventsBootstrap.isInstalled(this)) {
            packetEventsInstalled = true
            PacketEventsBootstrap.load(this)
            logger.info("Wave 6c-stub-packetevents: PacketEvents loaded (lifecycle phase 1/3).")
        } else {
            logger.info(
                "Wave 6c-stub-packetevents: PacketEvents plugin not installed — staying on " +
                    "InMemoryPacketInterceptor (Astera boots vanilla per CLAUDE.md §4).",
            )
        }
    }

    override fun onEnable() {
        logger.info("Astera-Core enabling (ADR-0022 Phase 2)…")

        contentDir = resolveContentDir()
        logger.info("Content directory: $contentDir")

        val ctx = WiringContext(services, contentDir, logger, serverEnvironment.role, serverEnvironment.name)
        logger.info("Server role=${serverEnvironment.role} name=${serverEnvironment.name}")
        wiringContext = ctx
        val bindings = CorePaperBindings(this, packetEventsInstalled, serverEnvironment)
        paperBindings = bindings

        val ports = bindings.buildPorts()
        val result = CoreWiring.install(ctx, ports)
        wiringResult = result
        bindings.afterWiring(ctx, result)

        logger.info("Astera-Core enabled. Services published: ${SERVICE_NAMES.joinToString(", ")}")
    }

    override fun onDisable() {
        logger.info("Astera-Core disabling.")
        // Cancels the recorded event-bus subscriptions (Wave 5complete / ADR-0117)
        // and IGuiIntentRouter registrations (Wave G / ADR-0124, releases the
        // captured handler lambdas after a /reload).
        wiringContext?.close()
        wiringContext = null
        // Wiring-owned tasks and listeners: location tracker, engagement loop,
        // analytics listener, movement packet bridge, AFK sweep, hologram
        // transitions, critical-error broadcast bridge.
        wiringResult?.shutdown()
        wiringResult = null
        // Metrics server, BukkitEventAdapter task, Lettuce, PacketEvents, scheduler.
        paperBindings?.shutdown()
        paperBindings = null
    }

    /**
     * Workspace-aware content resolution (mirrors the pre-Phase-2 wiring in
     * `AsteraPlugin.kt`):
     *  1. `<server>/../content/` — useful when Astera ships next to a Paper
     *     server inside the workspace.
     *  2. `<server>/content/` — typical k8s / production layout.
     *  3. `<dataFolder>/content/` — last-resort default that ships with the jar.
     */
    private fun resolveContentDir(): Path {
        // Paper hands out a relative dataFolder (`plugins/Astera-Core`), so
        // resolve it to absolute before walking up to the server root.
        val pluginData = dataFolder.toPath().toAbsolutePath().normalize()
        val serverRoot = pluginData.parent?.parent

        val workspaceCandidate = serverRoot?.parent?.resolve("content")
        if (workspaceCandidate != null && Files.isDirectory(workspaceCandidate)) return workspaceCandidate

        val serverCandidate = serverRoot?.resolve("content")
        if (serverCandidate != null && Files.isDirectory(serverCandidate)) return serverCandidate

        return pluginData.resolve("content")
    }

    private companion object {
        val SERVICE_NAMES = listOf(
            "IScheduler",
            "IMessageRenderer",
            "IBroadcaster",
            "IDomainEventBus",
            "IItemRegistry (ItemSpec)",
            "IPlayerGateway",
            "IAsteraCommandRegistry",
            "IMcServer",
            "IGuiRegistry",
            "IGuiIntentRouter",
            "IGuiAdapter",
            "IScoreboardManager",
            "ITabList",
            "IBlockStateGateway",
            "ISchematicLoader",
            "BlockDurabilityRule",
            "IWorldTimeGateway",
            "IWorldWeatherGateway",
            "IWorldRulesGateway",
            "IStageRegionIndex",
            "IRegionPermissionService",
            "IPlayerLocationQueryService",
            "IMatchConfigService",
            "IPermissionService",
            "IReloadableContentsRegistry",
            "IPlayerHealthGateway (Wave 5k)",
            "IPlayerSpeedGateway (Wave 5k)",
            "IPlayerFoodGateway (Wave 5k)",
            "IResourcePoolService (Wave 5k)",
            "IResourceRegenScheduler (Wave 5k)",
            "IEntityRegistry",
            "INpcAdapter (minimal+citizens)",
            "INpcInteractionRouter",
            "IEntitySpawner",
            // Wave 5pve (ADR-0085)
            "IMobRegistry",
            "IMobAdapter (minimal+mythicmobs skeleton)",
            "IMobSpawner",
            "IMobSpawnLadder",
            "IPveLootDistributor (noop)",
            "IPveXpDistributor (noop)",
            // Wave 5pve-boss (ADR-0098)
            "ILootRollService (deterministic seeded)",
            "IBossKillAuditWriter (in-memory; persistence overlays at p=100)",
            "IBossLifecycleService (in-memory)",
            // Wave 5pve-content (ADR-0099)
            "IEncounterZoneRegistry (YAML-backed EncounterZoneContents)",
            "IBossSpecRegistry (YAML-backed BossSpecContents)",
            "IDeathPenaltyPolicy (zone-scoped)",
            // Wave 5t (ADR-0086)
            "IRoleRegistry",
            "IPermissionBackend (Minimal + LuckPerms skeleton)",
            "IRichPresenceGateway",
            "ILiveConfigEditor",
            "IServerBackupGateway",
            "IScheduledAnnouncementSystem",
            "IServerHealthCheck",
            "ISlowQueryAuditRepository",
            "IPlayerFeedbackQueue",
            // Wave 5q (ADR-0083)
            "IMovementValidator",
            "IDamageRateValidator",
            "IInteractValidator",
            "IExternalAnticheatBridge (noop default)",
            "AnticheatOrchestrator",
            "IAfkDetector + AfkSweepScheduler",
            // Wave 5y (ADR-0090)
            "IPacketInterceptor (InMemory default; Wave 6b PacketEvents override)",
            "IPlayerVisibilityGateway (InMemory default; Wave 6b packet-drop override)",
            "MovementValidatorPacketBridge -> PacketKind.MovementServerbound",
            // Wave 5u (ADR-0087) + Wave 5engage (ADR-0104)
            "IAnalyticsExporter (Micrometer when metrics enabled; Noop otherwise)",
            "AnalyticsEventListener (subscribes MatchStarted/MatchEnded/SkillCastRequested/PlayerJoinedServer)",
            "IEngagementSnapshotRepository (InMemory default; Postgres overlay at priority 100)",
            "EngagementDashboardService (recomputes every 5 min, publishes EngagementSnapshotComputed)",
            // Wave 5cinematic (ADR-0110)
            "ICameraGateway (InMemory default; Wave 6 Paper camera-packet override)",
            "ICinematicPathPlayer (InMemory default; Wave 6 Paper waypoint scheduler)",
            "IFreelookGateway (InMemory default; Wave 6 Paper movement-listener override)",
            "IScreenEffectGateway (InMemory default; Wave 6 Paper overlay packet override)",
            "IMusicTrackRegistry (YAML-backed MusicTrackContents)",
            "IMusicGateway (InMemory default; Wave 6 Paper playSound override)",
            "IPerMatchBgmService (in-memory; publishes MatchMusicChanged)",
            "IAdaptiveBgmService (in-memory; publishes AdaptiveMusicTriggered)",
            // Wave 5gui-depth (ADR-0114)
            "IGuiAnimationController (InMemory default; Wave 6 Paper EntityMetadata/armor-stand override)",
            "IGuiModalGateway (InMemory default; Wave 6 Paper anvil/boss-bar/overlay-packet override)",
            "ITooltipRegistry (InMemory default; null/empty resolvers until Wave 5refine/Wave 5flex/Wave 5pve-survival wire rarity+category lookups)",
            // Wave 5transitions (ADR-0115)
            "IHologramGateway (InMemory default; Wave 6b PaperHologramGateway over Display entities; 16 new per-type updaters)",
            "IHologramTransitionController (InMemory default; tick-stepped tween with Composite + Timeline + LoopMode + 32 Easing curves)",
            // Wave 5block-embed (ADR-0120)
            "IBlockInteractionRouter (InMemory default; per-position right/left-click binding table)",
            "ISignActionService (InMemory default; magic-sign [ASTERA] binding registry)",
            "IPortalGateway (InMemory default; block + region shape spatial index)",
            // Wave J 5server-content-ops (ADR-0127)
            "INewsRegistry (YAML-backed NewsContents)",
            "IChangelogRegistry (YAML-backed ChangelogContents)",
            "ILoginBonusRegistry (YAML-backed LoginBonusContents)",
            "ILoginStreakRepository (InMemory default; Phase 2 Postgres overlay)",
            "ILoginBonusService (daily claim + streak, ADR-0199)",
        )
    }
}
