package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.BlockDurabilityYamlConfig
import com.astarworks.astera.core.application.config.StarterGrantYamlConfig
import com.astarworks.astera.core.application.contents.block.CustomBlockContents
import com.astarworks.astera.core.application.contents.effect.SoundContents
import com.astarworks.astera.core.application.contents.music.MusicTrackContents
import com.astarworks.astera.core.application.contents.pve.BossSpecContents
import com.astarworks.astera.core.application.contents.pve.EncounterZoneContents
import com.astarworks.astera.core.application.contents.survival.GatheringSpotContents
import com.astarworks.astera.core.application.i18n.LanguageLoader
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.application.service.mob.DeterministicLootRollService
import com.astarworks.astera.core.application.service.mob.pve.LootTableLoaderService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.charleskorn.kaml.Yaml
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Guards that every file shipped under the repo's real `content/` directory
 * decodes through the production loader that owns it (core-owned categories).
 *
 * Exists because schema/content drift (e.g. kaml's `type` polymorphism
 * discriminator, kebab-case id rules, list-vs-object files) otherwise only
 * surfaces at server boot. A failure names the offending file and message.
 *
 * Sibling plugins carry the same guard for their categories
 * (`ShippedSkillContentDecodesTest`, ...).
 */
class ShippedContentDecodesTest {

    private val contentRoot: Path = locateContentRoot()

    @TestFactory
    fun `every shipped content file decodes through its production loader`(): List<DynamicTest> {
        val cases: List<Pair<String, (Path) -> ContentLoader.LoadReport>> = listOf(
            "items" to { d -> ItemContents().bootstrap(d) },
            "gui" to { d -> GuiContents().bootstrap(d) },
            "blocks" to { d -> CustomBlockContents().bootstrap(d) },
            "particles" to { d -> ParticleContents().bootstrap(d) },
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
            "pve/drop-tables" to { d ->
                LootTableLoaderService().bootstrap(d, DeterministicLootRollService(FakeDomainEventBus()))
            },
            "music" to { d -> MusicTrackContents().bootstrap(d) },
            "news" to { d -> NewsContents().bootstrap(d) },
            "changelog" to { d -> ChangelogContents().bootstrap(d) },
            "login-bonus" to { d -> LoginBonusContents().bootstrap(d) },
            "sounds" to { d -> SoundContents().bootstrap(d) },
        )
        return cases.map { (category, load) ->
            DynamicTest.dynamicTest(category) {
                val dir = contentRoot.resolve(category)
                val report = load(dir)
                assertThat(report.failures)
                    .withFailMessage {
                        "content/$category failed to load: " +
                            report.failures.joinToString("; ") { "${it.file.fileName} -> ${it.message}" }
                    }
                    .isEmpty()
                assertThat(report.failed).`as`("content/$category failed count").isZero
                assertThat(report.loaded)
                    .`as`("content/$category loaded count (expected one per shipped file)")
                    .isEqualTo(shippedFileCount(dir, recursive = category == "sounds"))
            }
        }
    }

    @Test
    fun `permissions yaml at content root decodes`() {
        val contents = RoleContents()
        val report = contents.bootstrap(contentRoot)
        assertThat(report.failures)
            .withFailMessage { "content/ root failed: " + report.failures.joinToString("; ") { "${it.file.fileName} -> ${it.message}" } }
            .isEmpty()
        assertThat(report.loaded).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `language files load for en and ja`() {
        val languages = LanguageLoader.loadFrom(contentRoot.resolve("languages"))
        assertThat(languages.keys).contains("en", "ja")
    }

    @Test
    fun `balance starter-grant and block-durability decode and validate`() {
        val grantText = Files.readString(contentRoot.resolve("balance/starter-grant.yaml"))
        val grant = Yaml.default.decodeFromString(StarterGrantYamlConfig.serializer(), grantText).toDomain()
        assertThat(grant).withFailMessage { "balance/starter-grant.yaml invalid: $grant" }.isInstanceOf(Result.Success::class.java)

        val durText = Files.readString(contentRoot.resolve("balance/block-durability.yaml"))
        val dur = Yaml.default.decodeFromString(BlockDurabilityYamlConfig.serializer(), durText).toDomain()
        assertThat(dur).withFailMessage { "balance/block-durability.yaml invalid: $dur" }.isInstanceOf(Result.Success::class.java)
    }

    private fun shippedFileCount(dir: Path, recursive: Boolean): Int {
        val ext = setOf("yaml", "yml", "toml", "json")
        return Files.walk(dir, if (recursive) 2 else 1).use { s ->
            s.filter { it.isRegularFile() && it.extension.lowercase() in ext }.count().toInt()
        }
    }

    private fun locateContentRoot(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts")) && Files.isDirectory(cur.resolve("content"))) {
                return cur.resolve("content")
            }
            cur = cur.parent
        }
        error("repo root (settings.gradle.kts + content/) not found above ${Path.of("").toAbsolutePath()}")
    }
}
