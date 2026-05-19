package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.service.InMemoryFriendshipRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Friendship
import com.astarworks.astera.core.domain.social.FriendshipStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DeclineFriendRequestUseCaseTest {

    private lateinit var repo: InMemoryFriendshipRepository
    private lateinit var useCase: DeclineFriendRequestUseCase
    private val alice = PlayerId(UUID.randomUUID())
    private val bob = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repo = InMemoryFriendshipRepository()
        useCase = DeclineFriendRequestUseCase(repo)
    }

    @Test
    fun `decline transitions PENDING to DECLINED`() {
        repo.upsert(Friendship(alice, bob, FriendshipStatus.PENDING, Instant.EPOCH))
        val result = useCase.decline(bob, alice)
        assertThat(result.isSuccess).isTrue()
        assertThat(repo.find(alice, bob)?.status).isEqualTo(FriendshipStatus.DECLINED)
    }

    @Test
    fun `decline fails with NotFound when row is missing`() {
        val result = useCase.decline(bob, alice)
        assertThat(result.errorOrNull()).isInstanceOf(FriendshipError.NotFound::class.java)
    }

    @Test
    fun `decline fails with NotPending when row is ACCEPTED`() {
        repo.upsert(Friendship(alice, bob, FriendshipStatus.ACCEPTED, Instant.EPOCH))
        val result = useCase.decline(bob, alice)
        assertThat(result.errorOrNull()).isInstanceOf(FriendshipError.NotPending::class.java)
    }
}
