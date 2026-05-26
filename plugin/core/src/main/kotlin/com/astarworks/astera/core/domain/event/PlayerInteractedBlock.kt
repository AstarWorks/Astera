package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockInteractAction
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain-level "the player clicked / stepped on a block" event
 * (Wave 5world-events, ADR-0111).
 *
 * The single source of truth for **skill-trigger by-block-click**, **custom
 * block listener** (Wave 5x), **region permission interaction guard**
 * (Wave 5n), and **physical-trigger pressure plate** dispatch. [action]
 * carries the click semantic so the routing layer does not have to re-derive
 * intent from raw Bukkit action enums.
 *
 * Block identity is exposed by id (`blockId`, namespaced) rather than a
 * Bukkit `Material` to keep this layer Bukkit-free per ADR-0001 / 0002.
 * The producer (Bukkit `PlayerInteractEvent` → adapter) lands in Wave 6.
 */
public data class PlayerInteractedBlock(
    val playerId: PlayerId,
    val world: WorldId,
    val position: Vec3,
    val blockId: String,
    val action: BlockInteractAction,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
