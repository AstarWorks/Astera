package com.astarworks.astera.core.domain.world

/**
 * Vendor-neutral classification for why a teleport happened (Wave 5world-events,
 * ADR-0111).
 *
 * Used by [com.astarworks.astera.core.domain.event.PlayerTeleported] to give
 * downstream subscribers (region trigger router, anticheat exemption,
 * announcement HUD, replay capture) enough context to react without re-deriving
 * the cause from `from`/`to` coordinates.
 *
 * Sealed because every entry encodes a Minecraft / Astera-side concept that
 * either fires or does not — there is no "unknown" middle ground worth
 * representing. Adapter code (Wave 6) maps Bukkit's `PlayerTeleportEvent.Cause`
 * to one of these; non-mapped Bukkit causes fall back to [Admin] with a
 * warning log.
 *
 * Names use **business intent** (`MatchTransition`) rather than Bukkit's
 * cause name (`PLUGIN`) so domain readers can reason about why a teleport
 * matters without thinking about which Bukkit subsystem fired it.
 */
public sealed interface TeleportReason {

    /**
     * Result of a player-issued `/tp` / `/spawn` / similar command (or a
     * scripted equivalent invoked by an admin tool).
     */
    public data object Command : TeleportReason

    /**
     * Player died and the respawn pipeline placed them at a spawn point.
     * Distinct from [Command] because anticheat / region triggers may want
     * to skip a respawn-induced location change.
     */
    public data object Respawn : TeleportReason

    /**
     * Nether / End / custom-portal traversal. Carries the originating
     * portal-pair identifier when the adapter can resolve it.
     */
    public data class Portal(public val portalId: String? = null) : TeleportReason

    /**
     * Admin-side teleport not initiated through a command (drag-and-drop in
     * a debug overlay, programmatic move from a dev console). Treated as
     * authoritative — no anticheat flag fires.
     */
    public data object Admin : TeleportReason

    /**
     * Match-lifecycle move: lobby → arena spawn, arena → lobby on
     * `MatchEnded`, phase transition relocation. Carries the match id so
     * subscribers (replay capture, scoreboard sweeper) can correlate.
     */
    public data class MatchTransition(public val matchId: String) : TeleportReason

    /**
     * Ender pearl impact / chorus-fruit teleport / similar item-driven
     * short-range relocation. Carries the projectile / item kind so the
     * skill system can decide whether to consume cooldown / charge.
     */
    public data class EnderPearl(public val itemKind: String = "ender_pearl") : TeleportReason
}
