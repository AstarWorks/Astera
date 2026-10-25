package com.astarworks.astera.core.application.service.survival

import com.astarworks.astera.core.application.port.outbound.survival.IPartyInvitationRepository
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.PartyInvitation
import com.astarworks.astera.core.domain.survival.PartyInvitationId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [IPartyInvitationRepository] (Wave 5pve-survival /
 * ADR-0107). Invitations are runtime-only — server restart drops them.
 */
public class InMemoryPartyInvitationRepository : IPartyInvitationRepository {

    private val invitations: ConcurrentHashMap<PartyInvitationId, PartyInvitation> =
        ConcurrentHashMap()

    override fun find(id: PartyInvitationId): PartyInvitation? = invitations[id]

    override fun pendingFor(invitee: PlayerId, now: Instant): List<PartyInvitation> =
        invitations.values.filter { it.invitee == invitee && !it.isExpired(now) }

    override fun pending(islandId: IslandId, invitee: PlayerId, now: Instant): PartyInvitation? =
        invitations.values.firstOrNull {
            it.islandId == islandId && it.invitee == invitee && !it.isExpired(now)
        }

    override fun upsert(invitation: PartyInvitation) {
        invitations[invitation.id] = invitation
    }

    override fun remove(id: PartyInvitationId) {
        invitations.remove(id)
    }

    override fun purgeExpired(now: Instant): Int {
        val expired = invitations.values.filter { it.isExpired(now) }.map { it.id }
        expired.forEach { invitations.remove(it) }
        return expired.size
    }
}
