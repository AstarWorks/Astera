package com.astarworks.astera.core.application.port.outbound.survival

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.PartyInvitation
import com.astarworks.astera.core.domain.survival.PartyInvitationId
import java.time.Instant

/**
 * Outbound port: pending island invitations (Wave 5pve-survival / ADR-0107).
 *
 * Foundation wave keeps invitations in-memory (`InMemoryPartyInvitationRepository`)
 * — they expire on `expiresAt` and disappear on server restart. Phase 4
 * upgrades to Postgres for cross-server invite delivery.
 */
public interface IPartyInvitationRepository {

    public fun find(id: PartyInvitationId): PartyInvitation?

    /** All non-expired invitations addressed to [invitee]. */
    public fun pendingFor(invitee: PlayerId, now: Instant): List<PartyInvitation>

    /**
     * Pending invitation for `(islandId, invitee)`, if any. Used to enforce
     * the no-duplicate-invitation rule in [InvitePartyMemberUseCase].
     */
    public fun pending(islandId: IslandId, invitee: PlayerId, now: Instant): PartyInvitation?

    public fun upsert(invitation: PartyInvitation)

    public fun remove(id: PartyInvitationId)

    /** Remove every invitation with `expiresAt <= now`. Returns the count removed. */
    public fun purgeExpired(now: Instant): Int
}
