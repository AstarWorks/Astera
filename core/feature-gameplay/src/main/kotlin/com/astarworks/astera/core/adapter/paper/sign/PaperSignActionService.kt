package com.astarworks.astera.core.adapter.paper.sign

import com.astarworks.astera.core.application.port.outbound.sign.ISignActionService
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.sign.SignAction
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Server
import org.bukkit.block.Sign
import org.slf4j.LoggerFactory
import kotlin.math.floor

/**
 * Paper concrete of [ISignActionService] (Wave 6a-7, ADR-0152) — the
 * routing table for `[ASTERA]` magic signs.
 *
 * # Why a Paper adapter at all
 *
 * The InMemory impl is a pure `(WorldId, Vec3) → SignAction` map and is
 * already the canonical home for sign bindings — there's no "Bukkit
 * native sign-binding API" to delegate to. The Paper adapter therefore
 * adds **one** thing that the InMemory impl cannot: at bind time it
 * validates that the target block in the live world actually carries a
 * Bukkit [Sign] blockstate, logging DEBUG when the binding doesn't
 * match (typo'd coordinate, block destroyed since the bind was
 * authored, hot-swap from another plugin replaced the sign with a
 * non-sign block).
 *
 * # Bukkit mapping
 *
 *  - `bindSignAction(world, pos, action)` →
 *    1. Reads `server.getWorld(world.value).getBlockAt(x, y, z).state`
 *       and validates the state is a Bukkit [Sign]. DEBUG-logs and
 *       proceeds anyway if not (the binding may have been authored
 *       ahead of placement; Wave 6b `SignChangeEvent` adapter rebinds
 *       on actual placement).
 *    2. Stores the binding in the in-memory `bindings` map (same shape
 *       as `InMemorySignActionService` — `(world, pos) → action`).
 *  - `unbindSign(world, pos)` → drops the entry from the map. Returns
 *    `true` iff anything was removed. No Bukkit-side write (vanilla
 *    sign text is not modified by Astera).
 *  - `lookup(world, pos)` → reads the map; the
 *    Wave 6a-6 `BukkitEventAdapter` calls this from its
 *    `PlayerInteractEvent` → `ISignInteractionDispatcher` path.
 *
 * # Coordinate convention
 *
 * Bukkit blocks are integer-coordinate, the domain `Vec3` is double-
 * precision (so the same type can encode entity / projectile / camera
 * positions). The adapter floors each axis (`Math.floor` toward
 * negative infinity, matching Bukkit's `Location.getBlockX()` semantics
 * for negative coords) to derive the block coordinate. Bindings
 * written with the same `Vec3.x` floored to the same integer collide
 * — by design, since signs are block-scale.
 *
 * # Offline / unloaded world semantics
 *
 * `bindSignAction` against a world the server doesn't know about
 * (server.getWorld returns null) skips the validation step and still
 * records the binding — by-design so YAML-driven preload bindings
 * don't require the world to be loaded at boot. DEBUG-logged.
 *
 * # Threading
 *
 * Bukkit `World.getBlockAt(...)` is main-thread-only. Same contract as
 * ADR-0144 / 0145 / 0146 / 0147 / 0148. The in-memory map itself is
 * not thread-safe (matches `InMemorySignActionService`); callers are
 * expected to bind/unbind/lookup from the main thread.
 *
 * # Phase 1 PoC limitations
 *
 *  - The validation step is **advisory only** (DEBUG log on mismatch,
 *    not rejection). Strict validation would reject bindings authored
 *    ahead of sign placement, which breaks the
 *    `ParseAndBindSignOnSignChangedListener` skeleton's stage-preload
 *    use case. Wave 6b's SignChangeEvent listener handles the
 *    placement-time rebinding flow.
 *  - No persistence: bindings live for the JVM lifetime only. The
 *    persistence overlay is `:plugin:persistence` territory.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCorePlugin.onEnable` (overlays
 * `InMemorySignActionService` priority 0). Same map shape so a
 * priority-flip during reload does not lose bindings *within a single
 * adapter*, but cross-adapter switchover does — re-publish bindings
 * from the authoritative producer (YAML preload / SignChangeEvent
 * listener).
 *
 * @see ISignActionService for the full contract.
 * @see com.astarworks.astera.core.application.service.sign.InMemorySignActionService
 *      for the priority-0 default.
 * @see PaperPlayerSpeedGateway for the sibling-pattern reference (Wave 6a-4, ADR-0147).
 */
public class PaperSignActionService(
    private val server: Server,
    private val scheduler: com.astarworks.astera.core.application.port.outbound.IScheduler,
) : ISignActionService {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class Slot(val world: WorldId, val blockX: Int, val blockY: Int, val blockZ: Int)

    private val bindings: MutableMap<Slot, SignAction> = java.util.concurrent.ConcurrentHashMap()

    override fun bindSignAction(world: WorldId, position: Vec3, action: SignAction) {
        val slot = slotOf(world, position)
        scheduler.run(com.astarworks.astera.core.application.port.outbound.regionScope(world, position), kotlin.time.Duration.ZERO) {
            validateBlockIsSign(world, slot)
        }
        bindings[slot] = action
    }

    override fun unbindSign(world: WorldId, position: Vec3): Boolean =
        bindings.remove(slotOf(world, position)) != null

    override fun lookup(world: WorldId, position: Vec3): SignAction? =
        bindings[slotOf(world, position)]

    /** Test helper: total binding count. */
    public val size: Int get() = bindings.size

    /**
     * Floors [position] to block coordinates (matching Bukkit
     * `Location.getBlockX/Y/Z`). Negative coords floor toward -infinity
     * so `Vec3(-0.3, 64.0, -0.3)` → `(-1, 64, -1)` (consistent with
     * `Math.floor`).
     */
    private fun slotOf(world: WorldId, position: Vec3): Slot = Slot(
        world = world,
        blockX = floor(position.x).toInt(),
        blockY = floor(position.y).toInt(),
        blockZ = floor(position.z).toInt(),
    )

    /**
     * Advisory check: if the target block exists and is loaded, confirm
     * it carries a Bukkit [Sign] blockstate. Otherwise DEBUG-log and
     * proceed (see Phase 1 PoC limitations in the class KDoc).
     */
    private fun validateBlockIsSign(world: WorldId, slot: Slot) {
        val bukkitWorld = server.getWorld(world.value)
        if (bukkitWorld == null) {
            log.debug(
                "PaperSignActionService.bindSignAction: world '{}' not loaded (binding recorded anyway)",
                world.value,
            )
            return
        }
        val block = bukkitWorld.getBlockAt(slot.blockX, slot.blockY, slot.blockZ)
        val state = block.state
        if (state !is Sign) {
            log.debug(
                "PaperSignActionService.bindSignAction: block at {}:{},{},{} is {} not Sign (binding recorded anyway)",
                world.value,
                slot.blockX,
                slot.blockY,
                slot.blockZ,
                block.type,
            )
        }
    }
}
