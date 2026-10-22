package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IGodModeService
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageError
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.application.admin.panel.AdminPanelPoint
import com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.items.application.admin.AdminItemCatalog
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** F1-D: the `/astera admin panel` panel driven through the real wired object graph. */
class AdminPanelScenarioTest {

    private fun ScenarioScope.router(): IGuiIntentRouter = sim.services.getOrThrow()

    private fun ScenarioScope.makeAdmin(entity: SimEntity) {
        val r = sim.services.getOrThrow<IPermissionService>().assign(entity.id, RoleId("admin"))
        check(r is Result.Success) { "assign admin: $r" }
    }

    private fun ScenarioScope.click(who: SimEntity, key: String, vararg payload: Pair<String, String>) {
        val view = checkNotNull(sim.server.view(who.id))
        router().dispatch(view, GuiIntent(key, payload.toMap()))
    }

    @Test
    fun `hub shows a section from every plugin once all are wired`() {
        scenario("admin-panel-hub") {
            val admin = player("admin")
            makeAdmin(admin)
            click(admin, "admin.hub.open")

            val adapter = sim.services.getOrThrow<IGuiAdapter>()
            assertThat(adapter.currentSpec(checkNotNull(sim.server.view(admin.id)))!!.id.value).isEqualTo("admin-hub")
            val registry = sim.services.getOrThrow<IAdminPanelRegistry>()
            assertThat(registry.tiles(AdminPanelPoint.HUB).map { it.id })
                .containsExactly("items.catalog", "combat.skills", "match.control", "core.commands", "core.players", "core.server", "core.self")
            assertThat(registry.tiles(AdminPanelPoint.PLAYER).map { it.id }).contains("economy.currency", "combat.player.heal", "core.player.commands")
            assertThat(registry.tiles(AdminPanelPoint.SELF).map { it.id })
                .contains("core.self.fly", "combat.self.god", "combat.self.cooldowns", "combat.self.refill")
        }
    }

    @Test
    fun `non-admin cannot force start a lane or take the kit`() {
        scenario("admin-panel-denied") {
            val rookie = player("rookie")
            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            matchmaker.enqueue(rookie.id, MatchmakingMode.Norma)

            // A first-time player already holds the starter grant (content/balance/starter-grant.yaml).
            val before = checkNotNull(sim.server.view(rookie.id)).givenItems.toList()

            click(rookie, "admin.match.lane-force", "lane" to "norma")
            click(rookie, "admin.items.kit")

            assertThat(sim.services.getOrThrow<IMatchRepository>().findActiveMatches()).isEmpty()
            assertThat(checkNotNull(sim.server.view(rookie.id)).givenItems).containsExactlyElementsOf(before)
        }
    }

    @Test
    fun `admin force-starts a queued lane and the match is ACTIVE without waiting for the countdown`() {
        scenario("admin-panel-force-start") {
            val admin = player("admin")
            val p1 = player("p1")
            makeAdmin(admin)
            sim.services.getOrThrow<IMatchmaker>().enqueue(p1.id, MatchmakingMode.Norma)
            val matches = sim.services.getOrThrow<IMatchRepository>()
            assertThat(matches.findActiveMatches()).isEmpty()

            click(admin, "admin.match.lane-force", "lane" to "norma")

            val match = matches.findActiveMatches().single()
            assertThat(match.phase).isEqualTo(MatchPhase.ACTIVE)
            assertThat(match.hasParticipant(p1.id)).isTrue()
            assertThat(sim.tick).`as`("no queue/match countdown elapsed").isLessThan(200)
            assertThat(sim.services.getOrThrow<IMatchmaker>().pendingByMode(MatchmakingMode.Norma)).isEmpty()
        }
    }

    @Test
    fun `start lane with just me leaves everyone else queued`() {
        scenario("admin-panel-solo") {
            val admin = player("admin")
            val other = player("other")
            makeAdmin(admin)
            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            matchmaker.enqueue(other.id, MatchmakingMode.Norma)

            click(admin, "admin.match.lane-solo", "lane" to "norma")

            val match = sim.services.getOrThrow<IMatchRepository>().findActiveMatches().single()
            assertThat(match.phase).isEqualTo(MatchPhase.ACTIVE)
            assertThat(match.hasParticipant(admin.id)).isTrue()
            assertThat(match.hasParticipant(other.id)).isFalse()
            assertThat(matchmaker.queueStatus(other.id)).isNotNull()
        }
    }

    @Test
    fun `admin match actions end and abort a running match`() {
        scenario("admin-panel-end") {
            val admin = player("admin")
            makeAdmin(admin)
            click(admin, "admin.match.lane-solo", "lane" to "norma")
            val matches = sim.services.getOrThrow<IMatchRepository>()
            val id = matches.findActiveMatches().single().id

            click(admin, "admin.match.end", "match" to id.value)

            assertThat(matches.findActiveMatches()).isEmpty()
        }
    }

    @Test
    fun `admin full kit puts one of each weapon in the inventory`() {
        scenario("admin-panel-kit") {
            val admin = player("admin")
            makeAdmin(admin)
            val expected = AdminItemCatalog.kit(sim.items.itemRegistry.all()).map { it.id.value }
            assertThat(expected).contains("rifle", "pistol", "fire-staff", "greatsword")

            val starter = checkNotNull(sim.server.view(admin.id)).givenItems.toList()

            click(admin, "admin.items.kit")

            assertThat(checkNotNull(sim.server.view(admin.id)).givenItems)
                .containsExactlyInAnyOrderElementsOf(starter + expected)
        }
    }

    @Test
    fun `catalog single give and god mode make the admin immune in the Astera damage path`() {
        scenario("admin-panel-god") {
            val admin = player("admin")
            makeAdmin(admin)
            click(admin, "admin.items.give", "item" to "rifle", "amount" to "1")
            assertThat(checkNotNull(sim.server.view(admin.id)).givenItems).contains("rifle")

            val hit = DamageAttempt(
                targetId = admin.id,
                baseProfile = DamageProfile.single(5.0, DamageAttribute.PHYSICAL),
                source = DamageSource.Environment.Generic,
            )
            val damage = checkNotNull(sim.combat.applyDamage)
            assertThat(damage.execute(hit)).isInstanceOf(Result.Success::class.java)

            click(admin, "admin.combat.god", "target" to admin.id.toString())
            assertThat(sim.services.getOrThrow<IGodModeService>().isGod(admin.id)).isTrue()
            val blocked = damage.execute(hit)
            assertThat((blocked as Result.Failure).error).isInstanceOf(ApplyDamageError.TargetImmune::class.java)
        }
    }
}
