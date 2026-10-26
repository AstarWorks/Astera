package com.astarworks.astera.core.adapter.paper.world

import java.util.concurrent.CompletableFuture
import com.astarworks.astera.core.application.port.outbound.regionScope
import kotlin.time.Duration
import kotlin.math.floor

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.WorldGatewayError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.ParticleViewers
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Server
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IWorldGateway] (Wave 6b-1, ADR-0156) — overlays
 * [com.astarworks.astera.core.application.service.effect.NoopWorldGatewayForEffects]
 * at priority 100 once registered in `AsteraCorePlugin.onEnable`.
 *
 * # Bukkit mapping
 *
 *  - [placeBlock] → `world.getBlockAt(x, y, z).type = Material.matchMaterial(type.key)`.
 *    Resolves the [BlockType.key] (`namespace:identifier`) through the same
 *    `Material.matchMaterial` lookup used by [com.astarworks.astera.core.adapter.paper.gui.PaperItemTemplateRenderer]
 *    and [com.astarworks.astera.core.adapter.paper.entity.PaperPlayer] so
 *    `minecraft:` / vendor prefixes work identically across adapters.
 *  - [removeBlock] → set the block to [Material.AIR]. No block-update event
 *    is suppressed; physics neighbours retick as in vanilla.
 *  - [blockAt] → reads `world.getBlockAt(x, y, z).type` and rebuilds a
 *    [BlockType] from `material.key.asString()`. Returns `null` when the
 *    world is unknown (offline / unloaded) per the port contract; an `AIR`
 *    block still round-trips as `BlockType("minecraft:air")`.
 *  - [spawnParticle] → `world.spawnParticle(particle, loc, count, offsetX,
 *    offsetY, offsetZ, extra, data)` where `particle` is resolved by stripping the
 *    `minecraft:` namespace and `Particle.valueOf(name.uppercase())`. Offset and
 *    extra come from [ParticleSpec]; the data object is chosen by `Particle.dataType`
 *    ([PaperParticleData]: dust, block, item, trail, vibration, ...). `count == 0` is vanilla's
 *    directional form (offset = velocity). `force` and `viewers` map to the `force` flag and the
 *    receivers list.
 *  - [playSound] → resolves through `Registry.SOUNDS.get(NamespacedKey)`
 *    because `Sound` migrated to the Bukkit Registry pattern in 1.21.
 *    Falls back to silent no-op (DEBUG-logged) when the sound key is
 *    unknown so authoring typos do not crash the call site.
 *  - [teleportAsync] → uses Paper `Player.teleportAsync(Location)`; this is
 *    the boolean-returning Wave 4.5 variant. The Wave 5h Result-returning
 *    variants ([spectatorSpawn] / [lobbySpawn]) below wrap the same call.
 *  - [spectatorSpawn] → Phase 1 stub. No `IMatchSpawnRegistry` port exists
 *    yet (the use cases that call this — [com.astarworks.astera.match.application.usecase.spectator.EnterSpectatorUseCase],
 *    [com.astarworks.astera.match.application.usecase.match.ResumeMatchUseCase] —
 *    have always been gated by the placeholder no-op). For now we delegate
 *    to [lobbySpawn] so the player at least lands somewhere safe; a follow-up
 *    Wave (post Wave 6b inventory bridges) introduces the per-match spawn
 *    registry and rewires this to read the arena center.
 *  - [lobbySpawn] → reads the lobby world's `world.spawnLocation`. The
 *    lobby world is the procedural sky-island world `lobby` (ADR-0180) when
 *    this backend hosts it, else `server.worlds.first()` (the main world).
 *
 * # ADR-0149 §C invariant
 *
 * This gateway never sets player HP, ticks damage, or fires
 * `EntityDamageEvent`. Teleport is a position write only (Bukkit's
 * `Player.teleport` does not trigger damage). Block writes use the
 * vanilla setter so any falling-block / liquid physics is Paper-owned,
 * not gateway-owned.
 *
 * # Threading
 *
 * Block writes and sounds dispatch to the affected chunk. Particle delivery reads each
 * viewer on its Entity scope. Zero-delay work runs inline on the current owner.
 * Synchronous block reads require the caller to own the chunk.
 *
 * # Offline / unloaded world semantics
 *
 *  - Unknown world ([Server.getWorld] returns `null`): unit-returning
 *    methods log DEBUG and drop; [blockAt] returns `null`; the Wave 5h
 *    Result methods return [WorldGatewayError.Storage] with a
 *    `world not loaded` detail (the sealed surface lacks a dedicated
 *    `WorldNotFound` variant — see [WorldGatewayError]).
 *  - Offline player on [teleport]: returns `false` per port contract.
 *  - Offline player on [spectatorSpawn] / [lobbySpawn]: returns
 *    [WorldGatewayError.UnknownPlayer].
 *
 * # Registration
 *
 * Priority 100 in `AsteraCorePlugin.onEnable` (overlays
 * `NoopWorldGatewayForEffects` priority 0). See Phase C wiring snippet in
 * ADR-0156.
 */
