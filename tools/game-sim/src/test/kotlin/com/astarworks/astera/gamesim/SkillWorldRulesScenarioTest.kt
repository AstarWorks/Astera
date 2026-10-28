package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.service.SkillBlockError
import com.astarworks.astera.combat.application.service.SkillWorldRules
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * SKL-17 (WP-E): the skill engine's block seam stops dropping things silently. The rules the Paper gateway runs
 * (`SkillWorldRules`) are driven here against the real shipped items, real block gateway and real custom blocks.
 */
class SkillWorldRulesScenarioTest {

    private val content = Path.of(requireNotNull(System.getProperty("astera.sim.contentDir")) { "astera.sim.contentDir is not set" })

    /** Every shipped skill file that declares `tool_class:` with the item it hangs on. */
    private fun toolSkills(): List<Triple<String, String, String>> {
        val item = Regex("""(?m)^item:\s*['"]?([A-Za-z0-9_\-]+)""")
        val toolClass = Regex("""tool_class:\s*['"]?([A-Za-z]+)""")
        return Files.list(content.resolve("skills")).use { dirs ->
            dirs.toList().mapNotNull { dir ->
                val file = dir.resolve("skill.yaml").takeIf { Files.exists(it) } ?: return@mapNotNull null
                val text = Files.readString(file)
                val cls = toolClass.find(text)?.groupValues?.get(1) ?: return@mapNotNull null
                Triple(dir.fileName.toString(), cls, item.find(text)?.groupValues?.get(1) ?: "")
            }
        }
    }

    @Test
    fun `every shipped tool skill resolves a class and tier from its own item`() {
        scenario("skill-world-tool-tiers") {
            val items = sim.services.getOrThrow<IItemRegistry>()
            val skills = toolSkills()
            assertThat(skills).describedAs("shipped break_area tool skills").hasSizeGreaterThan(10)
            for ((id, cls, item) in skills) {
                val tool = HitCustomBlockUseCase.toolOf(items, ItemId(item))
                val r = SkillWorldRules.profile(id, cls, 100, tool, item)
                assertThat(r).describedAs("$id ($cls on $item)").isInstanceOf(Result.Success::class.java)
                val profile = (r as Result.Success).value
                assertThat(profile.flat).isFalse()
                assertThat(profile.effectiveTool.kind.name).isEqualTo(cls.uppercase())
            }
        }
    }

    @Test
    fun `tool class is honoured by the real block gateway and a weapon only chips`() {
        scenario("skill-world-tool-damage") {
            val caster = player("caster", team = "red", facing = Vec3(1.0, 0.0, 0.0))
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val registry = sim.services.getOrThrow<ICustomBlockRegistry>()
            val items = sim.services.getOrThrow<IItemRegistry>()
            val spec = checkNotNull(registry.find(BlockType("astera:stone_bulwark")))
            val world = caster.world

            fun hit(at: Vec3, skill: String, toolClass: String?, item: String, damage: Int): Int {
                blocks.placeCustomBlock(world, at, spec, caster.id)
                val before = checkNotNull(blocks.getDurability(world, at))
                val profile = (SkillWorldRules.profile(skill, toolClass, damage, HitCustomBlockUseCase.toolOf(items, ItemId(item)), item) as Result.Success).value
                blocks.applyDamageFromSkill(world, at, SkillId(skill), caster.id, profile)
                return before - checkNotNull(blocks.getDurability(world, at))
            }

            val iron = hit(Vec3(2.0, groundY + 1, 0.0), "pickaxe-bore-iron", "PICKAXE", "iron-pickaxe", 100)
            val diamond = hit(Vec3(3.0, groundY + 1, 0.0), "pickaxe-bore-diamond", "PICKAXE", "diamond-pickaxe", 100)
            val wrongClass = hit(Vec3(4.0, groundY + 1, 0.0), "axe-fell-iron", "AXE", "iron-axe", 100)
            val chip = hit(Vec3(5.0, groundY + 1, 0.0), "pistol-shot", null, "pistol", 7)

            assertThat(diamond).describedAs("tier matters").isGreaterThan(iron)
            assertThat(wrongClass).describedAs("class matters").isLessThan(iron)
            assertThat(chip).describedAs("no tool class = flat chip").isEqualTo(7)
        }
    }

    @Test
    fun `a tool class that cannot be resolved is an error, never a flat chip`() {
        scenario("skill-world-tool-errors") {
            val items = sim.services.getOrThrow<IItemRegistry>()
            val unknown = SkillWorldRules.profile("x-skill", "HAMMER", 100, null, "iron-pickaxe")
            assertThat((unknown as Result.Failure).error).isInstanceOf(SkillBlockError.UnknownToolClass::class.java)

            // A pistol is no pickaxe: its skill cannot borrow a tier.
            val wrongItem = SkillWorldRules.profile("x-skill", "PICKAXE", 100, HitCustomBlockUseCase.toolOf(items, ItemId("pistol")), "pistol")
            assertThat((wrongItem as Result.Failure).error).isInstanceOf(SkillBlockError.NoToolTier::class.java)

            val axeForPickaxe = SkillWorldRules.profile("x-skill", "PICKAXE", 100, HitCustomBlockUseCase.toolOf(items, ItemId("iron-axe")), "iron-axe")
            assertThat(axeForPickaxe).isInstanceOf(Result.Failure::class.java)
        }
    }

    @Test
    fun `a custom item is matched by its Astera id and a plain stack by its material`() {
        // A heal-potion is built on a vanilla potion material: asking for the vanilla key must not eat it.
        assertThat(SkillWorldRules.stackMatches("heal-potion", "heal-potion", "minecraft:potion")).isTrue()
        assertThat(SkillWorldRules.stackMatches("minecraft:potion", "heal-potion", "minecraft:potion")).isFalse()
        assertThat(SkillWorldRules.stackMatches("swift-powder", "heal-potion", "minecraft:potion")).isFalse()
        // A plain stack never satisfies a custom id, and matches vanilla keys with or without the namespace.
        assertThat(SkillWorldRules.stackMatches("heal-potion", null, "minecraft:potion")).isFalse()
        assertThat(SkillWorldRules.stackMatches("minecraft:stone", null, "minecraft:stone")).isTrue()
        assertThat(SkillWorldRules.stackMatches("stone", null, "minecraft:stone")).isTrue()
    }

    @Test
    fun `a running cast tells the gateway its real skill id`() {
        val sim = CastSim(victims = emptyMap())
        val cast = sim.start("pickaxe-bore-iron")
        val def = checkNotNull(sim.kit.hosts.skillOf(cast)) { "the running cast must expose its definition" }
        assertThat(def.baseId).isEqualTo("pickaxe-bore-iron")
        assertThat(def.item).isEqualTo("iron-pickaxe")
        assertThat(SkillId(def.baseId).value).isEqualTo("pickaxe-bore-iron")
    }
}
