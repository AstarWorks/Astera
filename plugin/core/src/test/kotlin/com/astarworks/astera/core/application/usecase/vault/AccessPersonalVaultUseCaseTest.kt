package com.astarworks.astera.core.application.usecase.vault

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IVaultWindow
import com.astarworks.astera.core.application.service.InMemoryPersonalVaultRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.vault.PersonalVault
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Vault open / store / reload through the same entry point `/vault` and the ender chest block use. */
class AccessPersonalVaultUseCaseTest {

    private class RecordingWindow(var accept: Boolean = true) : IVaultWindow {
        val shown = mutableListOf<PersonalVault>()
        var onClose: (PlayerId) -> Unit = {}
        override fun closeFor(playerId: PlayerId) = onClose(playerId)
        override fun show(vault: PersonalVault, page: Int): Boolean {
            shown += vault
            return accept
        }
    }

    private class FakeMatches(private val match: Match?) : IMatchRepository {
        override fun findActiveMatches(): Collection<Match> = listOfNotNull(match)
        override fun findMatchOf(playerId: PlayerId): Match? = match?.takeIf { it.hasParticipant(playerId) }
        override fun findInPhase(phase: MatchPhase): Collection<Match> = listOfNotNull(match).filter { it.phase == phase }
        override fun findById(id: MatchId): Match? = match?.takeIf { it.id == id }
        override fun all(): Collection<Match> = listOfNotNull(match)
        override fun save(entity: Match) = Unit
        override fun delete(id: MatchId) = Unit
    }

    private val repo = InMemoryPersonalVaultRepository()
    private val window = RecordingWindow()
    private val player = PlayerId(UUID.randomUUID())

    private fun matchWith(playerId: PlayerId, phase: MatchPhase): Match {
        val red = TeamId("red")
        return Match(
            id = MatchId("m1"),
            phase = phase,
            teams = listOf(Team(red, MessageKey("t.red"), MessageKey("t.red.color"), setOf(playerId))),
            score = Score(mapOf(red to 0)),
            stageId = StageId("arena"),
            startedAtTick = 0L,
        )
    }

    private fun access(matches: IMatchRepository? = null, win: IVaultWindow? = window) =
        AccessPersonalVaultUseCase(OpenVaultUseCase(repo), { win }, { matches })

    @Test
    fun `first open shows an empty vault`() {
        val result = access().open(player)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(window.shown.single()).isEqualTo(PersonalVault.empty(player))
    }

    @Test
    fun `stored items survive a close and reopen`() {
        val save = SaveVaultUseCase(repo)
        val stored = PersonalVault(
            player,
            listOf(
                PersonalVault.VaultItem(slot = 0, itemId = "minecraft:diamond", count = 3),
                PersonalVault.VaultItem(slot = 26, itemId = "astera:sword", count = 1, nbt = "b64"),
            ),
        )

        access().open(player)
        assertThat(save.save(stored)).isInstanceOf(Result.Success::class.java) // what the window does on close
        access().open(player)

        assertThat(window.shown.last()).isEqualTo(stored)
        assertThat(window.shown.last().itemAt(26)?.nbt).isEqualTo("b64")
    }

    @Test
    fun `an open window is saved before the vault is read back for the next one`() {
        val save = SaveVaultUseCase(repo)
        val before = PersonalVault(player, listOf(PersonalVault.VaultItem(0, "minecraft:diamond", 3)))
        save.save(before)
        // The old window had the diamond taken out; closing it persists the emptied vault.
        window.onClose = { save.save(PersonalVault.empty(it)) }

        access().open(player)

        assertThat(window.shown.single().items).isEmpty()
    }

    @Test
    fun `vault is refused while the player is in a live match`() {
        val live = FakeMatches(matchWith(player, MatchPhase.ACTIVE))

        val result = access(live).open(player)

        assertThat(result).isEqualTo(Result.Failure(VaultError.BlockedInMatch))
        assertThat(window.shown).isEmpty()
    }

    @Test
    fun `vault opens while the match is only waiting or already ended`() {
        for (phase in listOf(MatchPhase.WAITING, MatchPhase.ENDED)) {
            val result = access(FakeMatches(matchWith(player, phase))).open(player)
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `missing or refusing window reports WindowUnavailable`() {
        assertThat(access(win = null).open(player)).isEqualTo(Result.Failure(VaultError.WindowUnavailable))
        window.accept = false
        assertThat(access().open(player)).isEqualTo(Result.Failure(VaultError.WindowUnavailable))
    }

    @Test
    fun `save rejects a slot outside the chest and the aggregate rejects duplicate slots`() {
        // Construction guards the slot range; the use case is the second line of defence for hosts.
        assertThatThrownBy { PersonalVault.VaultItem(slot = PersonalVault.CAPACITY, itemId = "x", count = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            PersonalVault(
                player,
                listOf(PersonalVault.VaultItem(0, "a", 1), PersonalVault.VaultItem(0, "b", 1)),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
