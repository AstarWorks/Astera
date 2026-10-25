package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.application.admin.AdminSkillCatalog
import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.math.max

/**
 * MODE-09 / MODE-08 against the real `content/`: the static time-to-kill table of
 * `docs/game-design/weapons/ttk.md` (same arithmetic as `scripts/ttk-table.py`) stays inside the
 * per-archetype bands, no weapon out-damages the roster, and dev / demo content is flagged so
 * the admin catalogs do not list it.
 *
 * Nominal TTK of a 20-HP player: primary attack = the left-click skill, else right-click; damage =
 * every literal `damage` component of one cast (followed through `cast_skill`); players take one
 * direct hit per 20 ticks (invulnerability frames), so interval = max(cooldown, 20) and
 * TTK = (hits - 1) * interval. DoTs (bleed, burn) and crits only shorten a real kill; the live
 * measurement stays in `WeaponRosterTtkScenarioTest`.
 */
class ShippedWeaponTtkBandsTest {

    private val content: Path = locateContentRoot()

    private val items = ItemContents().also { assertThat(it.bootstrap(content.resolve("items")).failures).isEmpty() }

    private enum class Band(val label: String, val min: Double, val max: Double) {
        BASIC_GUN("basic gun", 1.0, 3.0),
        BASIC_SWORD("basic sword", 1.0, 3.5),
        BASIC_STAFF("basic staff", 3.0, 5.0),
        FLAGSHIP("flagship s1 (sequence)", 3.0, 6.0),
    }

    private data class Row(val id: String, val band: Band, val ttk: Double, val dps: Double)

    private fun isDemo(spec: ItemSpec) = spec.pdcTags["astera:demo"] == "true"

    private fun rows(): List<Row> = items.all()
        .filter { it.equipSlot == EquipSlot.MAINHAND && !isDemo(it) }
        .mapNotNull { spec ->
            val binding = spec.skillBindings.firstOrNull { it.trigger == SkillTrigger.LEFT_CLICK }
                ?: spec.skillBindings.firstOrNull { it.trigger == SkillTrigger.RIGHT_CLICK }
                ?: return@mapNotNull null
            val skill = loadSkill(binding.skillId.value)
            val damage = damageOf(skill, setOf(binding.skillId.value))
            if (damage <= 0.0) return@mapNotNull null
            val cooldown = binding.cooldownOverrideTicks ?: scalar(skill, "cooldown_ticks")?.toInt() ?: 0
            val interval = max(cooldown, I_FRAMES)
            val hits = ceil(HP / damage).toInt()
            val flagship = scalar(skill, "archetype") == "sequence"
            val id = spec.id.value
            val band = when {
                flagship -> Band.FLAGSHIP
                spec.resourceType == ResourceType.Ammo || "rifle" in id -> Band.BASIC_GUN
                "staff" in id -> Band.BASIC_STAFF
                else -> Band.BASIC_SWORD
            }
            Row(id, band, (hits - 1) * interval / 20.0, damage * 20.0 / interval)
        }

    @Test
    fun `every production weapon sits in its archetype TTK band`() {
        val rows = rows()
        assertThat(rows.map { it.id }).contains("pistol", "rifle", "shotgun", "knight-sword", "fire-staff", "verdict-hammer")
        val outside = rows.filter { it.ttk < it.band.min || it.ttk > it.band.max }
            .map { "${it.id}: ${"%.2f".format(it.ttk)}s outside ${it.band.label} [${it.band.min}, ${it.band.max}]" }
        assertThat(outside).`as`("weapons outside their TTK band").isEmpty()
    }

    @Test
    fun `no weapon dominates - nominal DPS stays at or below the burst weapons and flagships stay below basics`() {
        val rows = rows()
        assertThat(rows.filter { it.dps > MAX_DPS }.map { "${it.id} ${"%.1f".format(it.dps)}" }).isEmpty()
        val flagshipMax = rows.filter { it.band == Band.FLAGSHIP }.maxOf { it.dps }
        val basicGunSwordMin = rows.filter { it.band == Band.BASIC_GUN || it.band == Band.BASIC_SWORD }.minOf { it.dps }
        assertThat(flagshipMax).`as`("flagship s1 trades raw DPS for kit depth").isLessThanOrEqualTo(basicGunSwordMin)
    }

