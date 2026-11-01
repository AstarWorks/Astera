package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.ItemSkillCatalog
import com.astarworks.astera.bots.SkillUsePolicy
import com.astarworks.astera.match.application.contents.BotLoadoutContents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every weapon a bot can roll (`content/bot-loadouts/`) uses its OWN skills: the bindings come from the shipped
 * `content/items` + `content/skills` through [ItemSkillCatalog] (the object the live BotBrain asks), found from the
 * hotbar display name, never from a per-weapon list. For each pool weapon and each extra binding, a target in the
 * binding's range with line of sight yields that trigger; out of range, without line of sight or on cooldown yields none.
 * The primary fire (sword swing, the gun's right-click shot) and the reload stay with the brain's own loop.
 */
class BotItemSkillScenarioTest {

    private val content = GameSim.defaultContentDir().toFile()
    private val catalog = ItemSkillCatalog.load(content)
    private val pool = BotLoadoutContents().also {
        assertThat(it.bootstrap(GameSim.defaultContentDir().resolve("bot-loadouts")).failures).isEmpty()
    }.current()!!
    private val poolIds = pool.families.values.flatten().map { it.value }

    @Test
    fun `every pool weapon is found by its display name and no extra binding is left unclassified`() {
        assertThat(catalog.unclassified.filter { entry -> poolIds.any { entry.startsWith("$it:") } })
            .`as`("extra bindings of pool weapons no range rule fits").isEmpty()
        for (id in poolIds) {
            val item = checkNotNull(catalog.item(id)) { "pool weapon $id is not in the catalog" }
            assertThat(item.names).`as`("$id display names").isNotEmpty()
            item.names.forEach { name ->
                assertThat(catalog.findByDisplayName("[$name] x1")?.id).`as`("hotbar name containing '$name'").isEqualTo(id)
            }
        }
    }

    @Test
    fun `each extra skill of each pool weapon is cast in its range with line of sight and on its own cooldown`() {
        var casts = 0
        for (id in poolIds) {
            val item = checkNotNull(catalog.item(id))
            for (b in item.extras) {
                val mid = (b.minRange + b.maxRange) / 2
                val fresh = SkillUsePolicy(listOf(b))
                assertThat(fresh.decide(0, mid, false)).`as`("$id/${b.id} without line of sight").isNull()
                assertThat(fresh.decide(0, b.maxRange + 1.0, true)).`as`("$id/${b.id} beyond ${b.maxRange}").isNull()
                if (b.minRange > 0.0) assertThat(fresh.decide(0, b.minRange - 0.5, true)).`as`("$id/${b.id} under ${b.minRange}").isNull()
                val decided = fresh.decide(0, mid, true)
                assertThat(decided?.trigger).`as`("$id/${b.id} at $mid").isEqualTo(b.trigger)
                assertThat(fresh.decide(1, mid, true)).`as`("$id/${b.id} cooling down").isNull()
                assertThat(fresh.decide(b.cooldownMs + 1, mid, true)?.id).`as`("$id/${b.id} ready again").isEqualTo(b.id)
                casts++
                // The same through the item's whole policy (display name lookup, bindings in file order).
                val whole = catalog.policyFor(item.names.first())
                val any = whole.decide(0, mid, true)
                assertThat(any).`as`("$id whole policy at $mid").isNotNull()
            }
        }
        assertThat(casts).`as`("the pool has many skill-bearing weapons").isGreaterThan(20)
    }

    @Test
    fun `the primary fire and the reload are never extra skills and melee and ranged items get theirs`() {
        for (id in poolIds) {
            val item = checkNotNull(catalog.item(id))
            assertThat(item.extras.map { it.trigger }).`as`("$id extras")
                .doesNotContain(if (item.primary == "left_click") SkillUsePolicy.Trigger.LEFT_CLICK else SkillUsePolicy.Trigger.RIGHT_CLICK)
        }
        val sword = checkNotNull(catalog.item("knight-sword")).extras.single()
        assertThat(sword.id).isEqualTo("sword-dash")
        assertThat(sword.trigger).isEqualTo(SkillUsePolicy.Trigger.SNEAK_RIGHT)
        assertThat(sword.cooldownMs).isEqualTo(5_000L)
        assertThat(checkNotNull(catalog.item("pistol")).extras).`as`("the pistol has only the shot and the reload").isEmpty()
        assertThat(checkNotNull(catalog.item("revolver")).extras.map { it.id }).`as`("a gun's own sneak skill").contains("revolver-fan")
        assertThat(checkNotNull(catalog.item("frost-staff")).extras.map { it.id }).`as`("a staff's own sneak skill").contains("frost-nova")
        assertThat(checkNotNull(catalog.item("cleaver")).extras.map { it.id }).contains("cleaver-rend")
    }
}
