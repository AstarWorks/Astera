package com.astarworks.astera.core.domain.region

import com.astarworks.astera.core.domain.item.ItemId

/**
 * Vendor-neutral permission vocabulary for per-region access control.
 *
 * Used by [com.astarworks.astera.core.application.port.outbound.IRegionPermissionService]
 * to gate player actions inside a [com.astarworks.astera.core.domain.stage.Region].
 *
 * Wave 5n (ADR-0092): the primary grief-prevention mechanism per
 * `.claude/plans/idea-anti-grief-policy.md`. Per-region rules take
 * precedence over per-player overrides; Wave 5o introduces an orthogonal
 * per-player PvP toggle via `IPlayerPvpStateService`.
 *
 * The variants are intentionally coarse-grained (Build / Break / Interact
 * / PvP / Pickup / Drop) rather than per-block / per-entity, matching how
 * RTM and WorldGuard model protection. The `Use(itemId)` variant covers
 * item-specific gating (e.g. "no TNT in safe zones") without exploding the
 * vocabulary.
 */
public sealed interface RegionPermission {

    /** Encoded form for storage / serialisation (matches V25 migration). */
    public fun key(): String

    /** Player may place blocks. */
    public data object Build : RegionPermission {
        override fun key(): String = "build"
    }

    /** Player may break blocks. */
    public data object Break : RegionPermission {
        override fun key(): String = "break"
    }

    /** Player may right-click interactables (doors, chests, levers). */
    public data object Interact : RegionPermission {
        override fun key(): String = "interact"
    }

    /** Player may damage other players. */
    public data object PvP : RegionPermission {
        override fun key(): String = "pvp"
    }

    /** Player may pick up dropped items. */
    public data object Pickup : RegionPermission {
        override fun key(): String = "pickup"
    }

    /** Player may drop items from inventory. */
    public data object Drop : RegionPermission {
        override fun key(): String = "drop"
    }

    /** Player may consume / activate a specific [itemId]. */
    public data class Use(val itemId: ItemId) : RegionPermission {
        override fun key(): String = "use:${itemId.value}"
    }
}