    @Test
    fun `no flagship weapon needs more than level 4`() {
        val flagships = items.all().filter { it.rarity.ordinal >= 2 && it.equipSlot == EquipSlot.MAINHAND && !isDemo(it) }
        assertThat(flagships.filter { it.levelRequirement > MAX_FLAGSHIP_LEVEL }.map { it.id.value }).isEmpty()
        assertThat(flagships.count { it.levelRequirement <= EARLY_LEVEL }).`as`("flagships reachable on day one").isGreaterThanOrEqualTo(4)
    }

    @Test
    fun `demo items and skills are flagged and hidden from the admin catalogs`() {
        val demoItems = items.all().filter(::isDemo).map { it.id.value }.toSet()
        assertThat(demoItems).containsExactlyInAnyOrder("rtm-blaster", "skill-tester")

        val skillDir = content.resolve("skills")
        val demoSkillIds = Files.list(skillDir).use { s -> s.toList() }
            .filter { it.fileName.toString().let { n -> n.startsWith("vfx-demo-") || n.startsWith("mechanics-demo-") || n.startsWith("anim-demo-") || n == "simple-shoot.yaml" } }
            .map { it.fileName.toString().removeSuffix(".yaml") }
        assertThat(demoSkillIds).isNotEmpty
        for (id in demoSkillIds) {
            val tags = (loadSkill(id).get<YamlList>("tags")?.items ?: emptyList()).filterIsInstance<YamlScalar>().map { it.content }
            assertThat(tags).`as`("$id carries the demo tag").contains(AdminSkillCatalog.DEMO_TAG.value)
        }
        val skills = SkillContents().also { assertThat(it.bootstrap(skillDir).failures).isEmpty() }.all()
        val listed = AdminSkillCatalog.entries(skills, null).map { it.id.value }
        assertThat(listed).`as`("admin Skills page").isNotEmpty.doesNotContainAnyElementsOf(demoSkillIds)
    }

    // ---- tiny YAML helpers (generic walk: the skill DSL has dozens of effect shapes) ----

    private fun loadSkill(id: String): YamlMap =
        Yaml.default.parseToYamlNode(Files.readString(content.resolve("skills/$id.yaml"))) as YamlMap

    private fun scalar(map: YamlMap, key: String): String? = map.get<YamlScalar>(key)?.content

    private fun damageOf(node: YamlNode, seen: Set<String>): Double {
        var total = 0.0
        when (node) {
            is YamlMap -> {
                val type = scalar(node, "type")
                if (type == "damage") total += literalSum(node.get<YamlList>("components"))
                if (type == "melee") total += literalSum(node.get<YamlList>("damage"))
                val cast = scalar(node, "skill_id")
                if (type == "cast_skill" && cast != null && cast !in seen && Files.exists(content.resolve("skills/$cast.yaml"))) {
                    total += damageOf(loadSkill(cast), seen + cast)
                }
                for (child in node.entries.values) total += damageOf(child, seen)
            }
            is YamlList -> for (child in node.items) total += damageOf(child, seen)
            else -> {}
        }
        return total
    }

    private fun literalSum(list: YamlList?): Double = list?.items.orEmpty().filterIsInstance<YamlMap>().sumOf { comp ->
        val base = comp.get<YamlMap>("base")
        if (base != null && scalar(base, "type") == "literal") scalar(base, "value")?.toDouble() ?: 0.0 else 0.0
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

    private companion object {
        const val HP = 20.0
        const val I_FRAMES = 20
        const val MAX_DPS = 10.5
        const val MAX_FLAGSHIP_LEVEL = 4
        const val EARLY_LEVEL = 3
    }
}
