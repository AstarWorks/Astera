package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.economy.application.service.MatchShopLoaderService
import com.astarworks.astera.economy.domain.matchshop.MatchShopTab
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.core.domain.event.SkillCastRequested
import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files
import org.junit.jupiter.api.Test

/**
 * Weapon strengthening bought at the own base shop (BedWars / Annihilation style): the `upgrades` tab of the shipped
 * `content/shops/match-shop.yaml` trades the carried weapon plus ore for the next tier item (`<weapon>-tN`, a stronger
 * skill copy). Real wiring and shipped content run.
 *
 *  - A player buys tier 2 with gold: the base weapon is consumed, the ore is paid, and the tier item hits harder than the base weapon.
 *  - Without the base weapon or without the ore the buy is refused and nothing moves.
 *  - A `Bot_` account that stands at its own base shop NPC with the base weapon and the ore gets the tier item (same rules, no free
 *    stats); a bot without ore, or one standing away from its base, buys nothing.
 *  - Match end removes the tier item (match-scoped like ore).
 */
class WeaponUpgradeShopScenarioTest {

    private fun ScenarioScope.dispatch(who: SimEntity, key: String, vararg payload: Pair<String, String>) {
        val view = checkNotNull(sim.server.view(who.id))
        sim.services.getOrThrow<IGuiIntentRouter>().dispatch(view, GuiIntent(key, payload.toMap()))
    }

    private fun ScenarioScope.items(who: SimEntity) = checkNotNull(sim.server.view(who.id)).givenItems

    private fun ScenarioScope.upgradeIndex(item: String): Int {
        val loader = MatchShopLoaderService()
        assertThat(loader.loadFrom(GameSim.defaultContentDir().resolve("shops")).failed).isZero()
        return loader.catalog.tab(MatchShopTab.UPGRADES).indexOfFirst { it.itemId.value == item }.also { assertThat(it).isNotNegative() }
    }

    private fun ScenarioScope.buy(who: SimEntity, item: String) =
        dispatch(who, "matchshop.buy", "tab" to "upgrades", "index" to upgradeIndex(item).toString())

    @Test
    fun `a player buys the next pistol tier with gold, the base pistol is consumed and the new tier casts its own harder skill`() {
        scenario("weapon-upgrade-buy") {
            val red = player("red-buyer", team = "red")
            val blue = player("blue-victim", team = "blue")
            startMatch("annihilation", mapOf(red to "red", blue to "blue"), stage = "annihilation-arena")
            advance(5)

            // Refused without the base pistol (ore alone is not enough).
            items(red).removeAll { it == "pistol" } // the entry kit may already hand one over
            repeat(6) { items(red) += "ore-gold" }
            buy(red, "pistol-t2")
            assertThat(items(red).count { it == "pistol-t2" }).isZero()
            assertThat(items(red).count { it == "ore-gold" }).isEqualTo(6)

            // Tier 3 needs tier 2 and diamond: refused even with a base pistol.
            items(red) += "pistol"
            buy(red, "pistol-t3")
            assertThat(items(red).count { it == "pistol-t3" }).isZero()

            buy(red, "pistol-t2")
            assertThat(items(red).count { it == "pistol-t2" }).isEqualTo(1)
            assertThat(items(red).count { it == "pistol" }).isZero()
            assertThat(items(red).count { it == "ore-gold" }).isZero()

            // The bought tier fires its own, harder skill: the cast request carries the tier skill id, and the shipped skill
            // data holds strictly more damage per rung. (Landed-hit damage is not asserted here: the sim's pistol/sword hits do not
            // land at this branch head - SkillHitsLand / DeathRespawn fail at baseline - so the number comes from the skill data.)
            red.moveTo(blue.position + Vec3(0.0, 0.0, 6.0))
            red.holds("pistol-t2")
            red.faces(blue)
            repeat(3) {
                red.rightClick()
                advance(25)
            }
            val casts = eventsOf<SkillCastRequested>().filter { it.actorId == red.id }.map { it.skillId.value }
            assertThat(casts).`as`("the tier item casts its own skill").contains("pistol-shot-t2").doesNotContain("pistol-shot")

            val skills = GameSim.defaultContentDir().resolve("skills")
            fun damageOf(skill: String): Double =
                Regex("""damage:\s*\{\s*physical:\s*([0-9.]+)""").find(Files.readString(skills.resolve("$skill/skill.yaml")))!!.groupValues[1].toDouble()
            for ((base, tiers) in mapOf("pistol-shot" to listOf("", "-t2", "-t3", "-t4"), "sword-slash" to listOf("", "-t2", "-t3", "-t4"))) {
                val damages = tiers.map { damageOf(base + it) }
                assertThat(damages).`as`("$base ladder %s", damages).isSorted().doesNotHaveDuplicates()
            }
        }
    }

