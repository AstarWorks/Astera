package com.astarworks.astera.core.application.gui.binding

import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.core.domain.social.Friendship
import com.astarworks.astera.core.domain.social.FriendshipStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PlayerFriendsListGuiBindingResolverTest {

    private fun pid(): PlayerId = PlayerId(UUID.randomUUID())

    private class FakeFriendships : IFriendshipRepository {
        private val store: MutableList<Friendship> = mutableListOf()

        override fun find(a: PlayerId, b: PlayerId): Friendship? =
            store.firstOrNull {
                (it.playerA == a && it.playerB == b) || (it.playerA == b && it.playerB == a)
            }

        override fun forPlayer(playerId: PlayerId): List<Friendship> =
            store.filter { it.playerA == playerId || it.playerB == playerId }

        override fun upsert(friendship: Friendship) {
            remove(friendship.playerA, friendship.playerB)
            store += friendship
        }

        override fun remove(a: PlayerId, b: PlayerId) {
            store.removeIf {
                (it.playerA == a && it.playerB == b) || (it.playerA == b && it.playerB == a)
            }
        }
    }

    private class FakeSessions : IPlayerSessionRepository {
        private val store: MutableMap<PlayerId, PlayerSession> = mutableMapOf()

        override fun find(playerId: PlayerId): PlayerSession? = store[playerId]
        override fun upsert(session: PlayerSession) {
            store[session.playerId] = session
        }
        override fun remove(playerId: PlayerId) {
            store.remove(playerId)
        }
        override fun all(): List<PlayerSession> = store.values.toList()
    }

    @Test
    fun `resolves online friend tile when accepted and session active`() {
        val viewer = pid()
        val friend = pid()
        val friendships = FakeFriendships().apply {
            upsert(
                Friendship(
                    playerA = viewer,
                    playerB = friend,
                    status = FriendshipStatus.ACCEPTED,
                    createdAt = Instant.parse("2025-01-01T00:00:00Z"),
                ),
            )
        }
        val sessions = FakeSessions().apply {
            upsert(PlayerSession(playerId = friend, state = SessionState.Lobby, currentMatch = null as MatchId?))
        }
        val resolver = PlayerFriendsListGuiBindingResolver(friendships, sessions)

        val resolved = resolver.resolve(BindingKey("player.friends.list.0"), viewer)

        assertThat(resolved).isNotNull
        assertThat(resolved!!.itemId.value).isEqualTo(PlayerFriendsListGuiBindingResolver.TILE_ONLINE)
        assertThat(resolved.displayOverride).isEqualTo(PlayerFriendsListGuiBindingResolver.MSG_NAME_ONLINE)
    }

    @Test
    fun `resolves offline friend tile when accepted but no session`() {
        val viewer = pid()
        val friend = pid()
        val friendships = FakeFriendships().apply {
            upsert(
                Friendship(
                    playerA = viewer,
                    playerB = friend,
                    status = FriendshipStatus.ACCEPTED,
                    createdAt = Instant.parse("2025-01-01T00:00:00Z"),
                ),
            )
        }
        val resolver = PlayerFriendsListGuiBindingResolver(friendships, FakeSessions())

        val resolved = resolver.resolve(BindingKey("player.friends.list.0"), viewer)

        assertThat(resolved!!.itemId.value).isEqualTo(PlayerFriendsListGuiBindingResolver.TILE_OFFLINE)
    }

    @Test
    fun `resolves pending tile for pending friendships`() {
        val viewer = pid()
        val friend = pid()
        val friendships = FakeFriendships().apply {
            upsert(
                Friendship(
                    playerA = viewer,
                    playerB = friend,
                    status = FriendshipStatus.PENDING,
                    createdAt = Instant.parse("2025-01-01T00:00:00Z"),
                ),
            )
        }
        val resolver = PlayerFriendsListGuiBindingResolver(friendships, FakeSessions())

        val resolved = resolver.resolve(BindingKey("player.friends.list.0"), viewer)

        assertThat(resolved!!.itemId.value).isEqualTo(PlayerFriendsListGuiBindingResolver.TILE_PENDING)
    }

    @Test
    fun `omits BLOCKED and DECLINED entries`() {
        val viewer = pid()
        val blocked = pid()
        val declined = pid()
        val friendships = FakeFriendships().apply {
            upsert(
                Friendship(viewer, blocked, FriendshipStatus.BLOCKED, Instant.parse("2025-01-01T00:00:00Z")),
            )
            upsert(
                Friendship(viewer, declined, FriendshipStatus.DECLINED, Instant.parse("2025-01-02T00:00:00Z")),
            )
        }
        val resolver = PlayerFriendsListGuiBindingResolver(friendships, FakeSessions())

        val resolved = resolver.resolve(BindingKey("player.friends.list.0"), viewer)

        assertThat(resolved).isNull()
    }

    @Test
    fun `returns null for unknown viewer`() {
        val resolver = PlayerFriendsListGuiBindingResolver(FakeFriendships(), FakeSessions())
        val resolved = resolver.resolve(BindingKey("player.friends.list.0"), viewer = null)
        assertThat(resolved).isNull()
    }

    @Test
    fun `returns null for non-matching prefix`() {
        val resolver = PlayerFriendsListGuiBindingResolver(FakeFriendships(), FakeSessions())
        val resolved = resolver.resolve(BindingKey("player.kills"), viewer = pid())
        assertThat(resolved).isNull()
    }

    @Test
    fun `returns null when index out of range`() {
        val resolver = PlayerFriendsListGuiBindingResolver(FakeFriendships(), FakeSessions())
        assertThat(resolver.resolve(BindingKey("player.friends.list.9"), viewer = pid())).isNull()
    }

    @Test
    fun `returns null when index beyond available friends`() {
        val viewer = pid()
        val friendships = FakeFriendships()
        val resolver = PlayerFriendsListGuiBindingResolver(friendships, FakeSessions())
        assertThat(resolver.resolve(BindingKey("player.friends.list.3"), viewer)).isNull()
    }

    @Test
    fun `degrades gracefully when provider returns null`() {
        val resolver = PlayerFriendsListGuiBindingResolver(
            friendshipsProvider = { null },
            sessionsProvider = { null },
        )
        assertThat(resolver.resolve(BindingKey("player.friends.list.0"), pid())).isNull()
    }
}
