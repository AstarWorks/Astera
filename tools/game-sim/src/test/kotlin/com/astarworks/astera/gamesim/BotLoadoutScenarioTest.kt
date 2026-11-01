package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.item.IMatchEntryLoadout
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerEnteredMatch
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.WeaponCategory
import com.astarworks.astera.match.application.contents.BotLoadoutContents
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.service.loadout.BotLoadoutService
import com.astarworks.astera.match.application.port.outbound.IMatchNormaStore
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.norma.NormaProgress
import com.astarworks.astera.match.domain.loadout.BotWeaponFamily
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Bots (protocol clients, accounts `Bot_*`) cannot give themselves items, so the match hands each a loadout of two weapons
 * of two different families from `content/bot-loadouts/` right after the entry snapshot. The real wiring runs: queue ->
 * match -> Core's snapshot listener -> `IMatchEntryLoadout`.
 *
 *  - Shipped content: only weapons usable at Norma level 1 are drawn (common), so humans stay untouched and every bot
 *    carries two weapons of two families.
 *  - A rarity ladder that opens uncommon / rare at level 1 widens the usable pool: four bots of one match get four
 *    different pairs, each of two families that fit the weapon limit, none of them an excluded piece.
 *  - The loadout is added after the entry snapshot (so the match-end restore removes it) and the match forgets it at end.
 */
class BotLoadoutScenarioTest {

    private val pool = BotLoadoutContents().also {
        assertThat(it.bootstrap(GameSim.defaultContentDir().resolve("bot-loadouts")).failures).isEmpty()
    }.current()!!

    private fun familyOf(item: String): BotWeaponFamily =
        pool.families.entries.single { (_, ids) -> ItemId(item) in ids }.key

    private fun categoryOf(sim: GameSim, item: String): WeaponCategory? =
        sim.services.getOrThrow<IItemRegistry>().find(ItemId(item))?.let(WeaponCategory::of)

    private fun weaponsOf(sim: GameSim, entity: SimEntity): List<String> =
        checkNotNull(sim.server.view(entity.id)).givenItems.filter { categoryOf(sim, it) != null }

    /** A copy of the real `content/` (symlinks) whose Norma layer opens every rarity below epic at level 1. */
    private fun openRarityContent(): Path {
        val real = GameSim.defaultContentDir()
        val dir = Files.createTempDirectory("sim-content-open-rarity")
        Files.list(real).use { entries ->
            entries.filter { it.fileName.toString() != "norma-layer" }
                .forEach { Files.createSymbolicLink(dir.resolve(it.fileName.toString()), it) }
        }
        val layer = Files.readString(real.resolve("norma-layer/layer.yaml"))
        val opened = layer.replace("rarity_levels: \"common:1,uncommon:2,rare:3,epic:4,legendary:5\"", "rarity_levels: \"common:1,uncommon:1,rare:1,epic:4,legendary:5\"")
        check(opened != layer) { "layer.yaml rarity_levels line changed; update this scenario" }
        Files.createDirectory(dir.resolve("norma-layer"))
        Files.writeString(dir.resolve("norma-layer/layer.yaml"), opened)
        return dir
    }

    @Test
    fun `with the shipped ladder a bot carries two weapons of two families usable at level 1 and a human is untouched`() {
        val sim = GameSim.boot("bot-loadout-shipped", snapshots = InMemoryPlayerInventorySnapshotRepository())
        try {
            val human = sim.spawnPlayer("human")
            val bots = listOf(sim.spawnPlayer("Bot_Ash"), sim.spawnPlayer("Bot_Birch"))
            sim.step(5)
            val humanBefore = checkNotNull(sim.server.view(human.id)).givenItems.toList()

            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            (bots + human).forEach { matchmaker.enqueue(it.id, MatchmakingMode.CARRY_TNT) }
            assertThat(sim.stepUntil(900) { sim.eventsOf<PlayerEnteredMatch>().size == 3 }).isTrue()

            assertThat(checkNotNull(sim.server.view(human.id)).givenItems).containsExactlyElementsOf(humanBefore + ShippedStarterGrant.matchTools)
            bots.forEach { bot ->
                val weapons = weaponsOf(sim, bot)
                assertThat(weapons).hasSize(2)
                assertThat(weapons.map { familyOf(it) }.toSet()).`as`("two families for ${bot.name}: $weapons").hasSize(2)
                assertThat(weapons.map { categoryOf(sim, it) }.toSet()).`as`("fits gun:1,sword:1,staff:1").hasSize(2)
                weapons.forEach { id ->
                    val rarity = sim.services.getOrThrow<IItemRegistry>().find(ItemId(id))!!.rarity
                    // BotLoadoutService.entryLevel: the lobby's human average (level 1 here) moved by -1/0/+1 per bot, seeded by the random
                    // match id. So a bot may enter at level 2 and draw UNCOMMON (shipped ladder common:1, uncommon:2); rare and above stay shut.
                    assertThat(rarity.name).`as`("$id is usable at Norma level 1 or 2 (human average 1, +-1)").isIn("COMMON", "UNCOMMON")
                }
            }
        } finally {
            sim.shutdown()
        }
    }

