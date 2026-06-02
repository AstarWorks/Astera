package com.astarworks.astera.match.application.eventbus.listeners.region

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.PlayerEnteredRegion
import com.astarworks.astera.core.domain.event.PlayerExitedRegion
import com.astarworks.astera.match.domain.event.EntityEnteredRegion
import com.astarworks.astera.match.domain.event.EntityLeftRegion

/**
 * Bridges core-side player-region transition events into the match-domain
 * variants required by mode listeners (Wave 6a-N3, ADR-0159; closes the
 * `match.EntityEnteredRegion` / `match.EntityLeftRegion` rows of the
 * ADR-0134 deferred-events allowlist).
 *
 * # Why this exists
 *
 * Core's [com.astarworks.astera.core.application.eventbus.listeners.region.RegionTransitionDetectorListener]
 * derives [PlayerEnteredRegion] / [PlayerExitedRegion] from `PlayerMoved`,
 * but those events are stage-bound and carry no `matchId` — Core sits below
 * Match in the ADR-0022 dependency direction and cannot reference
 * `match.domain.event.*`. Mode listeners
 * (CarryTNT delivery zone, CaptureWool control area, StealJewelry deposit
 * zone) all subscribe to the match-flavoured [EntityEnteredRegion] /
 * [EntityLeftRegion] which require `matchId` for per-match state slicing.
 *
 * This relay closes the gap: it sits in the match plugin (allowed to
 * import core events + [IMatchRepository]), looks up the active match for
 * the player, and republishes a matchId-qualified event. Players not
 * currently in a match (lobby, spectator without a match assignment) are
 * silently skipped — relay is a no-op for them, not an error.
 *
 * # Behaviour
 *
 *  1. Subscribe to [PlayerEnteredRegion] / [PlayerExitedRegion] on the
 *     shared [IDomainEventBus] (single in-process instance, ADR-0036).
 *  2. For each, resolve `playerId -> matchId` via
 *     [IMatchRepository.findMatchOf]. A null result means the player is
 *     not currently in a match — the transition is a lobby/free-roam
 *     region crossing and no match-domain event is published.
 *  3. Otherwise publish the corresponding match event, copying
 *     `playerId`, `regionId.value` (the stable YAML region name), and
 *     `atTick` through unchanged. `regionId` on the match side is a
 *     `String` (stage-YAML id like `red_base` or `gem_deposit_blue`);
 *     core's [com.astarworks.astera.core.domain.region.RegionId] is a
 *     value class around the same string, so the unwrap is lossless.
 *
 * # Lifecycle + wiring
 *
 * Wired by `AsteraMatchPlugin.onEnable`; the returned subscriptions are
 * tracked in the plugin's shared cancel list and dropped on `onDisable`
 * (same pattern as [com.astarworks.astera.match.application.usecase.mode.StealJewelryListener]).
 *
 * # Threading
 *
 * Single-threaded; the in-memory event bus dispatches on the Paper main
 * thread, so neither [IMatchRepository] nor [IDomainEventBus.publish]
 * needs guarding here.
 *
 * # ADR refs
 *
 *  - ADR-0159 (this wave) — match-side relay introduction
 *  - ADR-0134 — deferred-events allowlist closure for the two
 *    `match.Entity*Region` rows
 *  - ADR-0022 — sibling-isolation justification for why this lives in
 *    `plugin:match` rather than `plugin:core`
 */
public class RegionTransitionRelayListener(
    private val eventBus: IDomainEventBus,
    private val matches: IMatchRepository,
) {

    /** Registers handlers on [eventBus]; cancel the returned subscriptions to detach. */
    public fun subscribe(): List<IDomainEventBus.Subscription> = listOf(
        eventBus.subscribe(PlayerEnteredRegion::class) { event -> onEntered(event) },
        eventBus.subscribe(PlayerExitedRegion::class) { event -> onExited(event) },
    )

    private fun onEntered(event: PlayerEnteredRegion) {
        val match = matches.findMatchOf(event.playerId) ?: return
        eventBus.publish(
            EntityEnteredRegion(
                matchId = match.id,
                playerId = event.playerId,
                regionId = event.regionId.value,
                atTick = event.atTick,
            ),
        )
    }

    private fun onExited(event: PlayerExitedRegion) {
        val match = matches.findMatchOf(event.playerId) ?: return
        eventBus.publish(
            EntityLeftRegion(
                matchId = match.id,
                playerId = event.playerId,
                regionId = event.regionId.value,
                atTick = event.atTick,
            ),
        )
    }
}
