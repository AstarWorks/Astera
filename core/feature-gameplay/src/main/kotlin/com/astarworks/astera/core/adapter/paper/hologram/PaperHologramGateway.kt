package com.astarworks.astera.core.adapter.paper.hologram

import com.astarworks.astera.core.application.port.outbound.HologramError
import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities
import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntityJanitor
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.entity.IManagedEntityRegistry
import com.astarworks.astera.core.application.port.outbound.IHologramGateway
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.BillboardMode
import com.astarworks.astera.core.domain.hologram.Hologram
import com.astarworks.astera.core.domain.hologram.HologramBrightness
import com.astarworks.astera.core.domain.hologram.HologramColor
import com.astarworks.astera.core.domain.hologram.HologramHandle
import com.astarworks.astera.core.domain.hologram.HologramInterpolation
import com.astarworks.astera.core.domain.hologram.HologramKind
import com.astarworks.astera.core.domain.hologram.HologramTransform
import com.astarworks.astera.core.domain.hologram.HologramVisibilityScope
import com.astarworks.astera.core.domain.hologram.ItemDisplayMode
import com.astarworks.astera.core.domain.hologram.TextAlignment
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.joml.Vector3f
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper concrete of [IHologramGateway] (Wave 6f, ADR-0163) — bridges the
 * Display-entity vocabulary defined in Wave 5h (ADR-0079 §1) + the 16 per-type
 * updaters added in Wave 5transitions (ADR-0115 §A) to Bukkit's Display
 * Entity family ([TextDisplay] / [ItemDisplay] / [BlockDisplay]).
 *
 * # Bukkit mapping
 *
 *  - [HologramKind.Text]  → `world.spawn(loc, TextDisplay::class.java)` with
 *    every `TextDisplay` field set from the [HologramKind.Text] payload.
 *    Lines are joined with `\n` after each [MessageKey] resolves through
 *    [IMessageRenderer] (when present) or falls back to the raw key text.
 *  - [HologramKind.Item] → `world.spawn(loc, ItemDisplay::class.java)` with
 *    the [ItemDisplay.setItemStack] built by the shared item materializer from
 *    the [IItemRegistry] spec (ADR-0241: same art / name as the real item) and [ItemDisplay.setItemDisplayTransform]
 *    mapped 1:1 from [ItemDisplayMode]. Fallback chain when the registry is
 *    absent or the id is unknown:
 *      1. the materialized `IItemRegistry` spec
 *      2. `Material.matchMaterial("minecraft:${id.value.replace('-','_')}")`
 *         (treat the kebab-case id as a vanilla material slug; safe for
 *         common cases like `diamond-sword` / `iron-pickaxe`)
 *      3. `Material.STONE` (last-resort placeholder so the entity still spawns;
 *         logged INFO at first occurrence per server lifetime).
 *  - [HologramKind.Block] → `world.spawn(loc, BlockDisplay::class.java)` with
 *    `BlockDisplay.setBlock(Bukkit.createBlockData(blockKey))`. Bukkit raises
 *    `IllegalArgumentException` on invalid block strings — caught + returned
 *    as [HologramError.Storage] so the gateway never leaks raw JVM throwables
 *    (Konsist `application layers must not import raw JVM throwable types`).
 *
 * # Shadow-map snapshot strategy (ADR-0115 §A)
 *
 * Paper's `Display` entity exposes nearly every Wave 5h field as a getter
 * (`Display.transformation` / `TextDisplay.text()` / `ItemDisplay.itemStack` /
 * `BlockDisplay.block`), so a reverse-mapping `snapshot(handle)` is
 * theoretically possible. **We deliberately do not reverse-map**:
 *
 *  - It would duplicate the domain-side `Hologram.copy(...)` mutation logic
 *    on the read path, and any divergence (rounding, default replacement)
 *    would surface only at runtime.
 *  - [HologramVisibilityScope] has no Bukkit equivalent — the shadow map is
 *    the only place that lives anyway.
 *  - [InMemoryHologramGateway] already tracks the live aggregate in
 *    `ConcurrentHashMap<HologramHandle, Hologram>` (its [snapshot] is
 *    `live[handle]`); mirroring that here keeps the contract identical
 *    across impls.
 *
 * The shadow map ([live]) is the SSOT for `snapshot` / `all` / [viewers] /
 * [TypeMismatch] dispatch (per-type updaters check `shadow.kind is X` before
 * touching the entity — cheaper than `entity is XDisplay` and atomic with
 * the InMemory contract).
 *
 * # Off-main handling
 *
 * The Bukkit Display entity API (spawn / remove / setters on `Display`,
 * `TextDisplay`, `ItemDisplay`, `BlockDisplay`) is **main-thread only** per
 * Bukkit docs. Every write method checks [Server.isPrimaryThread] up front:
 *
 *  - Off-main writes return [HologramError.Storage] with `"off-main: <op>"`.
 *    Callers should defer via `scheduler.runLater(Duration.ZERO, ...)` and
 *    retry. We deliberately do **not** auto-defer (unlike Camera) — hologram
 *    mutations are typically tick-scheduled by the transition controller
 *    which already runs main-thread, so an off-main call here signals a
 *    caller bug worth surfacing.
 *  - Reads ([snapshot], [all], [viewers]) hit only the [ConcurrentHashMap]s
 *    and never touch a Bukkit entity, so they're thread-safe and never
 *    return [HologramError.Storage].
 *
 * # Visibility filtering (Wave 5h-fix2 + Wave 6g, ADR-0168)
 *
 * [HologramVisibilityScope.Everyone] is enforced implicitly — `isVisibleByDefault`
 * stays `true` (Bukkit spawn default) so every tracker receives the entity.
 *
 * [HologramVisibilityScope.PerPlayer] is implemented end-to-end via Paper-
 * native APIs ([Entity.setVisibleByDefault]`(false)` + [Player.showEntity]
 * per listed online player). Carves out the per-viewer-visibility clause of
 * ADR-0095's PacketEvents strategy — Paper 1.20+ exposes the per-viewer
 * spawn-suppression directly, so no external packet library is required for
 * this surface. Bukkit's `Player.showEntity` state is per-session, so
 * [JoinReapplyListener] re-applies on [PlayerJoinEvent] for offline-at-spawn
 * players. ADR-0095's other clauses (inbound movement interception, replay
 * coordinate masking) remain genuine PacketEvents adoption targets and are
 * unaffected.
 *
 * [HologramVisibilityScope.PerTeam] is implemented end-to-end via Wave
 * 6g-PerTeam (ADR-0169): a lazy `matchRepositoryLookup: () -> IMatchRepository?`
 * (default `{ null }`) resolves `(matchId, teamId) → Match.teams[i].members`
 * at `applyVisibility` time. Lazy because the Match plugin (which registers
 * the repo impl) loads AFTER the Core plugin that constructs this gateway;
 * a constructor-time resolve would race. When the resolver is unwired or
 * returns null (test mode, stale match), the gateway logs INFO and falls
 * back to globally visible — same posture as the pre-`start` PerPlayer
 * branch, so the visibility *intent* is recorded in the shadow map and a
 * later `updateVisibility(scope)` after wiring resyncs.
 *
 * [viewers] is honest: it returns the resolved set per the scope. PerTeam
 * resolves via the same `matchRepositoryLookup`; when null, returns empty
 * (matches the [InMemoryHologramGateway] contract).
 *
 * # JOML usage
 *
 * [HologramTransform] holds two `org.joml.Quaternionf` fields directly per
 * Wave 5h-fix (allowed in domain per `feedback_joml_in_domain` memory —
 * pure-math, no Minecraft coupling). Bukkit's
 * [org.bukkit.util.Transformation] constructor takes
 * `(translation: Vector3f, leftRotation: Quaternionf, scale: Vector3f,
 * rightRotation: Quaternionf)` — the leftRotation + rightRotation pair is
 * what Bukkit uses for non-uniform-scale-along-arbitrary-axis composition
 * (`leftRotation * (scale * (rightRotation * vertex)) + translation`). The
 * adapter narrows [Vec3] (double-precision) to `Vector3f` (single-precision)
 * at this boundary; rotations pass straight through.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCorePlugin.onEnable` (overlays
 * `InMemoryHologramGateway` priority 0).
 *
 * @see IHologramGateway for the full 28-method contract.
 * @see com.astarworks.astera.core.application.service.InMemoryHologramGateway
 *      for the priority-0 default + the contract reference impl.
 * @see com.astarworks.astera.core.adapter.paper.cinematic.PaperCameraGateway
 *      for the sibling Paper-stub posture reference (Wave 6a-7, ADR-0152).
 */
public class PaperHologramGateway(
    private val server: Server,
    @Suppress("unused") private val scheduler: IScheduler,
    @Suppress("unused") private val eventBus: IDomainEventBus,
    private val itemRegistry: IItemRegistry? = null,
    /** ADR-0241: an Astera item floats as the real item (art included), not just its base material. */
    private val itemMaterializer: () -> com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer? = { null },
    private val messageRenderer: IMessageRenderer? = null,
    /**
     * Wave 6g-PerTeam (ADR-0169) — lazy lookup for `IMatchRepository` so
     * `HologramVisibilityScope.PerTeam` can resolve `(matchId, teamId) →
     * Set<PlayerId>` via `Match.teams[i].members`. Lazy because the Match
     * plugin (which registers the repo impl) loads AFTER the Core plugin
     * that constructs this gateway; a constructor-time resolve would race.
     *
     * Default `{ null }` keeps PerTeam stubbing behaviour (single INFO log
     * + globally visible). Test fixtures may pass `{ fakeMatchRepo }` for
     * a deterministic roster.
     */
    private val matchRepositoryLookup: () -> IMatchRepository? = { null },
    /** Shared registry of managed entities; `null` in tests that do not need owner idempotency. */
    private val managed: IManagedEntityRegistry? = null,
    /** Sweeps legacy (pre-fix, chunk-saved) duplicates around owned holograms; `null` = no sweep. */
    private val janitor: PaperManagedEntityJanitor? = null,
) : IHologramGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Shadow SSOT: every successful [spawn] inserts into both [live] and
     * [entities]; every [despawn] removes from both. Per-type updaters
     * mutate the [live] entry via `.copy(...)` and apply the same change
     * to the Bukkit entity — mirroring [InMemoryHologramGateway]'s shape.
     */
    private val live: ConcurrentHashMap<HologramHandle, Hologram> = ConcurrentHashMap()

    /** [HologramHandle] → spawned Bukkit entity UUID. */
    private val entities: ConcurrentHashMap<HologramHandle, UUID> = ConcurrentHashMap()

    private var perTeamScopeStubLogged: Boolean = false
    private var itemFallbackStoneLogged: Boolean = false

    /**
     * Held after [start]. Required for [Player.showEntity]/[Player.hideEntity]
     * — both Bukkit methods take a `Plugin` so the visibility scope unbinds
     * if the plugin disables. Also passed to [PluginManager.registerEvents]
     * for the [JoinReapplyListener].
     */
    private var plugin: JavaPlugin? = null

    /** ownerKey → handle of the live hologram of that owner. */
    private val owners: MutableMap<String, HologramHandle> = HashMap()

    private companion object {
        /** Blocks around an owned hologram within which untagged legacy text displays are removed. */
        const val LEGACY_SWEEP_RADIUS: Double = 2.0
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────

    override fun spawn(hologram: Hologram): Result<HologramHandle, HologramError> {
        if (!server.isPrimaryThread) {
            return Result.failure(HologramError.Storage("off-main: spawn"))
        }
        val world = server.getWorld(hologram.worldId.value)
            ?: return Result.failure(HologramError.UnknownWorld(hologram.worldId))
        val loc = Location(world, hologram.position.x, hologram.position.y, hologram.position.z)

        // Once per owner per server lifetime: a live, valid entity for this owner is reused.
        val ownerKey = hologram.ownerKey
        if (ownerKey != null) {
            val existing = owners[ownerKey]
            if (existing != null && entities[existing]?.let { server.getEntity(it)?.isValid } == true) {
                return Result.success(existing)
            }
            existing?.let { live.remove(it); entities.remove(it) }
            // Entities saved into the chunk by builds that did not mark them non-persistent.
            janitor?.addLegacyZone(
                PaperManagedEntityJanitor.LegacyZone(hologram.worldId, hologram.position, LEGACY_SWEEP_RADIUS),
            )
        }

        val entityClass = when (hologram.kind) {
            is HologramKind.Text -> TextDisplay::class.java
            is HologramKind.Item -> ItemDisplay::class.java
            is HologramKind.Block -> BlockDisplay::class.java
        }
        val spawned: Display = try {
            world.spawn(loc, entityClass) { entity ->
                applyBaseFields(entity, hologram)
                when (val kind = hologram.kind) {
                    is HologramKind.Text -> applyText(entity as TextDisplay, kind, textViewer(hologram))
                    is HologramKind.Item -> applyItem(entity as ItemDisplay, kind)
                    is HologramKind.Block -> applyBlock(entity as BlockDisplay, kind)
                }
            }
        } catch (ex: IllegalArgumentException) {
            // Bukkit raises this on invalid BlockData strings (e.g. malformed
            // `minecraft:oak_stairs[invalid=true]`). Surface as Storage so the
            // gateway never leaks raw JVM throwables to the application layer.
            return Result.failure(HologramError.Storage("spawn rejected: ${ex.message}"))
        } catch (ex: IllegalStateException) {
            return Result.failure(HologramError.Storage("spawn rejected: ${ex.message}"))
        }

        val handle = HologramHandle(UUID.randomUUID().toString())
        live[handle] = hologram
        entities[handle] = spawned.uniqueId
        val claimKey = ownerKey ?: "hologram:${handle.value}"
        PaperManagedEntities.mark(spawned, claimKey)
        managed?.claim(claimKey, spawned.uniqueId)
        if (ownerKey != null) owners[ownerKey] = handle

        applyVisibility(spawned, hologram.visibilityScope)

        return Result.success(handle)
    }

    override fun despawn(handle: HologramHandle): Result<Unit, HologramError> {
        // Idempotent per port KDoc — unknown handle returns Success.
        val removed = live.remove(handle)
        val claimKey = removed?.ownerKey ?: "hologram:${handle.value}"
        managed?.release(claimKey)
        removed?.ownerKey?.let { owners.remove(it) }
        val entityId = entities.remove(handle) ?: return Result.success(Unit)
        if (!server.isPrimaryThread) {
            return Result.failure(HologramError.Storage("off-main: despawn"))
        }
        server.getEntity(entityId)?.remove()
        return Result.success(Unit)
    }

    /** Removes every hologram this gateway spawned (plugin disable). Main thread. */
    public fun despawnAll() {
        for (handle in live.keys.toList()) despawn(handle)
    }

    // ─── Full-payload updaters (Wave 5h) ──────────────────────────────────

    override fun updateTransform(
        handle: HologramHandle,
        transform: HologramTransform,
    ): Result<Unit, HologramError> = mutate(handle, "updateTransform") { entity, current ->
        live[handle] = current.copy(transform = transform)
        entity.transformation = toBukkitTransformation(transform)
    }

    override fun updateKind(
        handle: HologramHandle,
        kind: HologramKind,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        if (current.kind::class != kind::class) {
            // Cross-kind updateKind requires despawn+respawn — Bukkit
            // Display variants are distinct entity types. Per the port KDoc
            // "hot-swap the kind payload" reads as in-place; we surface a
            // TypeMismatch so callers know to despawn explicitly. The
            // InMemory impl allows in-place kind reassignment because it
            // has no entity-type binding; the Paper impl is stricter.
            return Result.failure(
                HologramError.TypeMismatch(
                    expected = current.kind::class,
                    actual = kind::class,
                ),
            )
        }
        return mutate(handle, "updateKind") { entity, _ ->
            live[handle] = current.copy(kind = kind)
            when (kind) {
                is HologramKind.Text -> applyText(entity as TextDisplay, kind, textViewer(current))
                is HologramKind.Item -> applyItem(entity as ItemDisplay, kind)
                is HologramKind.Block -> applyBlock(entity as BlockDisplay, kind)
            }
        }
    }

    override fun teleport(
        handle: HologramHandle,
        newPosition: Vec3,
        newWorld: WorldId?,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        val targetWorldId = newWorld ?: current.worldId
        val world = server.getWorld(targetWorldId.value)
            ?: return Result.failure(HologramError.UnknownWorld(targetWorldId))
        return mutate(handle, "teleport") { entity, _ ->
            live[handle] = current.copy(position = newPosition, worldId = targetWorldId)
            entity.teleport(Location(world, newPosition.x, newPosition.y, newPosition.z))
        }
    }

    override fun updateVisibility(
        handle: HologramHandle,
        scope: HologramVisibilityScope,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        if (!server.isPrimaryThread) {
            // Visibility re-apply touches Bukkit Entity + Player APIs
            // (`isVisibleByDefault`, `showEntity`, `hideEntity`) which are
            // main-thread only. Same posture as the other mutators here:
            // off-main signals a caller bug worth surfacing rather than
            // silent auto-defer.
            return Result.failure(HologramError.Storage("off-main: updateVisibility"))
        }
        live[handle] = current.copy(visibilityScope = scope)
        val entityId = entities[handle]
        val entity = entityId?.let { server.getEntity(it) }
        if (entity != null) {
            applyVisibility(entity, scope)
        }
        return Result.success(Unit)
    }

    override fun updateBrightness(
        handle: HologramHandle,
        brightness: HologramBrightness?,
    ): Result<Unit, HologramError> = mutate(handle, "updateBrightness") { entity, current ->
        live[handle] = current.copy(brightness = brightness)
        entity.brightness = brightness?.let { Display.Brightness(it.blockLight, it.skyLight) }
    }

    override fun updateInterpolation(
        handle: HologramHandle,
        interpolation: HologramInterpolation,
    ): Result<Unit, HologramError> = mutate(handle, "updateInterpolation") { entity, current ->
        live[handle] = current.copy(interpolation = interpolation)
        entity.interpolationDuration = interpolation.durationTicks
        entity.interpolationDelay = interpolation.delayTicks
        entity.teleportDuration = interpolation.teleportDurationTicks.coerceAtMost(59)
    }

    override fun updateGlowColor(
        handle: HologramHandle,
        color: HologramColor?,
    ): Result<Unit, HologramError> = mutate(handle, "updateGlowColor") { entity, current ->
        live[handle] = current.copy(glowColor = color)
        entity.glowColorOverride = color?.let { toBukkitColor(it) }
    }

    // ─── Display base per-type updaters (Wave 5transitions, ADR-0115 §A) ──

    override fun updateShadowRadius(handle: HologramHandle, radius: Float): Result<Unit, HologramError> =
        mutate(handle, "updateShadowRadius") { entity, current ->
            live[handle] = current.copy(shadowRadius = radius)
            entity.shadowRadius = radius
        }

    override fun updateShadowStrength(handle: HologramHandle, strength: Float): Result<Unit, HologramError> =
        mutate(handle, "updateShadowStrength") { entity, current ->
            live[handle] = current.copy(shadowStrength = strength)
            entity.shadowStrength = strength
        }

    override fun updateViewRange(handle: HologramHandle, range: Float): Result<Unit, HologramError> =
        mutate(handle, "updateViewRange") { entity, current ->
            live[handle] = current.copy(viewRangeBlocks = range)
            entity.viewRange = range
        }

    override fun updateBillboard(
        handle: HologramHandle,
        billboard: BillboardMode,
    ): Result<Unit, HologramError> = mutate(handle, "updateBillboard") { entity, current ->
        live[handle] = current.copy(billboard = billboard)
        entity.billboard = toBukkitBillboard(billboard)
    }

    override fun updateDisplayDimensions(
        handle: HologramHandle,
        width: Float,
        height: Float,
    ): Result<Unit, HologramError> = mutate(handle, "updateDisplayDimensions") { entity, current ->
        live[handle] = current.copy(displayWidth = width, displayHeight = height)
        entity.displayWidth = width
        entity.displayHeight = height
    }

    // ─── Text-specific updaters (Wave 5transitions, ADR-0115 §A) ──────────

    override fun updateTextContent(
        handle: HologramHandle,
        lines: List<MessageKey>,
    ): Result<Unit, HologramError> = mutateText(handle, "updateTextContent") { entity, current, textKind ->
        live[handle] = current.copy(kind = textKind.copy(lines = lines))
        entity.text = renderLines(lines, textViewer(current))
    }

    override fun updateTextLineWidth(
        handle: HologramHandle,
        width: Int,
    ): Result<Unit, HologramError> = mutateText(handle, "updateTextLineWidth") { entity, current, textKind ->
        live[handle] = current.copy(kind = textKind.copy(lineWidth = width))
        entity.lineWidth = width
    }

    override fun updateTextBackgroundColor(
        handle: HologramHandle,
        color: HologramColor?,
    ): Result<Unit, HologramError> = mutateText(handle, "updateTextBackgroundColor") { entity, current, textKind ->
        live[handle] = current.copy(kind = textKind.copy(backgroundColor = color))
        entity.backgroundColor = color?.let { toBukkitColor(it) }
    }

    override fun updateTextOpacity(
        handle: HologramHandle,
        opacity: Byte,
    ): Result<Unit, HologramError> = mutateText(handle, "updateTextOpacity") { entity, current, textKind ->
        live[handle] = current.copy(kind = textKind.copy(textOpacity = opacity))
        entity.textOpacity = opacity
    }

    override fun updateTextAlignment(
        handle: HologramHandle,
        alignment: TextAlignment,
    ): Result<Unit, HologramError> = mutateText(handle, "updateTextAlignment") { entity, current, textKind ->
        live[handle] = current.copy(kind = textKind.copy(alignment = alignment))
        entity.alignment = toBukkitAlignment(alignment)
    }

    override fun updateTextSeeThrough(
        handle: HologramHandle,
        seeThrough: Boolean,
    ): Result<Unit, HologramError> = mutateText(handle, "updateTextSeeThrough") { entity, current, textKind ->
        live[handle] = current.copy(kind = textKind.copy(seeThrough = seeThrough))
        entity.isSeeThrough = seeThrough
    }

    override fun updateTextShadowed(
        handle: HologramHandle,
        shadowed: Boolean,
    ): Result<Unit, HologramError> = mutateText(handle, "updateTextShadowed") { entity, current, textKind ->
        live[handle] = current.copy(kind = textKind.copy(shadowed = shadowed))
        entity.isShadowed = shadowed
    }

    override fun updateTextDefaultBackground(
        handle: HologramHandle,
        hasBackground: Boolean,
    ): Result<Unit, HologramError> = mutateText(handle, "updateTextDefaultBackground") { entity, current, textKind ->
        live[handle] = current.copy(kind = textKind.copy(defaultBackground = hasBackground))
        entity.isDefaultBackground = hasBackground
    }

    // ─── Item-specific updaters (Wave 5transitions, ADR-0115 §A) ──────────

    override fun updateItemId(handle: HologramHandle, itemId: ItemId): Result<Unit, HologramError> =
        mutateItem(handle, "updateItemId") { entity, current, itemKind ->
            live[handle] = current.copy(kind = itemKind.copy(itemId = itemId))
            entity.setItemStack(resolveItemStack(itemId))
        }

    override fun updateItemDisplayMode(
        handle: HologramHandle,
        mode: ItemDisplayMode,
    ): Result<Unit, HologramError> = mutateItem(handle, "updateItemDisplayMode") { entity, current, itemKind ->
        live[handle] = current.copy(kind = itemKind.copy(displayMode = mode))
        entity.itemDisplayTransform = toBukkitItemTransform(mode)
    }

    // ─── Block-specific updater (Wave 5transitions, ADR-0115 §A) ──────────

    override fun updateBlockData(
        handle: HologramHandle,
        blockKey: String,
    ): Result<Unit, HologramError> {
        val blockData = try {
            Bukkit.createBlockData(blockKey)
        } catch (ex: IllegalArgumentException) {
            return Result.failure(HologramError.Storage("invalid blockKey: $blockKey (${ex.message})"))
        }
        return mutateBlock(handle, "updateBlockData") { entity, current, blockKind ->
            live[handle] = current.copy(kind = blockKind.copy(blockKey = blockKey))
            entity.block = blockData
        }
    }

    // ─── Reads ────────────────────────────────────────────────────────────

    override fun viewers(handle: HologramHandle): Collection<PlayerId> {
        val current = live[handle] ?: return emptyList()
        return when (val scope = current.visibilityScope) {
            is HologramVisibilityScope.Everyone ->
                server.onlinePlayers.map { PlayerId(it.uniqueId) }
            is HologramVisibilityScope.PerPlayer ->
                scope.playerIds.filter { id -> server.getPlayer(id.uuid) != null }
            // Wave 6g-PerTeam (ADR-0169) — resolve roster via the lazy
            // IMatchRepository lookup. Returns empty when the resolver
            // isn't wired (test / pre-Match-load); matches the InMemory
            // variant's behaviour for that case.
            is HologramVisibilityScope.PerTeam ->
                resolveTeamRoster(scope)
                    ?.filter { id -> server.getPlayer(id.uuid) != null }
                    ?: emptyList()
        }
    }

    override fun all(): Collection<HologramHandle> = live.keys.toList()

    override fun snapshot(handle: HologramHandle): Hologram? = live[handle]

    // ─── Internal helpers ─────────────────────────────────────────────────

    /**
     * Common write-path scaffold for the 11 "any-kind" updaters.
     * Resolves the [Display] entity, runs [block] under main-thread guard,
     * and translates a missing entity to [HologramError.UnknownHandle].
     */
    private inline fun mutate(
        handle: HologramHandle,
        op: String,
        block: (Display, Hologram) -> Unit,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        if (!server.isPrimaryThread) {
            return Result.failure(HologramError.Storage("off-main: $op"))
        }
        val entity = entities[handle]?.let(server::getEntity) as? Display
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        block(entity, current)
        return Result.success(Unit)
    }

    /**
     * Type-mismatched-aware write-path scaffold for the 8 Text-specific
     * updaters. Checks the shadow [Hologram.kind] (cheaper + atomic with the
     * InMemory contract) rather than `entity is TextDisplay` — they should
     * agree post-[spawn], but the shadow map is the authoritative source.
     */
    private inline fun mutateText(
        handle: HologramHandle,
        op: String,
        block: (TextDisplay, Hologram, HologramKind.Text) -> Unit,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        val kind = current.kind
        if (kind !is HologramKind.Text) {
            return Result.failure(
                HologramError.TypeMismatch(
                    expected = HologramKind.Text::class,
                    actual = kind::class,
                ),
            )
        }
        if (!server.isPrimaryThread) {
            return Result.failure(HologramError.Storage("off-main: $op"))
        }
        val entity = entities[handle]?.let(server::getEntity) as? TextDisplay
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        block(entity, current, kind)
        return Result.success(Unit)
    }

    private inline fun mutateItem(
        handle: HologramHandle,
        op: String,
        block: (ItemDisplay, Hologram, HologramKind.Item) -> Unit,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        val kind = current.kind
        if (kind !is HologramKind.Item) {
            return Result.failure(
                HologramError.TypeMismatch(
                    expected = HologramKind.Item::class,
                    actual = kind::class,
                ),
            )
        }
        if (!server.isPrimaryThread) {
            return Result.failure(HologramError.Storage("off-main: $op"))
        }
        val entity = entities[handle]?.let(server::getEntity) as? ItemDisplay
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        block(entity, current, kind)
        return Result.success(Unit)
    }

    private inline fun mutateBlock(
        handle: HologramHandle,
        op: String,
        block: (BlockDisplay, Hologram, HologramKind.Block) -> Unit,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        val kind = current.kind
        if (kind !is HologramKind.Block) {
            return Result.failure(
                HologramError.TypeMismatch(
                    expected = HologramKind.Block::class,
                    actual = kind::class,
                ),
            )
        }
        if (!server.isPrimaryThread) {
            return Result.failure(HologramError.Storage("off-main: $op"))
        }
        val entity = entities[handle]?.let(server::getEntity) as? BlockDisplay
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        block(entity, current, kind)
        return Result.success(Unit)
    }

    // ─── Apply: domain → Bukkit ───────────────────────────────────────────

    private fun applyBaseFields(entity: Display, hologram: Hologram) {
        entity.transformation = toBukkitTransformation(hologram.transform)
        entity.billboard = toBukkitBillboard(hologram.billboard)
        entity.viewRange = hologram.viewRangeBlocks
        entity.shadowRadius = hologram.shadowRadius
        entity.shadowStrength = hologram.shadowStrength
        entity.displayWidth = hologram.displayWidth
        entity.displayHeight = hologram.displayHeight
        entity.interpolationDuration = hologram.interpolation.durationTicks
        entity.interpolationDelay = hologram.interpolation.delayTicks
        entity.teleportDuration = hologram.interpolation.teleportDurationTicks.coerceAtMost(59)
        entity.brightness = hologram.brightness?.let { Display.Brightness(it.blockLight, it.skyLight) }
        entity.glowColorOverride = hologram.glowColor?.let { toBukkitColor(it) }
    }

    private fun applyText(entity: TextDisplay, kind: HologramKind.Text, viewer: PlayerId?) {
        entity.text = renderLines(kind.lines, viewer)
        entity.lineWidth = kind.lineWidth
        entity.backgroundColor = kind.backgroundColor?.let { toBukkitColor(it) }
        entity.textOpacity = kind.textOpacity
        entity.isShadowed = kind.shadowed
        entity.isSeeThrough = kind.seeThrough
        entity.isDefaultBackground = kind.defaultBackground
        entity.alignment = toBukkitAlignment(kind.alignment)
    }

    private fun applyItem(entity: ItemDisplay, kind: HologramKind.Item) {
        entity.setItemStack(resolveItemStack(kind.itemId))
        entity.itemDisplayTransform = toBukkitItemTransform(kind.displayMode)
    }

    private fun applyBlock(entity: BlockDisplay, kind: HologramKind.Block) {
        // Caller already constructed the entity via spawn(...) which set
        // BlockData via this same path indirectly — but `world.spawn` for
        // BlockDisplay does NOT set the block field; we must set it here.
        entity.block = Bukkit.createBlockData(kind.blockKey)
    }

    // ─── Domain → Bukkit type conversions ─────────────────────────────────

    private fun toBukkitTransformation(transform: HologramTransform): Transformation {
        // Vec3 (double) → Vector3f (single). Bukkit's Transformation uses
        // single-precision throughout; domain keeps double for math fidelity
        // and narrows at the boundary.
        val translation = Vector3f(
            transform.translation.x.toFloat(),
            transform.translation.y.toFloat(),
            transform.translation.z.toFloat(),
        )
        val scale = Vector3f(
            transform.scale.x.toFloat(),
            transform.scale.y.toFloat(),
            transform.scale.z.toFloat(),
        )
        // JOML Quaternionf passes straight through — Bukkit's Transformation
        // constructor takes JOML quaternions directly (Paper 1.20+).
        return Transformation(translation, transform.leftRotation, scale, transform.rightRotation)
    }

    private fun toBukkitColor(color: HologramColor): Color {
        // Bukkit Color channels are 0..255 int; HologramColor stores raw Byte
        // (-128..127). The `and 0xFF` mask widens to unsigned int per the
        // HologramColor KDoc.
        return Color.fromARGB(
            color.alpha.toInt() and 0xFF,
            color.red.toInt() and 0xFF,
            color.green.toInt() and 0xFF,
            color.blue.toInt() and 0xFF,
        )
    }

    private fun toBukkitBillboard(billboard: BillboardMode): Display.Billboard = when (billboard) {
        BillboardMode.FIXED -> Display.Billboard.FIXED
        BillboardMode.VERTICAL -> Display.Billboard.VERTICAL
        BillboardMode.HORIZONTAL -> Display.Billboard.HORIZONTAL
        BillboardMode.CENTER -> Display.Billboard.CENTER
    }

    private fun toBukkitAlignment(alignment: TextAlignment): TextDisplay.TextAlignment = when (alignment) {
        TextAlignment.LEFT -> TextDisplay.TextAlignment.LEFT
        TextAlignment.CENTER -> TextDisplay.TextAlignment.CENTER
        TextAlignment.RIGHT -> TextDisplay.TextAlignment.RIGHT
    }

    private fun toBukkitItemTransform(mode: ItemDisplayMode): ItemDisplay.ItemDisplayTransform = when (mode) {
        ItemDisplayMode.NONE -> ItemDisplay.ItemDisplayTransform.NONE
        ItemDisplayMode.THIRD_PERSON_LEFT_HAND -> ItemDisplay.ItemDisplayTransform.THIRDPERSON_LEFTHAND
        ItemDisplayMode.THIRD_PERSON_RIGHT_HAND -> ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND
        ItemDisplayMode.FIRST_PERSON_LEFT_HAND -> ItemDisplay.ItemDisplayTransform.FIRSTPERSON_LEFTHAND
        ItemDisplayMode.FIRST_PERSON_RIGHT_HAND -> ItemDisplay.ItemDisplayTransform.FIRSTPERSON_RIGHTHAND
        ItemDisplayMode.HEAD -> ItemDisplay.ItemDisplayTransform.HEAD
        ItemDisplayMode.GUI -> ItemDisplay.ItemDisplayTransform.GUI
        ItemDisplayMode.GROUND -> ItemDisplay.ItemDisplayTransform.GROUND
        ItemDisplayMode.FIXED -> ItemDisplay.ItemDisplayTransform.FIXED
    }

    /**
     * Resolve [itemId] to a Bukkit [ItemStack] via the three-step fallback
     * chain documented in the class KDoc. Lookup never throws — silently
     * falls back to [Material.STONE] (logged once) so the entity still spawns.
     */
    private fun resolveItemStack(itemId: ItemId): ItemStack {
        itemRegistry?.find(itemId)?.let { spec -> itemMaterializer()?.buildFor(spec, null, 1) }?.let { return it }
        val fromSlug = Material.matchMaterial("minecraft:${itemId.value.replace('-', '_')}")
        val resolved = fromSlug ?: run {
            if (!itemFallbackStoneLogged) {
                log.info(
                    "PaperHologramGateway.resolveItemStack: itemId={} not resolved via registry " +
                        "nor vanilla-slug fallback; using Material.STONE placeholder (ADR-0163 §item-fallback)",
                    itemId,
                )
                itemFallbackStoneLogged = true
            }
            Material.STONE
        }
        return ItemStack(resolved)
    }

    /**
     * Render the i18n [lines] for a TextDisplay. Joins with `\n` per port KDoc.
     * Without an [IMessageRenderer] the raw [MessageKey.value] is used —
     * useful for tests + dev environments before i18n is wired.
     */
    private fun textViewer(hologram: Hologram): PlayerId? =
        (hologram.visibilityScope as? HologramVisibilityScope.PerPlayer)?.playerIds?.singleOrNull()

    private fun renderLines(lines: List<MessageKey>, viewer: PlayerId? = null): String {
        val renderer = messageRenderer
        return lines.joinToString(separator = "\n") { key ->
            renderer?.render(playerId = viewer, key = key) ?: key.value
        }
    }

    /**
     * Wave 6g-visibility (ADR-0168) — Paper-native per-viewer entity
     * visibility via `Entity.setVisibleByDefault` + `Player.showEntity` /
     * `Player.hideEntity`. No external packet library required (carves out
     * the per-viewer-visibility clause of ADR-0095's PacketEvents strategy).
     *
     * Per-scope behaviour:
     *  - [HologramVisibilityScope.Everyone] — no-op. `isVisibleByDefault`
     *    stays `true` (Bukkit spawn default) so every tracker receives the
     *    entity. Used to clear a prior PerPlayer / PerTeam scope.
     *  - [HologramVisibilityScope.PerPlayer] — `isVisibleByDefault = false`,
     *    then `showEntity(plugin, entity)` per listed online player. Offline
     *    players in the set are tracked in `live[handle]` and get
     *    `showEntity` on next `PlayerJoinEvent` ([JoinReapplyListener]).
     *  - [HologramVisibilityScope.PerTeam] — still stubbed (ADR-0168 §C).
     *    Team-roster resolution lives in `:plugin:match` which `:plugin:core`
     *    cannot import (ADR-0022). Implementation lands in a follow-up wave
     *    that introduces an `ITeamRosterLookup`-shaped port + a Match-side
     *    in-memory impl.
     *
     * `plugin` may be null in tests / pre-`start` use. PerPlayer falls back
     * to logging at WARN (the visibility intent is recorded in `live[handle]`
     * for later resync once `start` is called); the InMemory variant offers
     * the same diagnostic and tests against it cover the contract.
     */
    private fun applyVisibility(entity: Entity, scope: HologramVisibilityScope) {
        when (scope) {
            is HologramVisibilityScope.Everyone -> {
                // Reset to "spawn for everyone" — re-applies after a prior
                // PerPlayer / PerTeam swap so the new tracker default works.
                entity.isVisibleByDefault = true
            }
            is HologramVisibilityScope.PerPlayer -> {
                val plugin = this.plugin
                if (plugin == null) {
                    log.warn(
                        "PaperHologramGateway: applyVisibility(PerPlayer) before start() — " +
                            "entity {} stays globally visible until plugin reference is wired",
                        entity.uniqueId,
                    )
                    return
                }
                entity.isVisibleByDefault = false
                scope.playerIds.forEach { id ->
                    server.getPlayer(id.uuid)?.showEntity(plugin, entity)
                }
            }
            is HologramVisibilityScope.PerTeam -> {
                val plugin = this.plugin
                if (plugin == null) {
                    log.warn(
                        "PaperHologramGateway: applyVisibility(PerTeam) before start() — " +
                            "entity {} stays globally visible until plugin reference is wired",
                        entity.uniqueId,
                    )
                    return
                }
                val roster = resolveTeamRoster(scope)
                if (roster == null) {
                    // Resolver returned null — either the IMatchRepository isn't
                    // registered yet (test mode) or the match/team id is stale.
                    // Same posture as the pre-start branch: log + keep visible.
                    if (!perTeamScopeStubLogged) {
                        log.info(
                            "PaperHologramGateway: HologramVisibilityScope.PerTeam(matchId={}, teamId={}) " +
                                "could not be resolved — IMatchRepository unavailable or match/team not found. " +
                                "Entity stays globally visible (ADR-0169 fallback).",
                            scope.matchId.value,
                            scope.teamId.value,
                        )
                        perTeamScopeStubLogged = true
                    }
                    return
                }
                entity.isVisibleByDefault = false
                roster.forEach { id ->
                    server.getPlayer(id.uuid)?.showEntity(plugin, entity)
                }
            }
        }
    }

    /**
     * Resolve `(matchId, teamId) → Set<PlayerId>` via [matchRepositoryLookup].
     * Returns `null` if the resolver isn't wired, the match doesn't exist,
     * or the team is unknown — caller treats `null` as "visibility intent
     * recorded but unenforced". Returns an empty set if the team exists but
     * has zero members (valid: visibility intent is "nobody sees it").
     */
    private fun resolveTeamRoster(scope: HologramVisibilityScope.PerTeam): Set<PlayerId>? {
        val matchRepo = matchRepositoryLookup() ?: return null
        val match = matchRepo.findById(scope.matchId) ?: return null
        val team = match.teams.firstOrNull { it.id == scope.teamId } ?: return null
        return team.members
    }

    /**
     * Wire the [JavaPlugin] reference used by [Player.showEntity] /
     * [Player.hideEntity] and register the [JoinReapplyListener] (Wave 6g,
     * ADR-0168). Must be called once from `AsteraCorePlugin.onEnable` after
     * the gateway is constructed. Calling twice replaces the previous
     * registration but Bukkit will keep both Listener instances unless
     * `HandlerList.unregisterAll(plugin)` runs first — production code
     * should call this once and trust `onDisable` to clean up.
     */
    public fun start(plugin: JavaPlugin) {
        this.plugin = plugin
        plugin.server.pluginManager.registerEvents(JoinReapplyListener(), plugin)
    }

    /**
     * Re-apply [HologramVisibilityScope.PerPlayer] visibility on player join.
     * Bukkit's `Player.showEntity` state is per-session; without this
     * listener a player who reconnects sees every hologram (including those
     * that should be hidden from them).
     *
     * Runs `MONITOR` priority — purely additive, never cancels. Walks the
     * live shadow map filtering to PerPlayer scopes containing the joining
     * player; for each match, looks up the spawned entity (skipping any
     * that despawned mid-flight) and re-applies the visibility tag.
     *
     * PerTeam scopes are not handled here — they fall under the same
     * cross-plugin resolver gap that [applyVisibility] documents.
     */
    private inner class JoinReapplyListener : Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public fun onJoin(event: PlayerJoinEvent) {
            val plugin = this@PaperHologramGateway.plugin ?: return
            val playerId = PlayerId(event.player.uniqueId)
            live.forEach { (handle, hologram) ->
                val isViewer = when (val scope = hologram.visibilityScope) {
                    is HologramVisibilityScope.Everyone -> false
                    is HologramVisibilityScope.PerPlayer -> playerId in scope.playerIds
                    // Wave 6g-PerTeam (ADR-0169) — resolve via the lazy
                    // matchRepositoryLookup; null roster (resolver not
                    // wired, match gone) treats the player as not-a-viewer
                    // so the global default-visibility holds.
                    is HologramVisibilityScope.PerTeam ->
                        resolveTeamRoster(scope)?.contains(playerId) ?: false
                }
                if (!isViewer) return@forEach
                val entityId = entities[handle] ?: return@forEach
                val entity = server.getEntity(entityId) ?: return@forEach
                event.player.showEntity(plugin, entity)
            }
        }
    }
}