    @Test
    fun `bots of one match get different two-family loadouts from the wider pool, outside the excluded pieces`() {
        val sim = GameSim.boot("bot-loadout-variety", contentDir = openRarityContent(), snapshots = InMemoryPlayerInventorySnapshotRepository())
        try {
            val bots = listOf("Bot_Ash", "Bot_Birch", "Bot_Cedar", "Bot_Dusk").map { sim.spawnPlayer(it) }
            sim.step(5)
            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            bots.forEach { matchmaker.enqueue(it.id, MatchmakingMode.CARRY_TNT) }
            assertThat(sim.stepUntil(900) { sim.eventsOf<PlayerEnteredMatch>().size == 4 }).isTrue()

            val loadouts = bots.map { weaponsOf(sim, it) }
            loadouts.forEach { weapons ->
                assertThat(weapons).hasSize(2)
                assertThat(weapons.map { familyOf(it) }.toSet()).`as`("two families: $weapons").hasSize(2)
                assertThat(weapons.map { categoryOf(sim, it) }.toSet()).`as`("fits the weapon limit: $weapons").hasSize(2)
            }
            assertThat(loadouts.map { it.toSet() }.toSet()).`as`("four bots, four different pairs: $loadouts").hasSize(4)
            // Pieces deliberately kept out of the bot pool (content/bot-loadouts/loadouts.yaml): the Limbus
            // families whose skills were removed, plus the two kept Limbus families (bastion, couture).
            val excluded = listOf(
                "crimson-chain-axe", "edict-blade", "ember-gauntlet", "gossamer-katana", "orrery-staff", "sigil-rifle",
                "verdict-hammer", "vendetta-rifle", "bastion-greatsword", "couture-rapier",
            )
            assertThat(loadouts.flatten()).doesNotContainAnyElementsOf(excluded)
        } finally {
            sim.shutdown()
        }
    }

