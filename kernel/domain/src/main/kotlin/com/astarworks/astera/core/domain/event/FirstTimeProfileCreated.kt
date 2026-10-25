package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Emitted after [com.astarworks.astera.core.application.port.outbound.IPlayerProfileService]
 * successfully provisions a first-time profile for a player (Wave 5wireup,
 * ADR-0116 §G1).
 *
 * # Why a separate event (not just `PlayerJoinedServer`)
 *
 * `PlayerJoinedServer` fires for every login (first-time and returning). The
 * downstream "auto-start tutorial / show welcome dialog" listeners only want
 * to run **once per account**, so they subscribe to this narrower signal
 * which is published exactly once per profile by the join-side listener
 * [com.astarworks.astera.core.application.eventbus.listeners.CreateFirstTimeProfileOnPlayerJoinedListener].
 *
 * Returning-player paths (session restore, inventory restore) do not see
 * this event.
 *
 * # Subscribers (Wave 5wireup)
 *
 *  - `StartTutorialOnFirstTimeProfileCreatedListener` — kicks off the
 *    starter tutorial via `ITutorialSequencerService`.
 *  - `ShowWelcomeDialogOnFirstTimeProfileCreatedListener` — shows the
 *    welcome dialog via `IDialogGateway` (skips when none authored).
 *
 * # Future
 *
 * Phase 4 will likely add an analytics / achievement listener (first-day
 * tracker, referral attribution) that also subscribes here.
 */
public data class FirstTimeProfileCreated(
    val playerId: PlayerId,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
