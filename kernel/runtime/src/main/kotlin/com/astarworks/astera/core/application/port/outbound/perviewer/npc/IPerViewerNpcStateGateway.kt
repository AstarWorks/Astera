package com.astarworks.astera.core.application.port.outbound.perviewer.npc

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcSkin
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.perviewer.NpcAttributeKind
import com.astarworks.astera.core.domain.perviewer.NpcViewerOverride
import com.astarworks.astera.core.domain.perviewer.PerViewerOverrideError
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port — per-viewer NPC state override (Wave 5y-ext, ADR-0112).
 *
 * Where [com.astarworks.astera.core.application.port.outbound.entity.INpcAdapter]
 * stages traits that every viewer sees, this gateway stages **per-viewer**
 * overrides on top of those traits. Use cases:
 *
 *  - **Disguise / spy ability** — show a hostile NPC as a teammate to one
 *    player while every other player still sees the real model.
 *  - **Tutorial highlight** — make a quest-giver NPC glow only for the
 *    student until they accept the quest.
 *  - **Match team filter** — render a captured enemy scout with the team
 *    skin only for the capturing team's spectators.
 *  - **Mob skin pool (Wave 5pve)** — pick a random mob skin per spectator
 *    so a crowd of mobs feels visually distinct across viewers.
 *
 * # Backend coverage
 *
 * Implementations target both Citizens NPCs and Display entities — the same
 * two-tier coverage as [com.astarworks.astera.core.application.port.outbound.entity.INpcAdapter].
 * The Phase 1 in-memory impl is logic-only (records what should be
 * overridden); Wave 6's Paper adapter routes through PacketEvents'
 * `WrapperPlayServerPlayerInfoUpdate` + `WrapperPlayServerEntityMetadata`
 * + `WrapperPlayServerEntityEquipment` per viewer.
 *
 * # Persistence
 *
 * **Session-scoped only.** No DB table — every override is dropped on
 * server restart, and per-viewer auto-clear runs on
 * [com.astarworks.astera.core.domain.event.PlayerQuitServer]. Use cases
 * that need to persist a "this player permanently sees X disguise" semantic
 * must re-apply on join via their own repository.
 *
 * # Idempotency
 *
 * `override*` calls are idempotent: re-applying the same payload returns
 * success with no event published (the gateway compares against the current
 * staged value). `reset*` on a slot that holds no override is also success.
 * This keeps subscriber-side state machines clean.
 *
 * Implementations are NOT thread-safe (called from Paper's main thread or
 * the server-tick scheduler only).
 */
public interface IPerViewerNpcStateGateway {

    /**
     * Override the NPC's skin texture as seen by [viewer]. Other viewers see
     * the NPC's globally-configured skin.
     */
    public fun overrideSkin(
        viewer: PlayerId,
        npc: EntityHandle,
        skin: NpcSkin,
    ): Result<Unit, PerViewerOverrideError>

    /** Clear the per-viewer skin override. Idempotent. */
    public fun resetSkin(
        viewer: PlayerId,
        npc: EntityHandle,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Override the NPC's display name as seen by [viewer]. The Paper
     * adapter resolves [displayName] through `IMessageRenderer` at packet
     * send time, so subscriber locale is honoured.
     */
    public fun overrideName(
        viewer: PlayerId,
        npc: EntityHandle,
        displayName: MessageKey,
    ): Result<Unit, PerViewerOverrideError>

    /** Clear the per-viewer name override. Idempotent. */
    public fun resetName(
        viewer: PlayerId,
        npc: EntityHandle,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Override the NPC's equipment in [slot] as seen by [viewer].
     *
     * Pass `item = null` to **explicitly hide** the item in that slot for
     * the viewer (distinct from "no override" — see [NpcViewerOverride]
     * equipment-map invariant).
     */
    public fun overrideEquipment(
        viewer: PlayerId,
        npc: EntityHandle,
        slot: EquipSlot,
        item: ItemId?,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Clear the per-viewer equipment override on every slot for this NPC.
     * Idempotent. Equivalent to a per-slot reset loop, kept as a single
     * call because "drop the disguise gear" is a common use-case shape.
     */
    public fun resetEquipment(
        viewer: PlayerId,
        npc: EntityHandle,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Override an attribute (scale / glowing / invisible / name-visible)
     * for [viewer]. Setting the same attribute twice replaces the previous
     * value; reset via [clearAllOverrides] (no per-attribute reset by
     * design — every staged attribute is cheap to re-apply and per-axis
     * reset would balloon the surface area).
     */
    public fun overrideAttribute(
        viewer: PlayerId,
        npc: EntityHandle,
        attribute: NpcAttributeKind,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Clear every override (skin + name + equipment + every attribute)
     * for one (viewer, npc) pair. Idempotent.
     */
    public fun clearAllOverrides(
        viewer: PlayerId,
        npc: EntityHandle,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Clear every override on every NPC for [viewer]. Called automatically
     * on `PlayerQuitServer` by the gateway's internal subscriber to prevent
     * resource leak; use cases may also call explicitly (tutorial cleanup).
     *
     * Returns the number of NPCs that held at least one override (useful
     * for the audit log writer; `0` means nothing to do).
     */
    public fun clearAllOverridesForViewer(viewer: PlayerId): Int

    /**
     * Read-back snapshot of every override currently staged on (viewer, npc),
     * or `null` if no override is staged. Defensive copy.
     */
    public fun currentOverrides(
        viewer: PlayerId,
        npc: EntityHandle,
    ): NpcViewerOverride?
}
