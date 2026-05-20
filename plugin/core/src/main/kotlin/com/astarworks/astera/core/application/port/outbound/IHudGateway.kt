package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.hud.HudHandle
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.hud.ResourcePackSpec
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: render HUD (Action Bar / BossBar / Sidebar / TabList /
 * Subtitle) for a specific player using texture-based custom font glyphs
 * (Wave 5j, ADR-0097).
 *
 * Wave 5j ships `InMemoryHudGateway` (priority 0, no-op default + event
 * publish) + `FakeHudGateway` (test-fixtures, operation history). Wave 6b
 * lands `PaperHudGateway` over Adventure `Audience.sendActionBar` +
 * BossBar registry + Player.setResourcePack push.
 *
 * Vendor-neutral by construction: domain uses [HudLayout] + [HudGlyphRef],
 * never `Adventure Component` or `org.bukkit.boss.BossBar`. Wave 6b
 * `HudRenderer` (application layer) converts [HudLayout] → Adventure
 * `Component` at the edge.
 *
 * Per Konsist `Hud domain must not import Bukkit/Paper API`, no type in
 * `core.domain.hud.*` may import `org.bukkit.*` / `io.papermc.*` / etc.
 * (`org.joml.*` is allowed per ADR-0075 §1 principle.)
 */
public interface IHudGateway {

    /**
     * Render [layout] for [playerId]. Returns an opaque [HudHandle] callers
     * store for later `updateFor` / `clearFor`.
     *
     * The same player may have multiple concurrent HUD handles across
     * different regions (e.g. mana on BossBarSlot1 + cooldown on
     * ActionBar + score on Sidebar). The gateway tracks them independently.
     */
    public fun showFor(
        playerId: PlayerId,
        layout: HudLayout,
    ): Result<HudHandle, HudError>

    /**
     * Replace the layout on a live HUD handle (e.g. mana value changed,
     * progress bar re-renders).
     */
    public fun updateFor(
        handle: HudHandle,
        layout: HudLayout,
    ): Result<Unit, HudError>

    /**
     * Clear all HUDs in [region] for [playerId]. Idempotent: clearing a
     * region with no active HUD returns Success.
     */
    public fun clearFor(
        playerId: PlayerId,
        region: HudRegion,
    ): Result<Unit, HudError>

    /**
     * Push [pack] to [playerId]. The player's client downloads the pack
     * from the URL, verifies the SHA-1, and applies (or declines, per
     * [ResourcePackSpec.required]).
     *
     * Idempotent: pushing the same hash to a player who already has it
     * applied is a no-op success.
     */
    public fun pushResourcePack(
        playerId: PlayerId,
        pack: ResourcePackSpec,
    ): Result<Unit, HudError>

    /** Snapshot of currently active HUD handles for [playerId]. */
    public fun activeFor(playerId: PlayerId): Collection<HudHandle>
}

public sealed class HudError {
    public data class UnknownPlayer(public val playerId: PlayerId) : HudError()
    public data class UnknownHandle(public val handle: HudHandle) : HudError()
    public data class InvalidGlyph(public val ref: HudGlyphRef) : HudError()
    public data class ResourcePackPushFailed(public val detail: String) : HudError()
    public data class Storage(public val detail: String) : HudError()
}