public class PaperWorldGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    @Suppress("UnusedPrivateProperty")
    private val eventBus: IDomainEventBus,
    encodeParticles: Boolean = false,
    private val lobbyPosition: () -> Vec3 = { Vec3(0.5, 65.0, 6.5) },
) : IWorldGateway {

    private val particleFanout = if (encodeParticles) PacketParticleFanout() else null
    private val log = LoggerFactory.getLogger(javaClass)

    override fun placeBlock(world: WorldId, at: Vec3, type: BlockType) {
        val w = resolveWorld(world) ?: run {
            log.debug("PaperWorldGateway.placeBlock: world {} not loaded", world)
            return
        }
        val material = Material.matchMaterial(type.key) ?: run {
            log.debug("PaperWorldGateway.placeBlock: unknown material {}", type.key)
            return
        }
        scheduler.run(regionScope(world, at), Duration.ZERO) {
            assertWorldOwnership(scheduler, regionScope(world, at))
            w.getBlockAt(kotlin.math.floor(at.x).toInt(), kotlin.math.floor(at.y).toInt(), kotlin.math.floor(at.z).toInt()).type = material
        }
    }

    override fun removeBlock(world: WorldId, at: Vec3) {
        val w = resolveWorld(world) ?: run {
            log.debug("PaperWorldGateway.removeBlock: world {} not loaded", world)
            return
        }
        scheduler.run(regionScope(world, at), Duration.ZERO) {
            assertWorldOwnership(scheduler, regionScope(world, at))
            w.getBlockAt(kotlin.math.floor(at.x).toInt(), kotlin.math.floor(at.y).toInt(), kotlin.math.floor(at.z).toInt()).type = Material.AIR
        }
    }

    override fun blockAt(world: WorldId, at: Vec3): BlockType? {
        val w = resolveWorld(world) ?: return null
        assertWorldOwnership(scheduler, regionScope(world, at))
        val mat = w.getBlockAt(floor(at.x).toInt(), floor(at.y).toInt(), floor(at.z).toInt()).type
        return BlockType(mat.key.asString())
    }

    override fun spawnParticle(world: WorldId, at: Vec3, particle: ParticleSpec) {
        val w = resolveWorld(world) ?: run {
            log.debug("PaperWorldGateway.spawnParticle: world {} not loaded", world)
            return
        }
        val bukkitParticle = resolveParticle(particle.key) ?: run {
            log.debug("PaperWorldGateway.spawnParticle: unknown particle {}", particle.key)
            return
        }
        val data = PaperParticleData.map(bukkitParticle, particle, w, ::blockData) ?: run {
            log.debug("PaperWorldGateway.spawnParticle: {} needs data this spec cannot supply", particle.key)
            return
        }
        // The online-player collection supplies identities only; all live reads happen on Entity.
        val source = particle.source
        val candidates = if (source != null && particle.viewers == ParticleViewers.CASTER) {
            listOfNotNull(onlinePlayer(source))
        } else server.onlinePlayers.toList()
        for (viewer in candidates) {
            if (source != null && particle.viewers == ParticleViewers.OTHERS && viewer.uniqueId == source.uuid) continue
            scheduler.run(ExecutionScope.Entity(viewer.uniqueId), Duration.ZERO) delivery@ {
                if (viewer.world != w) return@delivery
                val location = viewer.location
                val squared = Vec3(location.x, location.y, location.z).distanceSquared(at)
                val distance = if (particle.force) 512.0 else 32.0
                if (squared > distance * distance) return@delivery
                if (com.astarworks.astera.core.application.service.observability.CombatTickBudget.overrun(scheduler.currentTick())) {
                    val stride = if (squared <= 16.0 * 16.0) 1 else if (squared <= 32.0 * 32.0) 2 else 4
                    if (com.astarworks.astera.core.application.service.observability.CombatTickBudget.emission() % stride != 0L) {
                        com.astarworks.astera.core.application.service.observability.ExecutionCosts.increment("combat_vfx_lod_dropped")
                        return@delivery
                    }
                }
                if (particleFanout?.send(w, at, bukkitParticle, particle, data.value, listOf(viewer)) == true) return@delivery
                viewer.spawnParticle(bukkitParticle, at.x, at.y, at.z, particle.count,
                    particle.offset.x, particle.offset.y, particle.offset.z, particle.extra, data.value)
            }
        }
    }

    private fun blockData(key: String): BlockData? = runCatching { server.createBlockData(key) }.getOrNull()

    override fun playSound(world: WorldId, at: Vec3, sound: SoundSpec) {
        val w = resolveWorld(world) ?: run {
            log.debug("PaperWorldGateway.playSound: world {} not loaded", world)
            return
        }
        val bukkitSound = resolveSound(sound.key) ?: run {
            log.debug("PaperWorldGateway.playSound: unknown sound {}", sound.key)
            return
        }
        val loc = toLocation(w, at)
        scheduler.run(regionScope(world, at), Duration.ZERO) {
            assertWorldOwnership(scheduler, regionScope(world, at))
            w.playSound(loc, bukkitSound, sound.volume.toFloat(), sound.pitch.toFloat())
        }
    }

    override fun teleportAsync(playerId: PlayerId, at: Vec3, world: WorldId?, yaw: Float?, pitch: Float?): CompletableFuture<Boolean> {
        val completed = CompletableFuture<Boolean>()
        val action = action@ {
            val player = onlinePlayer(playerId)
            if (player == null) { completed.complete(false); return@action }
            val targetWorld = if (world == null) player.world else resolveWorld(world)
            if (targetWorld == null) { completed.complete(false); return@action }
            val loc = toLocation(targetWorld, at)
            loc.yaw = yaw ?: player.location.yaw
            loc.pitch = pitch ?: player.location.pitch
            // Completion is thread-safe and dependents choose their own owner, so no extra entity hop that could be retired.
            player.teleportAsync(loc).whenComplete { moved, failure ->
                completed.complete(failure == null && moved == true)
            }
        }
        val reference = onlinePlayer(playerId)
        if (reference != null && org.bukkit.Bukkit.isOwnedByCurrentRegion(reference)) action()
        else scheduler.run(ExecutionScope.Entity(playerId.uuid), kotlin.time.Duration.ZERO, action).onRetired { completed.complete(false) }
        return completed
    }

    override fun spectatorSpawn(
        matchId: MatchId,
        playerId: PlayerId,
    ): CompletableFuture<Result<Unit, WorldGatewayError>> {
        // Phase 1 stub — no per-match spawn registry yet. Fall back to
        // lobby spawn so the player lands somewhere safe; follow-up wave
        // introduces IMatchSpawnRegistry and rewires this to read the
        // arena-center spectator vantage point.
        log.debug(
            "PaperWorldGateway.spectatorSpawn: no match-spawn registry yet, falling back to lobbySpawn for match={} player={}",
            matchId,
            playerId,
        )
        return lobbySpawn(playerId)
    }

    override fun lobbySpawn(playerId: PlayerId): CompletableFuture<Result<Unit, WorldGatewayError>> {
        val player = onlinePlayer(playerId)
            ?: return CompletableFuture.completedFuture(Result.failure(WorldGatewayError.UnknownPlayer(playerId)))
        // The procedural sky-island world (ADR-0180) when this backend hosts it, else the main world.
        val lobby = server.getWorld(com.astarworks.astera.core.application.config.LobbyWorldYamlConfig.DEFAULT_ID)
            ?: server.worlds.firstOrNull()
            ?: return CompletableFuture.completedFuture(Result.failure(WorldGatewayError.Storage("no worlds loaded")))
        val target = lobbyPosition()
        return teleportAsync(playerId, target, WorldId(lobby.name), 180f, 0f).thenApply { moved ->
            if (moved) Result.success(Unit) else Result.failure(WorldGatewayError.Storage("teleport rejected"))
        }

    }

    // ---- helpers --------------------------------------------------------

    private fun resolveWorld(worldId: WorldId): World? = server.getWorld(worldId.value)

    private fun toLocation(world: World, vec: Vec3): Location =
        Location(world, vec.x, vec.y, vec.z)

    private fun onlinePlayer(playerId: PlayerId): Player? = server.getPlayer(playerId.uuid)

    /**
     * Resolve a vendor-neutral particle key (`minecraft:flame`) to a Bukkit
     * [Particle]. Strips the namespace and looks the identifier up in the
     * enum; Paper 1.21 still exposes `Particle` as an enum (unlike `Sound`
     * which migrated to Registry).
     */
    private fun resolveParticle(key: String): Particle? {
        val identifier = key.substringAfter(':', missingDelimiterValue = key)
        return runCatching { Particle.valueOf(identifier.uppercase()) }.getOrNull()
    }

    /**
     * Resolve a sound key (`minecraft:entity.lightning_bolt.thunder`) to a
     * Bukkit [Sound] through `Registry.SOUNDS.get(NamespacedKey)`. `Sound`
     * migrated from an enum to a Registry-backed interface in Paper 1.21,
     * so `Sound.valueOf` is no longer the canonical lookup. Returns `null`
     * on malformed or unknown key.
     */
    private fun resolveSound(key: String): Sound? {
        val nk = NamespacedKey.fromString(key) ?: return null
        return Registry.SOUNDS.get(nk)
    }
}
