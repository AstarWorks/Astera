package com.astarworks.astera.core.application.port.outbound.vanilla

import com.astarworks.astera.core.domain.vanilla.VanillaFeature

/**
 * Outbound port (Wave 5server-harden / ADR-0123): centralized suppression
 * register for vanilla Minecraft features that Astera content overrides.
 *
 * # Why a registry
 *
 * Without a registry every plugin that overrides a vanilla feature must
 * roll its own listener-cancel logic and decide policy independently. With
 * one, ownership of "vanilla X is off" is global and admins can introspect
 * via a single GUI / command.
 *
 * # Phase split
 *
 * - Wave 5server-harden (this wave) — port + in-memory state register.
 *   Domain code that wants to know "is vanilla advancement live?" calls
 *   [isSuppressed] and branches.
 * - Wave 6b — `PaperVanillaFeatureSuppressor` adapter implements actual
 *   suppression: subscribes to the matching Bukkit event and calls
 *   `setCancelled(true)` (or sets the relevant game rule) only for
 *   features currently in the suppressed set.
 *
 * # Contract
 *
 * - [suppress] is idempotent — suppressing an already-suppressed feature
 *   is a no-op.
 * - [restore] is idempotent — restoring a non-suppressed feature is a
 *   no-op.
 * - [isSuppressed] is read-only and side-effect free.
 * - Implementations MUST be thread-safe; calls land from main-thread
 *   listeners and scheduler workers alike.
 */
public interface IVanillaFeatureSuppressor {

    /** Mark [feature] as suppressed. */
    public fun suppress(feature: VanillaFeature)

    /** Mark [feature] as restored. */
    public fun restore(feature: VanillaFeature)

    /** Whether [feature] is currently suppressed. */
    public fun isSuppressed(feature: VanillaFeature): Boolean

    /** Snapshot of currently suppressed features. */
    public fun suppressed(): Set<VanillaFeature>
}
