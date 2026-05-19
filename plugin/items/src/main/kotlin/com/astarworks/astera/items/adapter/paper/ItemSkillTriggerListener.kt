package com.astarworks.astera.items.adapter.paper

import com.astarworks.astera.core.adapter.api.event.IMcEvent
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.astarworks.astera.items.application.usecase.HandleItemTriggerUseCase

/**
 * Vendor-neutral click-to-skill sink (Wave _items-1, ADR-0073).
 *
 * Replaces the legacy weapons-side `WeaponClickListener`. Receives
 * [IMcEvent] from Core's `BukkitEventAdapter`, resolves the player's held
 * Astera item by reading the `astera:item_id` PDC tag (via
 * [IPaperItemIdResolver]), and dispatches the matching [SkillTrigger] to
 * [HandleItemTriggerUseCase] for binding-resolution + event publish.
 *
 * Per ADR-0022 Konsist rules this file lives in `adapter/paper/` but does NOT
 * import Bukkit — every type it touches (`IMcEvent`, `IMcPlayer`, `ItemId`,
 * `SkillTrigger`) is vendor-neutral. The Bukkit reads happen on the resolver
 * side.
 *
 * ## Trigger mapping
 *
 * `IMcEvent` today exposes the click-pair `PlayerLeftClickAir` /
 * `PlayerLeftClickBlock`. Right-click variants (and the long tail of
 * `SkillTrigger` values — `HOLD`, `AUTO`, `SYSTEM`, `ATTACK`, …) are not yet
 * wired through `BukkitEventAdapter`; they fall through the `else` branch and
 * become Phase 2 work alongside ADR-0029 §"Deep audit". The listener is
 * intentionally lenient: an unmapped event is a no-op, not an error.
 */
public class ItemSkillTriggerListener(
    private val handleTrigger: HandleItemTriggerUseCase,
    private val itemIdResolver: IPaperItemIdResolver,
) {

    /** Single entrypoint passed to `BukkitEventAdapter { event -> handle(event) }`. */
    public fun handle(event: IMcEvent) {
        val trigger: SkillTrigger = when (event) {
            is IMcEvent.PlayerLeftClickAir -> SkillTrigger.LEFT_CLICK_AIR
            is IMcEvent.PlayerLeftClickBlock -> SkillTrigger.LEFT_CLICK_BLOCK
            // Right-click + the long tail of triggers (HOLD / AUTO / SYSTEM /
            // ATTACK / DAMAGED / DEATH / …) are not yet emitted by
            // BukkitEventAdapter. Wave 6 wires the remaining mappings; until
            // then we no-op so a future event variant addition stays
            // forward-compatible.
            is IMcEvent.PlayerJoined,
            is IMcEvent.PlayerQuit,
            is IMcEvent.BlockPlaced,
            is IMcEvent.BlockBroken,
            -> return
        }

        val player = event.player
        val itemId = itemIdResolver.heldItemId(player.id) ?: return
        handleTrigger.execute(player.id, itemId, trigger, player.location)

        // Also fire the generic `LEFT_CLICK` variant so designers can bind to
        // either the narrower air/block trigger or the umbrella LEFT_CLICK.
        // The umbrella entry is opt-in per binding — no double-fire when only
        // the specific variant is bound.
        if (trigger == SkillTrigger.LEFT_CLICK_AIR || trigger == SkillTrigger.LEFT_CLICK_BLOCK) {
            handleTrigger.execute(player.id, itemId, SkillTrigger.LEFT_CLICK, player.location)
        }
    }
}
