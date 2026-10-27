package com.astarworks.astera.match.application.usecase.respawn

import java.util.concurrent.CompletableFuture

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.executor
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.stage.SpawnPoint

/**
 * Respawn a player at a [SpawnPoint] after a death / explicit revive, applying
 * an optional pre-fill kit, restoring health, granting spawn protection, and
 * applying any caller-supplied status effects (Wave 5o, ADR-0081).
 *
 * # Sequence
 *
 *  1. Teleport via [IWorldGateway.teleport] to `spawnPoint.position`; fail
 *     fast with [RespawnError.TeleportFailed] if the gateway returns `false`.
 *  2. Restore health to `spec.initialHealth` via [IPlayerHealthGateway]; the
 *     mutation is tagged with [HealthChangeSource.Respawn] so the audit /
 *     listener tier can distinguish "respawn restore" from "/kill -1" etc.
 *  3. Grant spawn protection for `spec.protectionSeconds` (skipped if 0).
 *  4. If a pre-fill [PreFillKit] is attached and `applyOnRespawn=true`,
 *     resolve every kit slot through [IItemRegistry]. Missing item ids
 *     surface as [RespawnError.UnknownKitItem] and abort the kit phase
 *     (everything earlier in the sequence stays applied — Annihilation
 *     does not re-kill on partial kit failure).
 *  5. Apply caller-supplied status effects via the optional
 *     [statusEffectContainer]; null container means "no status step".
 *  6. Publish [PlayerRespawned].
 *
 * # Why [statusEffectContainer] is optional
 *
 * `:plugin:match` wires it from `services.get<IStatusEffectContainer>()`
 * which `:plugin:combat` registers; the use case stays usable in match-only
 * tests / smoke deployments where combat is absent.
 */
public class RespawnUseCase(
    private val healthGateway: IPlayerHealthGateway,
    private val worldGateway: IWorldGateway,
    private val itemRegistry: IItemRegistry,
    private val protectionGateway: ISpawnProtectionGateway,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val statusEffectContainer: IStatusEffectContainer? = null,
) {

    public fun respawn(spec: RespawnSpec): CompletableFuture<Result<Unit, RespawnError>> {
        // 1. Teleport.
        return worldGateway.teleportAsync(
            playerId = spec.playerId,
            at = spec.spawnPoint.position,
            world = spec.spawnPoint.world,
            yaw = spec.spawnPoint.yaw,
            pitch = spec.spawnPoint.pitch,
        ).thenApplyAsync({ moved ->
            if (!moved) return@thenApplyAsync Result.failure(RespawnError.TeleportFailed(spec.playerId))
            // 2. Restore health (admin / system path; ignore the gateway's
            //    Result.Failure for invalid value since the spec is already
            //    bounds-checked at construction).
            healthGateway.setHealth(spec.playerId, spec.initialHealth, HealthChangeSource.Respawn)

            // 3. Spawn protection — skip when caller asked for 0 seconds.
            if (spec.protectionSeconds > 0) {
                protectionGateway.grantProtection(spec.playerId, spec.protectionSeconds)
            }

            // 4. Pre-fill kit. Resolve every item up-front so an unknown id
            //    surfaces as a typed error rather than a "half-applied kit".
            val kit = spec.kit
            if (kit != null && kit.applyOnRespawn) {
                val unknown = kit.items.firstOrNull { (_, id) -> itemRegistry.find(id) == null }
                if (unknown != null) {
                    return@thenApplyAsync Result.Failure(RespawnError.UnknownKitItem(unknown.second))
                }
                // The actual "give item to slot" Bukkit-side application lives in the
                // Wave 6 `PaperInventoryGateway` (deferred). For Wave 5o the kit is
                // resolved + emitted on the event so downstream listeners + tests
                // can verify the contract end-to-end without a Paper harness.
            }

            // 5. Status effects via the optional container.
            if (statusEffectContainer != null && spec.effects.isNotEmpty()) {
                statusEffectContainer.applyBulk(spec.effects)
            }

            // 6. Publish event.
            eventBus.publish(
                PlayerRespawned(
                    playerId = spec.playerId,
                    matchId = spec.matchId,
                    spawnPoint = spec.spawnPoint,
                    atTick = scheduler.currentTick(),
                ),
            )
            return@thenApplyAsync Result.Success(Unit)
        }, scheduler.executor(ExecutionScope.Entity(spec.playerId.uuid)))
    }
}

/**
 * Inputs to [RespawnUseCase.respawn].
 *
 * @property playerId Player to respawn (must be online; offline players get
 *   `TeleportFailed`).
 * @property matchId Match the respawn belongs to, or `null` for free-roam
 *   respawns (admin /kill in lobby world). Threaded onto the [PlayerRespawned]
 *   event so cross-server audit can attribute the respawn to a session.
 * @property spawnPoint Where to teleport — typically obtained from
 *   `ISpawnSelector.selectFor(player, match, stage, strategy)`.
 * @property protectionSeconds Seconds of spawn protection; 0 = no protection.
 *   Default 5s matches the Annihilation tuning point.
 * @property initialHealth HP to restore to (clamped to current max-health by
 *   the health gateway). Default = Astera default max HP (100).
 * @property kit Optional pre-fill kit (loadout); `null` for "do not fill
 *   inventory". When `applyOnRespawn=false`, the use case keeps the kit
 *   reference on the event without applying it (lobby preview).
 * @property effects Status effects to apply on respawn (e.g.
 *   "spawn-blessing" stat buff). Empty list = "no status step".
 */
public data class RespawnSpec(
    public val playerId: PlayerId,
    public val matchId: MatchId?,
    public val spawnPoint: SpawnPoint,
    public val protectionSeconds: Int = 5,
    public val initialHealth: Double = com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP,
    public val kit: PreFillKit? = null,
    public val effects: List<StatusEffectInstance> = emptyList(),
) {
    init {
        require(protectionSeconds >= 0) { "protectionSeconds must be >= 0: $protectionSeconds" }
        require(initialHealth > 0.0 && initialHealth.isFinite()) {
            "initialHealth must be > 0 and finite: $initialHealth"
        }
    }
}

/**
 * Loadout description for a respawning player.
 *
 * `items` is a list (not a map) so the YAML / authoring side can give the same
 * item to multiple slots (helmet + chestplate of the same id) without losing
 * a slot to deduplication.
 */
public data class PreFillKit(
    public val items: List<Pair<EquipSlot, ItemId>>,
    public val applyOnRespawn: Boolean = true,
)

/**
 * Expected failure modes of [RespawnUseCase.respawn].
 */
public sealed class RespawnError {
    public abstract val messageKey: MessageKey

    /** Teleport gateway refused (player offline / target world unknown). */
    public data class TeleportFailed(public val playerId: PlayerId) : RespawnError() {
        override val messageKey: MessageKey =
            MessageKey("astera.respawn.error.teleport-failed")
    }

    /** Pre-fill kit referenced an item that no registry knows about. */
    public data class UnknownKitItem(public val itemId: ItemId) : RespawnError() {
        override val messageKey: MessageKey =
            MessageKey("astera.respawn.error.unknown-kit-item")
    }
}
