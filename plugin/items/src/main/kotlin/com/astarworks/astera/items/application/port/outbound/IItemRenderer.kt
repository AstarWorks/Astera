package com.astarworks.astera.items.application.port.outbound

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.domain.glow.GlowProfile

/**
 * Outbound port: Paper-side item glow / particle / glint rendering
 * (Wave 5flex / ADR-0102 surface #1).
 *
 * The use case (`StartGlowRenderUseCase` — Phase C wired) hands the
 * adapter `(player, itemId, profile)` triples; the adapter schedules
 * per-tick particle emission and the glint flag for the player's
 * currently-held item.
 *
 * # Why this is vendor-neutral (no Bukkit import here)
 *
 * Per ADR-0001 / ADR-0002 / ADR-0022: the application layer must remain
 * Bukkit-free. The Paper adapter implements [IItemRenderer] in
 * `items.adapter.paper.glow.PaperItemRenderer` and reaches Bukkit's
 * `Player.spawnParticle`. The in-memory fake recorder lives in
 * `items.application.service.glow.RecordingItemRenderer` (Phase 1) for
 * use-case-level tests.
 *
 * # Lifecycle
 *
 * - [startGlow] is idempotent per (player, itemId). Calling twice
 *   replaces the schedule.
 * - [stopGlow] removes the schedule. The Paper adapter is responsible
 *   for cleaning up cleanly on player quit (PlayerQuitEvent → stopGlow).
 * - [stopAll] is the disable hook.
 *
 * # Visibility (ADR-0102 §1, "他 player から見える発光")
 *
 * The profile's [GlowProfile.visibleFromBlocks] tells the adapter the
 * broadcast radius. Common = holder-only; Legendary = lobby-visible.
 * The adapter computes the audience per spawn.
 */
public interface IItemRenderer {

    /**
     * Begin or replace the glow schedule for [player]'s held [itemId]
     * using [profile]. If [profile.active] is false the call is a no-op
     * (and any existing schedule is cancelled).
     */
    public fun startGlow(player: PlayerId, itemId: ItemId, profile: GlowProfile)

    /** Cancel any glow schedule for [player]/[itemId] pair. */
    public fun stopGlow(player: PlayerId, itemId: ItemId)

    /** Drop every schedule. Called on plugin disable. */
    public fun stopAll()

    /**
     * Number of active glow schedules (for tests + Prometheus
     * `astera_flex_glow_active_count`).
     */
    public fun activeScheduleCount(): Int
}