    @Test
    fun `the loadout is added after the entry snapshot and the match forgets it at the end`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val sim = GameSim.boot("bot-loadout-removal", contentDir = openRarityContent(), snapshots = repo)
        try {
            val bots = listOf("Bot_Ash", "Bot_Birch").map { sim.spawnPlayer(it) }
            sim.step(5)
            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            bots.forEach { matchmaker.enqueue(it.id, MatchmakingMode.CARRY_TNT) }
            assertThat(sim.stepUntil(900) { sim.eventsOf<PlayerEnteredMatch>().size == 2 }).isTrue()

            val service = sim.services.getOrThrow<IMatchEntryLoadout>() as BotLoadoutService
            val matchId = sim.services.getOrThrow<IMatchRepository>().findActiveMatches().single().id
            bots.forEach { bot ->
                assertThat(service.loadoutOf(matchId, bot.id)).hasSize(2)
                // The entry snapshot (what the restore writes back) holds none of the loaned weapons.
                assertThat(repo.findLatest(bot.id)!!.hotbar.filterNotNull()).isEmpty()
            }

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            assertThat(sim.stepUntil(200) { sim.eventsOf<MatchEnded>().isNotEmpty() }).isTrue()
            sim.step(10)
            bots.forEach { bot ->
                assertThat(service.loadoutOf(matchId, bot.id)).isNull()
                assertThat(checkNotNull(sim.inventory).items(bot.id)).`as`("restored to the (empty) pre-match hotbar").isEmpty()
            }
        } finally {
            sim.shutdown()
        }
    }

    @Test
    fun `a team of bots at mixed Norma levels carries at least three distinct weapons of both ranged and melee kinds`() {
        val sim = GameSim.boot("bot-loadout-levels", snapshots = InMemoryPlayerInventorySnapshotRepository())
        try {
            val bots = listOf("Bot_Ash", "Bot_Birch", "Bot_Cedar", "Bot_Dusk").map { sim.spawnPlayer(it) }
            sim.step(5)
            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            bots.forEach { matchmaker.enqueue(it.id, MatchmakingMode.CARRY_TNT) }
            assertThat(sim.stepUntil(900) { sim.eventsOf<PlayerEnteredMatch>().size == 4 }).isTrue()

            val service = sim.services.getOrThrow<IMatchEntryLoadout>() as BotLoadoutService
            val matchId = sim.services.getOrThrow<IMatchRepository>().findActiveMatches().single().id
            // No humans: every bot enters at level 1 (spread to 1-2, so no rare piece); the Norma check then lifts three of them to levels 2, 3 and 5.
            val entry = bots.map { checkNotNull(service.loadoutOf(matchId, it.id)) }
            assertThat(entry.flatten().map { it.value }).`as`("no rare weapon at a level 1-2 entry").doesNotContain("sniper", "greatsword")

            val bus = sim.services.getOrThrow<IDomainEventBus>()
            listOf(bots[1] to 2, bots[2] to 3, bots[3] to 5).forEach { (bot, level) -> bus.publish(NormaLevelUp(matchId, bot.id, level)) }
            sim.step(5)

            val loadouts = bots.map { checkNotNull(service.loadoutOf(matchId, it.id)) }
            val weapons = loadouts.flatten().map { it.value }.toSet()
            assertThat(weapons).`as`("distinct weapons across the team: $loadouts").hasSizeGreaterThanOrEqualTo(3)
            val families = loadouts.flatten().map { familyOf(it.value) }.toSet()
            assertThat(families).contains(BotWeaponFamily.RANGED, BotWeaponFamily.MELEE)
            bots.zip(loadouts).forEach { (bot, loadout) ->
                assertThat(loadout).hasSize(2)
                assertThat(weaponsOf(sim, bot)).`as`("${bot.name} carries its re-rolled loadout").containsAll(loadout.map { it.value })
            }
            // A re-roll never hands out a weapon whose rarity the level has not opened.
            val registry = sim.services.getOrThrow<IItemRegistry>()
            val norma = listOf(1 to 2, 2 to 3, 3 to 5).associate { (i, level) -> bots[i] to level }
            norma.forEach { (bot, level) ->
                checkNotNull(service.loadoutOf(matchId, bot.id)).forEach { id ->
                    val needed = mapOf("COMMON" to 1, "UNCOMMON" to 2, "RARE" to 3, "EPIC" to 4, "LEGENDARY" to 5).getValue(registry.find(id)!!.rarity.name)
                    assertThat(needed).`as`("${bot.name} at level $level holds ${id.value}").isLessThanOrEqualTo(level)
                }
            }
        } finally {
            sim.shutdown()
        }
    }

    @Test
    fun `bots entering a match whose humans average Norma level 5 roll from the wide pool at entry`() {
        val sim = GameSim.boot("bot-loadout-human-level", snapshots = InMemoryPlayerInventorySnapshotRepository())
        try {
            val humans = listOf(sim.spawnPlayer("human-a"), sim.spawnPlayer("human-b"))
            val bots = listOf("Bot_Ash", "Bot_Birch", "Bot_Cedar", "Bot_Dusk").map { sim.spawnPlayer(it) }
            sim.step(5)
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val stage = com.astarworks.astera.core.domain.stage.StageId("siege-isles-tnt")
            val matchId = (lifecycle.start(stage, "carry-tnt") as com.astarworks.astera.core.domain.Result.Success).value
            humans.forEachIndexed { i, human ->
                lifecycle.entry(matchId, human.id, com.astarworks.astera.core.domain.match.TeamId("red"))
                // Levels 4 and 6: the average is 5.
                sim.services.getOrThrow<IMatchNormaStore>().put(matchId, human.id, NormaProgress(level = if (i == 0) 4 else 6))
            }
            sim.step(5)
            bots.forEachIndexed { i, bot -> lifecycle.entry(matchId, bot.id, com.astarworks.astera.core.domain.match.TeamId(if (i % 2 == 0) "red" else "blue")) }
            sim.step(10)

            val service = sim.services.getOrThrow<IMatchEntryLoadout>() as BotLoadoutService
            val loadouts = bots.map { checkNotNull(service.loadoutOf(matchId, it.id)) }
            val weapons = loadouts.flatten().map { it.value }.toSet()
            assertThat(weapons).`as`("distinct weapons at entry: $loadouts").hasSizeGreaterThanOrEqualTo(3)
            assertThat(loadouts.flatten().map { familyOf(it.value) }.toSet()).contains(BotWeaponFamily.RANGED, BotWeaponFamily.MELEE)
            // Level 5 +-1 opens every rarity up to rare at least (rare:3), so no bot is stuck on the starter pair.
            assertThat(loadouts.map { it.toSet() }.toSet()).isNotEqualTo(setOf(setOf(ItemId("pistol"), ItemId("knight-sword"))))
            bots.zip(loadouts).forEach { (bot, loadout) -> assertThat(weaponsOf(sim, bot)).containsAll(loadout.map { it.value }) }
        } finally {
            sim.shutdown()
        }
    }
}
