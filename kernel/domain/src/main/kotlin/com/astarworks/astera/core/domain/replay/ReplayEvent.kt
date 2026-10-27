package com.astarworks.astera.core.domain.replay

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * One in-match observation captured by [com.astarworks.astera.match.application
 * .port.inbound.IMatchRecorder] (Wave 5v, ADR-0094).
 *
 * Each variant is **a pure data class over primitives + value classes** —
 * that purity is mechanically enforced by `ArchitectureTest.\`Wave 5v
 * ReplayEvent variants must contain only primitive or value-class types\``,
 * so serialization for the V20 `events_jsonb` column is mirror-trivial
 * (one `ReplayEventJson` discriminator-wrapped sealed in
 * `:plugin:persistence` per Astera's convention of never tagging domain
 * types with `@Serializable`).
 *
 * The "primitive + value class only" rule has three motivations:
 *
 * 1. **Wire stability** — the JSONB shape is deterministic from the field
 *    list; adding a sealed sub-hierarchy would silently produce
 *    discriminator collisions across waves.
 * 2. **Size budget** — Wave 5v promises 4 players × 1 hour ≈ 5 MB. Nested
 *    rich objects (e.g. inlining `DamageSource` here) would 3-5× the byte
 *    count without playback benefit.
 * 3. **Forward-only schema (Phase 1 PoC posture)** — variants can be
 *    renamed / restructured in Wave 5v++ without writing a dual-decoder;
 *    the events_jsonb decoder fails loudly on schema drift and the only
 *    cost is "old captures unplayable" — acceptable pre-release.
 *
 * Cross-plugin events (e.g. combat's `DamageDealt` with its rich
 * `ResolvedDamage` + `DamageSource`) are projected into the recorder via
 * application-level adapters in `:plugin:match.application.service.replay`,
 * NOT by reusing the source-of-truth event types.
 *
 * The `atTick` field on every variant is the **server tick the event
 * occurred**, captured at publish time — replay playback uses this for
 * timeline placement, not [DomainEvent.occurredAt] (wall-clock).
 */
public sealed interface ReplayEvent {

    public val atTick: Long

    /**
     * Delta-encoded position change between key frames. Anchors are
     * [KeyFrame]s; this variant carries displacement only so a long
     * stationary stretch produces zero events between snapshots.
     *
     * Phase 1 PoC publishes one entry per server tick where the player's
     * position changed by ≥ 0.01 blocks; Wave 6a Paper hook decides the
     * exact gate.
     */
    public data class PlayerMoved(
        val playerId: PlayerId,
        val deltaX: Float,
        val deltaY: Float,
        val deltaZ: Float,
        override val atTick: Long,
    ) : ReplayEvent

    /**
     * Resolved damage application. [sourceKind] discriminates the broad
     * damage origin (one of [DamageSourceKind]) — full sealed-source
     * structure (per-weapon ID, projectile owner chain, etc.) is dropped
     * for size; analytics needing it should join against the match's
     * `audit_log` rows on `(matchId, atTick, attackerId, victimId)`.
     */
    public data class DamageDealt(
        val attackerId: PlayerId?,
        val victimId: PlayerId,
        val amount: Double,
        val sourceKind: DamageSourceKind,
        override val atTick: Long,
    ) : ReplayEvent

    /**
     * Lethal damage outcome. [killerId] null = environmental death (fall,
     * void, burn-tick-from-lava). Mirrors combat's `PlayerKilled` domain
     * event.
     */
    public data class PlayerKilled(
        val victimId: PlayerId,
        val killerId: PlayerId?,
        override val atTick: Long,
    ) : ReplayEvent

    /**
     * Respawn completion event. Subscribed from `PlayerRespawned` (Wave
     * 5o landed in a sibling worktree; main publishes it once that wave
     * merges — until then the recorder simply records zero of these
     * variants). The placeholder forward-declared variant lives in
     * `core.domain.event.PlayerRespawned`.
     */
    public data class PlayerRespawned(
        val playerId: PlayerId,
        override val atTick: Long,
    ) : ReplayEvent

    /**
     * Player consumed / activated an item (food / scroll / utility).
     * [itemId] is the item slug; for stack-from-Bukkit inputs Wave 6a
     * fills the slug from `ItemRegistry.lookupBySluggedMaterial`.
     */
    public data class ItemUsed(
        val playerId: PlayerId,
        val itemId: ItemId,
        override val atTick: Long,
    ) : ReplayEvent

    /**
     * A skill was cast. Mirrors combat's `SkillCast` domain event but
     * drops the cast-position vector (not a playback essential — the
     * caster's position is interpolatable from key frames).
     */
    public data class SkillCast(
        val playerId: PlayerId,
        val skillId: SkillId,
        override val atTick: Long,
    ) : ReplayEvent

    /**
     * Chat / system message that should reappear in playback. Only the
     * [messageKey] is captured; per-call parameters (e.g. damage numbers)
     * are dropped — Phase 4 playback resolves the key against
     * `content/languages/{en,ja}.yaml`.
     */
    public data class ChatSent(
        val playerId: PlayerId,
        val messageKey: MessageKey,
        override val atTick: Long,
    ) : ReplayEvent

    /**
     * Match phase transition (Wave 5c state-machine). Useful for playback
     * scrub-bar segments.
     */
    public data class MatchPhaseChanged(
        val matchId: MatchId,
        val from: MatchPhase,
        val to: MatchPhase,
        override val atTick: Long,
    ) : ReplayEvent

    /**
     * Pillar / objective capture in Annihilation-style game modes. The
     * recorder treats pillarId as opaque; Wave 5c CaptureWool
     * listeners publish a sibling `PillarCaptured` core domain event that
     * the recorder mirrors here.
     */
    public data class PillarCaptured(
        val matchId: MatchId,
        val pillarId: String,
        val team: TeamId,
        override val atTick: Long,
    ) : ReplayEvent
}

/**
 * Broad damage-source category for [ReplayEvent.DamageDealt].
 *
 * Mirrors combat's `DamageSource` sealed at one level coarser to keep the
 * replay JSONB flat (see [ReplayEvent] docstring §"Wire stability"). The
 * recorder maps combat's full sealed via
 * [com.astarworks.astera.match.application.service.replay.toReplayKind].
 */
public enum class DamageSourceKind {
    ENVIRONMENT,
    ITEM,
    SKILL,
    FALL,
    EXPLOSION,
    STATUS_TICK,
    PROJECTILE,
    BARRIER,
}
