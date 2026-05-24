package com.astarworks.astera.core.domain.survival

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PartyIslandTest {

    private val now: Instant = Instant.parse("2026-05-24T12:00:00Z")
    private val partyId = PartyId(UUID.randomUUID().toString())
    private val islandId = IslandId("island-${UUID.randomUUID()}")
    private val world = WorldId("survival-test")
    private val templateId = IslandTemplateId("vanilla-skyblock")

    private fun island(
        maxMembers: Int = 5,
        visibility: IslandVisibility = IslandVisibility.PartyOnly,
        status: IslandStatus = IslandStatus.Active,
        createdAt: Instant = now,
        lastActiveAt: Instant = now,
    ): PartyIsland = PartyIsland(
        id = islandId,
        partyId = partyId,
        world = world,
        templateId = templateId,
        maxMembers = maxMembers,
        visibility = visibility,
        status = status,
        createdAt = createdAt,
        lastActiveAt = lastActiveAt,
    )

    @Test
    fun `valid PartyIsland constructs without throwing`() {
        val i = island()
        assertThat(i.partyId).isEqualTo(partyId)
        assertThat(i.status).isEqualTo(IslandStatus.Active)
    }

    @Test
    fun `maxMembers below floor is rejected`() {
        assertThatThrownBy { island(maxMembers = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `maxMembers above ceiling is rejected`() {
        assertThatThrownBy { island(maxMembers = 99) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `lastActiveAt before createdAt is rejected`() {
        assertThatThrownBy {
            island(
                createdAt = now,
                lastActiveAt = now.minusSeconds(60),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `transitionTo updates status and lastActiveAt`() {
        val initial = island(status = IslandStatus.Active, createdAt = now, lastActiveAt = now)
        val later = now.plusSeconds(300)
        val updated = initial.transitionTo(IslandStatus.Unloaded, later)
        assertThat(updated.status).isEqualTo(IslandStatus.Unloaded)
        assertThat(updated.lastActiveAt).isEqualTo(later)
    }

    @Test
    fun `FriendsAllowed empty invitedFriends is rejected`() {
        assertThatThrownBy { IslandVisibility.FriendsAllowed(emptySet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `FriendsAllowed with members accepted`() {
        val visibility = IslandVisibility.FriendsAllowed(setOf(PlayerId(UUID.randomUUID())))
        val i = island(visibility = visibility)
        assertThat(i.visibility).isInstanceOf(IslandVisibility.FriendsAllowed::class.java)
    }
}
