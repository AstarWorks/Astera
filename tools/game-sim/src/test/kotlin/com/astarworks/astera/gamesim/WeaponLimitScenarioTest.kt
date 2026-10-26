package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.service.inventory.WeaponLimitService
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.PlayerEnteredMatch
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.WeaponLimits
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Match weapon restriction: the Norma layer default `weapon_limits` (content/norma-layer/layer.yaml, every mode) = at most 1 per family, 2 in total (RTM MAIN + SUB assumption). */
class WeaponLimitScenarioTest {

    @Test
    fun `match entry trims an over-full inventory to the cap and the verdict then refuses more`() {
        val sim = GameSim.boot("weapon-limit", snapshots = InMemoryPlayerInventorySnapshotRepository())
        try {
            val loaded = sim.spawnPlayer("loaded")
            val rival = sim.spawnPlayer("rival")
            val lobbyOnly = sim.spawnPlayer("lobby")
            sim.step(5)
            val service = sim.services.getOrThrow<WeaponLimitService>()
            val items = checkNotNull(sim.server.view(loaded.id)).givenItems
            items.clear()
            items += listOf("rifle", "knight-sword", "greatsword", "frost-staff", "gui-pane-dark")

            // Outside a match nothing is restricted.
            assertThat(service.verdictFor(lobbyOnly.id, ItemId("pistol"))).isEqualTo(WeaponLimitService.Verdict.Allowed)

            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            matchmaker.enqueue(loaded.id, MatchmakingMode.SKIRMISH)
            matchmaker.enqueue(rival.id, MatchmakingMode.SKIRMISH)
            assertThat(sim.stepUntil(600) { sim.eventsOf<PlayerEnteredMatch>().size == 2 }).isTrue()

            // 5 slots in, the first MAIN + SUB stay; the same-family and over-total weapons are set aside. Non-weapons stay.
            assertThat(items).containsExactly("rifle", "knight-sword", "gui-pane-dark")
            assertThat(checkNotNull(sim.server.view(loaded.id)).messages).anyMatch { "Weapon limit" in it }

            // At the cap: another weapon is refused with the total-cap blocker, a non-weapon is not.
            val blocked = service.verdictFor(loaded.id, ItemId("pistol"))
            assertThat(blocked).isInstanceOf(WeaponLimitService.Verdict.Blocked::class.java)
            assertThat((blocked as WeaponLimitService.Verdict.Blocked).blocker).isEqualTo(WeaponLimits.Blocker.Total(2))
            assertThat(service.verdictFor(loaded.id, ItemId("gui-pane-dark"))).isEqualTo(WeaponLimitService.Verdict.Allowed)
            // Players outside the match stay unrestricted.
            assertThat(service.verdictFor(lobbyOnly.id, ItemId("pistol"))).isEqualTo(WeaponLimitService.Verdict.Allowed)

            // After the match the cap is gone again.
            val matchId = sim.services.getOrThrow<IMatchRepository>().findActiveMatches().single().id
            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            sim.step(10)
            assertThat(service.verdictFor(loaded.id, ItemId("pistol"))).isEqualTo(WeaponLimitService.Verdict.Allowed)
        } finally {
            sim.shutdown()
        }
    }

    @Test
    fun `a siege mode carries the same cap through the layer default`() {
        val sim = GameSim.boot("weapon-limit-siege", snapshots = InMemoryPlayerInventorySnapshotRepository())
        try {
            val a = sim.spawnPlayer("sa")
            val b = sim.spawnPlayer("sb")
            sim.step(5)
            val items = checkNotNull(sim.server.view(a.id)).givenItems
            items.clear()
            items += listOf("rifle", "pistol", "knight-sword", "frost-staff")

            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            val mode = MatchmakingMode.Custom(com.astarworks.astera.match.domain.mode.GameModeId("carry-tnt"))
            matchmaker.enqueue(a.id, mode)
            matchmaker.enqueue(b.id, mode)
            assertThat(sim.stepUntil(600) { sim.eventsOf<PlayerEnteredMatch>().size == 2 }).isTrue()

            // gun:1 drops the pistol, total:2 drops the staff; the sword keeps the second slot.
            assertThat(items).containsExactly("rifle", "knight-sword")
        } finally {
            sim.shutdown()
        }
    }
}
