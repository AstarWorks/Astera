package com.astarworks.astera.core.application.port.outbound.entity

import java.util.UUID

/**
 * Process-lifetime registry of the helper entities (text / item / block displays, armor stands,
 * item frames, NPC bodies, ...) Astera spawned, keyed by an **owner key** such as
 * `"leaderboard:lobby:0,70,0"`. It is the single answer to "does this owner already have its
 * entity?" so a feature spawns once per server lifetime instead of once per join / refresh / boot.
 *
 * Entities handed out through this registry are also marked non-persistent and tagged
 * [MANAGED_TAG] on the Paper side, so they are never written into chunk data and a janitor can
 * purge any left over from a crash or a `/reload`.
 */
public interface IManagedEntityRegistry {

    /** Records that [ownerKey] is now represented by [entityId]; returns the entity it replaced, if any. */
    public fun claim(ownerKey: String, entityId: UUID): UUID?

    /** The entity currently owned by [ownerKey], or `null`. */
    public fun entityOf(ownerKey: String): UUID?

    /** Forgets [ownerKey] (idempotent). */
    public fun release(ownerKey: String)

    /** True when [entityId] is the live entity of some owner. */
    public fun isClaimed(entityId: UUID): Boolean

    /** All owner keys with a live entity. */
    public fun ownerKeys(): Set<String>

    public companion object {
        /** Scoreboard tag on every Astera-managed helper entity. */
        public const val MANAGED_TAG: String = "astera_managed"

        /** Prefix of the per-owner scoreboard tag (`astera_owner_<key>`). */
        public const val OWNER_TAG_PREFIX: String = "astera_owner_"
    }
}