    private fun ScenarioScope.botAt(name: String, team: String, at: Vec3): SimEntity = player(name, at = at, team = team)

    private fun ScenarioScope.shopSpot(team: String): Vec3 {
        val stage = sim.services.getOrThrow<IStageRegistry>().find(StageId("siege-isles"))!!
        val npc = stage.shopNpcs.first { it.team == TeamId(team) }
        return Vec3(npc.x + stage.origin.x + 1.0, npc.y + stage.origin.y, npc.z + stage.origin.z)
    }

    @Test
    fun `a bot at its own base shop with the base weapon and the ore buys the tier, without ore or away from base it buys nothing`() {
        scenario("weapon-upgrade-bot-buy") {
            val human = player("human", team = "blue")
            val rich = botAt("Bot_Rich", "red", Vec3(0.0, groundY, 0.0))
            val poor = botAt("Bot_Poor", "red", Vec3(0.0, groundY, 0.0))
            val away = botAt("Bot_Away", "red", Vec3(0.0, groundY, 0.0))
            startMatch("carry-tnt", mapOf(human to "blue", rich to "red", poor to "red", away to "red"))
            advance(60)
            val spot = shopSpot("red")
            rich.position = spot
            poor.position = spot
            away.position = Vec3(spot.x + 60.0, spot.y, spot.z)
            val registry = sim.services.getOrThrow<IItemRegistry>()
            listOf(rich, poor, away).forEach { bot ->
                items(bot).removeAll { it == "knight-sword" }
                items(bot) += "knight-sword"
            }
            repeat(6) { items(rich) += "ore-gold" }
            repeat(6) { items(away) += "ore-gold" }
            repeat(5) { items(poor) += "ore-gold" } // one short of the price

            advance(80)
            assertThat(items(rich).count { it == "knight-sword-t2" }).isEqualTo(1)
            assertThat(items(rich).count { it == "knight-sword" }).isZero()
            assertThat(items(rich).count { it == "ore-gold" }).isZero()
            assertThat(items(poor).count { it == "knight-sword-t2" }).isZero()
            assertThat(items(poor).count { it == "ore-gold" }).isEqualTo(5)
            assertThat(items(away).count { it == "knight-sword-t2" }).isZero()
            assertThat(items(away).count { it == "ore-gold" }).isEqualTo(6)
            assertThat(registry.find(com.astarworks.astera.core.domain.item.ItemId("knight-sword-t2"))).isNotNull()
        }
    }

    @Test
    fun `match end removes the bought tier item`() {
        scenario("weapon-upgrade-match-end") {
            val red = player("red-buyer", team = "red")
            val blue = player("blue-buyer", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            repeat(6) { items(red) += "ore-gold" }
            items(red).removeAll { it == "pistol" }
            items(red) += "pistol"
            buy(red, "pistol-t2")
            assertThat(items(red).count { it == "pistol-t2" }).isEqualTo(1)

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            sim.step(10)
            assertThat(items(red).count { it == "pistol-t2" }).isZero()
            assertThat(items(red).count { it == "ore-gold" }).isZero()
        }
    }

    @Test
    fun `every shipped upgrade offer names real items and a carried base that is a real item`() {
        scenario("weapon-upgrade-content") {
            val loader = MatchShopLoaderService()
            assertThat(loader.loadFrom(GameSim.defaultContentDir().resolve("shops")).failed).isZero()
            val registry = sim.services.getOrThrow<IItemRegistry>()
            val upgrades = loader.catalog.tab(MatchShopTab.UPGRADES)
            assertThat(upgrades).isNotEmpty()
            for (offer in upgrades) {
                assertThat(registry.find(offer.itemId)).describedAs("tier %s", offer.itemId.value).isNotNull()
                assertThat(registry.find(offer.upgradeFrom!!)).describedAs("base %s", offer.upgradeFrom!!.value).isNotNull()
            }
        }
    }
}
