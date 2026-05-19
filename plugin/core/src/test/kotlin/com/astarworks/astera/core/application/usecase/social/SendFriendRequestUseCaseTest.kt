package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.service.InMemoryFriendshipRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Friendship
import com.astarworks.astera.core.domain.social.FriendshipStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SendFriendRequestUseCaseTest {

    private lateinit var repo: InMemoryFriendshipRepository
    private lateinit var useCase: SendFriendRequestUseCase
    private val alice = PlayerId(UUID.randomUUID())
    private val bob = PlayerId(UUID.randomUUID())
    private val fixedClock = Clock.fixed(Instant.parse("2026-05-19T00:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        repo = InMemoryFriendshipRepository()
        useCase = SendFriendRequestUseCase(repo, fixedClock)
    }

    @Test
    fun `send creates a PENDING friendship on first call`() {
        val result = useCase.send(alice, bob)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val f = (result as Result.Success).value
        assertThat(f.playerA).isEqualTo(alice)
        assertThat(f.playerB).isEqualTo(bob)
        assertThat(f.status).isEqualTo(FriendshipStatus.PENDING)
        assertThat(f.createdAt).isEqualTo(Instant.parse("2026-05-19T00:00:00Z"))
        assertThat(repo.find(alice, bob)).isEqualTo(f)
    }

    @Test
    fun `send fails with SelfFriend when from equals to`() {
        val result = useCase.send(alice, alice)
        assertThat(result.errorOrNull()).isInstanceOf(FriendshipError.SelfFriend::class.java)
    }

    @Test
    fun `send fails with Blocked when existing row is BLOCKED`() {
        repo.upsert(Friendship(alice, bob, FriendshipStatus.BLOCKED, Instant.EPOCH))
        val result = useCase.send(alice, bob)
        assertThat(result.errorOrNull()).isInstanceOf(FriendshipError.Blocked::class.java)
    }

    @Test
    fun `send fails with AlreadyExists when existing row is ACCEPTED`() {
        repo.upsert(Friendship(alice, bob, FriendshipStatus.ACCEPTED, Instant.EPOCH))
        val result = useCase.send(alice, bob)
        assertThat(result.errorOrNull()).isInstanceOf(FriendshipError.AlreadyExists::class.java)
    }

    @Test
    fun `send overwrites a previously DECLINED row with a new PENDING`() {
        repo.upsert(Friendship(alice, bob, FriendshipStatus.DECLINED, Instant.EPOCH))
        val result = useCase.send(alice, bob)
        assertThat(result.isSuccess).isTrue()
        assertThat(repo.find(alice, bob)?.status).isEqualTo(FriendshipStatus.PENDING)
    }

    @Test
    fun `send unordered lookup catches reverse-pair Blocked`() {
        repo.upsert(Friendship(bob, alice, FriendshipStatus.BLOCKED, Instant.EPOCH))
        val result = useCase.send(alice, bob)
        assertThat(result.errorOrNull()).isInstanceOf(FriendshipError.Blocked::class.java)
    }
}
