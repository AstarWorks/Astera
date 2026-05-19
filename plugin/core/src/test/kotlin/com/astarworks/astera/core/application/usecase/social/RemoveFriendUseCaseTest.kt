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

class RemoveFriendUseCaseTest {

    private lateinit var repo: InMemoryFriendshipRepository
    private lateinit var useCase: RemoveFriendUseCase
    private val alice = PlayerId(UUID.randomUUID())
    private val bob = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repo = InMemoryFriendshipRepository()
        useCase = RemoveFriendUseCase(repo)
    }

    @Test
    fun `remove deletes the row entirely`() {
        repo.upsert(Friendship(alice, bob, FriendshipStatus.ACCEPTED, Instant.EPOCH))
        val result = useCase.remove(alice, bob)
        assertThat(result.isSuccess).isTrue()
        assertThat(repo.find(alice, bob)).isNull()
    }

    @Test
    fun `remove works regardless of pair order`() {
        repo.upsert(Friendship(alice, bob, FriendshipStatus.ACCEPTED, Instant.EPOCH))
        val result = useCase.remove(bob, alice)
        assertThat(result.isSuccess).isTrue()
        assertThat(repo.find(alice, bob)).isNull()
    }

    @Test
    fun `remove fails with NotFound when row is missing`() {
        val result = useCase.remove(alice, bob)
        assertThat(result.errorOrNull()).isInstanceOf(FriendshipError.NotFound::class.java)
    }
}
