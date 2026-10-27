package com.astarworks.astera.contentlint

import com.astarworks.astera.combat.application.contents.ArmorContents
import com.astarworks.astera.combat.application.contents.ComboRouteContents
import com.astarworks.astera.combat.application.contents.StatusContents
import com.astarworks.astera.core.application.contents.AdvancementContents
import com.astarworks.astera.core.application.contents.ChangelogContents
import com.astarworks.astera.core.application.contents.GuiContents
import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.contents.LoginBonusContents
import com.astarworks.astera.core.application.contents.MobContents
import com.astarworks.astera.core.application.contents.NewsContents
import com.astarworks.astera.core.application.contents.NpcDialogContents
import com.astarworks.astera.core.application.contents.ParticleSkinContents
import com.astarworks.astera.core.application.contents.QuestChainContents
import com.astarworks.astera.core.application.contents.RecipeContents
import com.astarworks.astera.core.application.contents.RoleContents
import com.astarworks.astera.core.application.contents.SkinRigContents
import com.astarworks.astera.core.application.contents.SpriteContents
import com.astarworks.astera.core.application.contents.TutorialContents
import com.astarworks.astera.core.application.contents.block.CustomBlockContents
import com.astarworks.astera.core.application.contents.effect.SoundContents
import com.astarworks.astera.core.application.contents.music.MusicTrackContents
import com.astarworks.astera.core.application.contents.pve.BossSpecContents
import com.astarworks.astera.core.application.contents.pve.EncounterZoneContents
import com.astarworks.astera.core.application.contents.survival.GatheringSpotContents
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.application.service.mob.DeterministicLootRollService
import com.astarworks.astera.core.application.service.mob.pve.LootTableLoaderService
import com.astarworks.astera.economy.application.contents.betting.BettingPolicyContents
import com.astarworks.astera.economy.application.service.InMemoryShopRegistry
import com.astarworks.astera.economy.application.service.InMemoryShopStockRepository
import com.astarworks.astera.economy.application.service.ShopLoaderService
import com.astarworks.astera.match.application.contents.AnomalyContents
import com.astarworks.astera.match.application.contents.GameModeContents
import com.astarworks.astera.match.application.contents.GeneratorContents
import com.astarworks.astera.match.application.contents.StageContents
import com.astarworks.astera.stats.application.contents.AchievementContents
import com.astarworks.astera.stats.application.contents.NormaContents
import com.astarworks.astera.stats.application.contents.SkillProgressionContents
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Decodes every content category through the same production loader the server uses on boot and on
 * `astera admin reload all` (the Shipped*ContentDecodesTest category lists, minus JUnit). A bad value fails
 * with `RUNTIME-DECODE FAIL content/<category>/<file>: <loader message>`; the loader message names the
 * offending field (e.g. "velocity speed must be >= 0: -1.0"). `make dev-content` runs this before copying.
 */
internal object RuntimeDecode {

    /** [alreadyLoaded]: categories [main] bootstrapped itself (skills need vfx + particles); reported here only. */
    fun run(contentDir: Path, alreadyLoaded: List<Pair<String, ContentLoader.LoadReport>>): Int {
        val cases: List<Pair<String, (Path) -> ContentLoader.LoadReport>> = listOf(
            "items" to { d -> ItemContents().bootstrap(d) },
            "gui" to { d -> GuiContents().bootstrap(d) },
            "blocks" to { d -> CustomBlockContents().bootstrap(d) },
            "sprites" to { d -> SpriteContents().bootstrap(d) },
            "particle-skins" to { d -> ParticleSkinContents().bootstrap(d) },
            "animations" to { d -> SkinRigContents().bootstrap(d) },
            "gathering" to { d -> GatheringSpotContents().bootstrap(d) },
            "tutorials" to { d -> TutorialContents().bootstrap(d) },
            "advancements" to { d -> AdvancementContents().bootstrap(d) },
            "dialogs" to { d -> NpcDialogContents().bootstrap(d) },
            "recipes" to { d -> RecipeContents().bootstrap(d) },
            "quests" to { d -> QuestChainContents().bootstrap(d) },
            "mobs" to { d -> MobContents().bootstrap(d) },
            "pve/encounters" to { d -> EncounterZoneContents().bootstrap(d) },
            "pve/bosses" to { d -> BossSpecContents().bootstrap(d) },
            "pve/drop-tables" to { d -> LootTableLoaderService().bootstrap(d, DeterministicLootRollService(FakeDomainEventBus())) },
            "music" to { d -> MusicTrackContents().bootstrap(d) },
            "news" to { d -> NewsContents().bootstrap(d) },
            "changelog" to { d -> ChangelogContents().bootstrap(d) },
            "login-bonus" to { d -> LoginBonusContents().bootstrap(d) },
            "sounds" to { d -> SoundContents().bootstrap(d) },
            "status" to { d -> StatusContents().bootstrap(d) },
            "armor" to { d -> ArmorContents().bootstrap(d) },
            "combat-depth/combos" to { d -> ComboRouteContents().bootstrap(d) },
            "stages" to { d -> StageContents().bootstrap(d) },
            "anomaly" to { d -> AnomalyContents().bootstrap(d) },
            "generators" to { d -> GeneratorContents().bootstrap(d) },
            "ore-nodes" to { d -> com.astarworks.astera.match.application.contents.OreNodeContents().bootstrap(d) },
            "game-modes" to { d -> GameModeContents().bootstrap(d) },
            "norma" to { d -> NormaContents().bootstrap(d) },
            "skills-progression" to { d -> SkillProgressionContents().bootstrap(d) },
            "achievements" to { d -> AchievementContents().bootstrap(d) },
            "bet-policies" to { d -> BettingPolicyContents().bootstrap(d) },
            "shops" to { d ->
                val r = ShopLoaderService(InMemoryShopRegistry(), InMemoryShopStockRepository()).loadFrom(d)
                ContentLoader.LoadReport(r.loaded, r.failed, r.failures)
            },
            // permissions.yaml sits at the content root.
            "." to { d -> RoleContents().bootstrap(d) },
        )
        val reports = alreadyLoaded.toMutableList()
        for ((category, load) in cases) {
            val dir = contentDir.resolve(category)
            if (!dir.isDirectory()) continue
            reports += category to try {
                load(dir)
            } catch (t: Throwable) {
                ContentLoader.LoadReport(0, 1, listOf(ContentLoader.LoadFailure(dir, "${t::class.simpleName}: ${t.message}")))
            }
        }
        var failed = 0
        for ((category, report) in reports) {
            for (f in report.failures) {
                failed++
                println("RUNTIME-DECODE FAIL content/$category/${f.file.fileName}: ${f.message}")
            }
            if (report.failures.isEmpty() && report.failed > 0) {
                failed += report.failed
                println("RUNTIME-DECODE FAIL content/$category: ${report.failed} file(s) failed (see log above)")
            }
        }
        println("runtimeDecode: ${reports.size} categories, $failed failure(s)")
        return failed
    }
}
