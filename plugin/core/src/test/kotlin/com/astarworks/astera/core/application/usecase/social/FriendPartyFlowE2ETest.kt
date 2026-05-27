package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.service.InMemoryFriendshipRepository
import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.FriendshipStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave G 5player-prefs-social (ADR-0124) — exercises the full social loop
 * end-to-end against in-memory repositories. Pins the Wave 5e use cases
 * plus Wave G's [KickPartyMemberUseCase] / [TransferPartyLeaderUseCase] in
 * a single chain so a regression in any link breaks the test.
 */
class FriendPartyFlowE2ETest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `add then accept then list then remove friend chain`() {
        val repo = InMemoryFriendshipRepository()
        val alice = playerId()
        val bob = playerId()

        // 1. Alice sends to Bob.
        val sent = SendFriendRequestUseCase(repo).send(alice, bob)
        assertThat(sent).isInstanceOf(Result.Success::class.java)
        assertThat(repo.find(alice, bob)!!.status).isEqualTo(FriendshipStatus.PENDING)

        // 2. Bob accepts (recipient side).
        val accepted = AcceptFriendRequestUseCase(repo).accept(bob, alice)
        assertThat(accepted).isInstanceOf(Result.Success::class.java)
        assertThat(repo.find(alice, bob)!!.status).isEqualTo(FriendshipStatus.ACCEPTED)

        // 3. Both sides see one accepted friendship.
        assertThat(repo.forPlayer(alice)).singleElement()
            .satisfies({ assertThat(it.status).isEqualTo(FriendshipStatus.ACCEPTED) })
        assertThat(repo.forPlayer(bob)).singleElement()
            .satisfies({ assertThat(it.status).isEqualTo(FriendshipStatus.ACCEPTED) })

        // 4. Bob removes. Row disappears from both sides.
        val removed = RemoveFriendUseCase(repo).remove(bob, alice)
        assertThat(removed).isInstanceOf(Result.Success::class.java)
        assertThat(repo.find(alice, bob)).isNull()
        assertThat(repo.forPlayer(alice)).isEmpty()
    }

    @Test
    fun `create then invite then kick chain`() {
        val parties = InMemoryPartyRepository()
        val leader = playerId()
        val member = playerId()

        // 1. Leader creates a party with maxSize = 4.
        val created = CreatePartyUseCase(parties).create(leader, maxSize = 4)
        assertThat(created).isInstanceOf(Result.Success::class.java)
        val party = (created as Result.Success).value
        assertThat(party.members).containsExactly(leader)

        // 2. Lobby invite via JoinPartyUseCase (Wave G PoC synchronous invite,
        // see LobbyPartyInviteIntentHandler kdoc). Member is added.
        val joined = JoinPartyUseCase(parties).join(member, party.id)
        assertThat(joined).isInstanceOf(Result.Success::class.java)
        assertThat(parties.find(party.id)!!.members).containsExactlyInAnyOrder(leader, member)

        // 3. Leader kicks the member back out.
        val kicked = KickPartyMemberUseCase(parties).kick(leader, member)
        assertThat(kicked).isInstanceOf(Result.Success::class.java)
        assertThat(parties.find(party.id)!!.members).containsExactlyInAnyOrder(leader)
    }

    @Test
    fun `create then invite then transfer then leave chain`() {
        val parties = InMemoryPartyRepository()
        val original = playerId()
        val successor = playerId()

        val created = CreatePartyUseCase(parties).create(original, maxSize = 4)
        val party = (created as Result.Success).value
        JoinPartyUseCase(parties).join(successor, party.id)

        // Original transfers leadership to successor.
        val transferred = TransferPartyLeaderUseCase(parties).transfer(original, successor)
        assertThat(transferred).isInstanceOf(Result.Success::class.java)
        assertThat(parties.find(party.id)!!.leader).isEqualTo(successor)

        // Original (now a plain member) leaves; party has 1 member, successor stays leader.
        val left = LeavePartyUseCase(parties).leave(original)
        assertThat(left).isInstanceOf(Result.Success::class.java)
        val after = parties.find(party.id)!!
        assertThat(after.members).containsExactly(successor)
        assertThat(after.leader).isEqualTo(successor)
    }
}
