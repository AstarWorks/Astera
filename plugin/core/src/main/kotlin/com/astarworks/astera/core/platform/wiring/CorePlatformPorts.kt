package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.adapter.api.hud.IScoreboardManager
import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver
import com.astarworks.astera.core.application.port.outbound.IHologramGateway
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerFoodGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerMovementGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSpeedGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerVisionGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.IWorldRulesGateway
import com.astarworks.astera.core.application.port.outbound.IWorldTimeGateway
import com.astarworks.astera.core.application.port.outbound.IWorldWeatherGateway
import com.astarworks.astera.core.application.port.outbound.admin.IAdminPlayerGateway
import com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter
import com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway
import com.astarworks.astera.core.application.port.outbound.cinematic.ICameraGateway
import com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway
import com.astarworks.astera.core.application.port.outbound.entity.INpcBodyMaterializer
import com.astarworks.astera.core.application.port.outbound.packet.IPacketInterceptor
import com.astarworks.astera.core.application.port.outbound.sign.ISignActionService
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.domain.world.BlockDurabilityRule

/**
 * Everything host-specific that [CoreWiring.install] consumes (ADR-0174).
 *
 * The pure wiring builds the object graph and registers the in-memory
 * defaults at priority 0; the host contributes the pieces below. Two shapes:
 *
 *  - **plain value** — the host impl is self-contained (needs nothing the
 *    wiring builds), e.g. a Paper adapter that only needs `Server`;
 *  - **factory** — the host impl needs objects the wiring builds first (event
 *    bus, message renderer, content registries). The wiring calls it at the
 *    exact point the old monolithic `onEnable` constructed the Paper impl, so
 *    mid-wiring `services.get<...>()` lookups still see the overlay.
 *
 * Every **nullable** entry is an optional priority-100 overlay: when present
 * the wiring registers it AFTER the in-memory default, in the original order
 * (ServiceRegistry.get returns the highest priority, ties go to the first
 * registered). `null` means "stay on the in-memory default" (simulator).
 */
public class CorePlatformPorts(
    public val mcServer: IMcServer,
    public val scheduler: IScheduler,
    public val broadcaster: IBroadcaster,
    /** Required: GUI rendering has no in-memory default. Built before the wiring registers [IGuiBindingResolver]. */
    public val guiAdapter: GuiAdapterFactory,
    // ---- HUD ------------------------------------------------------------
    public val scoreboardManager: ((ServiceRegistry) -> IScoreboardManager)? = null,
    public val tabList: ((ServiceRegistry) -> ITabList)? = null,
    public val hudGateway: ((ServiceRegistry) -> IHudGateway)? = null,
    /** Team-coloured nameplates / tab grouping / HP under the name (ADR-0200). Null = in-memory recorder. */
    public val playerLabelGateway: ((ServiceRegistry) -> com.astarworks.astera.core.application.port.outbound.IPlayerLabelGateway)? = null,
    /**
     * The host's own permission answer for a player and node (Paper: `hasPermission`, true for
     * operators). When set, the permission service honours it on top of Astera roles (ADR-0184).
     */
    public val hostPermission: ((com.astarworks.astera.core.domain.player.PlayerId, String) -> Boolean)? = null,
    /** The resource pack to push on join (ADR-0182); null = none configured. */
    public val resourcePack: com.astarworks.astera.core.domain.hud.ResourcePackSpec? = null,
    // ---- World ----------------------------------------------------------
    /** No in-memory default exists: when null, `services.get<IWorldGateway>()` is null and the wiring falls back to a Noop. */
    public val worldGateway: ((ServiceRegistry) -> IWorldGateway)? = null,
    public val blockStateGateway: ((BlockStateGatewayDeps) -> IBlockStateGateway)? = null,
    public val worldTimeGateway: IWorldTimeGateway? = null,
    public val worldWeatherGateway: IWorldWeatherGateway? = null,
    public val worldRulesGateway: IWorldRulesGateway? = null,
    public val signActionService: ISignActionService? = null,
    public val hologramGateway: ((ServiceRegistry) -> IHologramGateway)? = null,
    /** Physical bodies for the NPCs in `content/lobby-npcs/`; absent in headless hosts. */
    public val npcBodies: ((ServiceRegistry) -> INpcBodyMaterializer)? = null,
    // ---- Player / effects -----------------------------------------------
    public val playerSoundGateway: IPlayerSoundGateway? = null,
    public val playerHealthGateway: ((ServiceRegistry) -> IPlayerHealthGateway)? = null,
    public val playerSpeedGateway: ((ServiceRegistry) -> IPlayerSpeedGateway)? = null,
    public val playerFoodGateway: ((ServiceRegistry) -> IPlayerFoodGateway)? = null,
    /** No in-memory default exists for movement / vision. */
    public val playerMovementGateway: IPlayerMovementGateway? = null,
    public val playerVisionGateway: IPlayerVisionGateway? = null,
    public val playerAnimationGateway: ((ServiceRegistry) -> IPlayerAnimationGateway)? = null,
    public val cameraGateway: ((ServiceRegistry) -> ICameraGateway)? = null,
    /**
     * Inventory snapshot save/restore + action-lock listeners. A hook rather
     * than an overlay: it registers nothing, but subscribes to the event bus
     * at this exact point of the wiring (subscription order is observable).
     */
    public val inventoryLifecycle: ((ServiceRegistry) -> Unit)? = null,
    /** Admin panel host operations (online list, gamemode, fly, kick, teleport). Null = in-memory (headless). */
    public val adminPlayerGateway: IAdminPlayerGateway? = null,
    // ---- Infrastructure -------------------------------------------------
    /** Returns a started interceptor (registered at priority 100) or is null when PacketEvents is absent. */
    public val packetInterceptor: (() -> IPacketInterceptor)? = null,
    /**
     * Metrics endpoint bootstrap. May register `IMetricsRegistry` and start an
     * HTTP server; returns the analytics exporter to use, or null for the Noop one.
     */
    public val metrics: ((ServiceRegistry) -> IAnalyticsExporter?)? = null,
    /**
     * Cross-server messenger (Redis pub/sub + player connect through the
     * Velocity proxy, ADR-0179). Registered at priority 100 over the in-memory
     * loopback; the server-transfer gateway is built on top of it. `null`
     * (simulator without a peer, single-server mode) or a factory returning `null` keeps the loopback.
     */
    public val proxyMessenger: ((WiringContext) -> com.astarworks.astera.core.application.port.outbound.IProxyMessenger?)? = null,
)

/** Builds the host GUI adapter from the content/i18n objects the wiring has just created. */
public fun interface GuiAdapterFactory {
    public fun create(
        items: IItemRegistry,
        messages: IMessageRenderer,
        bindingResolver: IGuiBindingResolver,
    ): IGuiAdapter
}

/** Inputs the wiring hands to [CorePlatformPorts.blockStateGateway]. */
public class BlockStateGatewayDeps(
    public val services: ServiceRegistry,
    public val customBlocks: ICustomBlockRegistry,
    public val durabilityRule: BlockDurabilityRule,
)
