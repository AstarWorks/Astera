package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.application.contents.ParticleContents
import com.astarworks.astera.core.domain.effect.ParticleId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * F2-4 guard: every particle a shipped skill emits (cast / hit phases and the
 * projectile `visual.trail`) must resolve to a content/particles definition that
 * names a real vanilla particle — a mapping-less definition renders nothing, which
 * is how skills silently lost their visuals before. Projectile skills must also
 * declare a visible in-flight look (no more invisible bolts).
 */
class SkillVisualContentTest {

    private val contentRoot: Path = locateContentRoot()

    private val particles = ParticleContents().also { it.bootstrap(contentRoot.resolve("particles")) }
    private val skills = SkillContents().also { it.bootstrap(contentRoot.resolve("skills")) }

    private val skillFiles: List<Path> = Files.list(contentRoot.resolve("skills")).use { s ->
        s.filter { it.isRegularFile() && it.extension == "yaml" }.toList()
    }

    @Test
    fun `every particle id a skill uses exists and maps to a vanilla particle`() {
        val missing = mutableListOf<String>()
        for (file in skillFiles) {
            val text = Files.readString(file)
            val ids = PARTICLE_ID.findAll(text).map { it.groupValues[1] }.toList() +
                TRAIL.findAll(text).flatMap { m -> m.groupValues[1].split(',').map { it.trim() }.asSequence() }.toList()
            for (id in ids) {
                val def = particles.find(ParticleId(id))
                when {
                    def == null -> missing += "${file.fileName}: unknown particle '$id'"
                    def.vanillaParticle == null -> missing += "${file.fileName}: '$id' has no vanilla_particle"
                    else -> if (def.vanillaParticle!!.substringAfter(':') !in PAPER_26_3_PARTICLES) {
                        missing += "${file.fileName}: '$id' -> unknown vanilla particle '${def.vanillaParticle}'"
                    }
                }
            }
        }
        assertThat(missing).isEmpty()
    }

    @Test
    fun `every projectile skill has a visible in-flight look`() {
        val invisible = skills.all()
            .map { it.id to it.params }
            .filter { (_, p) -> p is SkillParams.ProjectileParams }
            .filter { (_, p) ->
                val v = (p as SkillParams.ProjectileParams).visual
                v.trail.isEmpty() && v.display == null
            }
            .map { it.first.value }
        assertThat(invisible).`as`("projectile skills without params.visual").isEmpty()
    }

    @Test
    fun `no shipped definition still uses the dead example particles in a skill`() {
        val offenders = skillFiles.filter { Files.readString(it).contains("example-burst") || Files.readString(it).contains("example-ring") }
        assertThat(offenders.map { it.fileName.toString() }).isEmpty()
    }

    private fun locateContentRoot(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts")) && Files.isDirectory(cur.resolve("content"))) {
                return cur.resolve("content")
            }
            cur = cur.parent
        }
        error("repo root (settings.gradle.kts + content/) not found")
    }

    private companion object {
        /** `org.bukkit.Particle` constants of paper-api 26.3 (Konsist forbids importing Bukkit in combat tests). */
        val PAPER_26_3_PARTICLES: Set<String> = setOf(
        "poof",
        "explosion",
        "explosion_emitter",
        "firework",
        "bubble",
        "splash",
        "fishing",
        "underwater",
        "crit",
        "enchanted_hit",
        "smoke",
        "large_smoke",
        "effect",
        "instant_effect",
        "entity_effect",
        "witch",
        "dripping_water",
        "dripping_lava",
        "angry_villager",
        "happy_villager",
        "mycelium",
        "note",
        "portal",
        "enchant",
        "flame",
        "lava",
        "cloud",
        "dust",
        "item_snowball",
        "item_slime",
        "heart",
        "item",
        "block",
        "rain",
        "elder_guardian",
        "dragon_breath",
        "end_rod",
        "damage_indicator",
        "sweep_attack",
        "falling_dust",
        "totem_of_undying",
        "spit",
        "squid_ink",
        "bubble_pop",
        "current_down",
        "bubble_column_up",
        "nautilus",
        "dolphin",
        "sneeze",
        "campfire_cosy_smoke",
        "campfire_signal_smoke",
        "composter",
        "flash",
        "falling_lava",
        "landing_lava",
        "falling_water",
        "dripping_honey",
        "falling_honey",
        "landing_honey",
        "falling_nectar",
        "soul_fire_flame",
        "ash",
        "crimson_spore",
        "warped_spore",
        "soul",
        "dripping_obsidian_tear",
        "falling_obsidian_tear",
        "landing_obsidian_tear",
        "reverse_portal",
        "white_ash",
        "dust_color_transition",
        "vibration",
        "falling_spore_blossom",
        "spore_blossom_air",
        "small_flame",
        "snowflake",
        "dripping_dripstone_lava",
        "falling_dripstone_lava",
        "dripping_dripstone_water",
        "falling_dripstone_water",
        "glow_squid_ink",
        "glow",
        "wax_on",
        "wax_off",
        "electric_spark",
        "scrape",
        "sonic_boom",
        "sculk_soul",
        "sculk_charge",
        "sculk_charge_pop",
        "shriek",
        "cherry_leaves",
        "pale_oak_leaves",
        "red_poplar_leaves",
        "orange_poplar_leaves",
        "yellow_poplar_leaves",
        "tinted_leaves",
        "egg_crack",
        "dust_plume",
        "white_smoke",
        "gust",
        "small_gust",
        "gust_emitter_large",
        "gust_emitter_small",
        "trial_spawner_detection",
        "trial_spawner_detection_ominous",
        "vault_connection",
        "infested",
        "item_cobweb",
        "dust_pillar",
        "block_crumble",
        "firefly",
        "trail",
        "ominous_spawning",
        "raid_omen",
        "trial_omen",
        "block_marker",
        "copper_fire_flame",
        "pause_mob_growth",
        "reset_mob_growth",
        "noxious_gas",
        "noxious_gas_cloud",
        "sulfur_cube_goo",
        "sulfur_bubbles",
        "geyser",
        "geyser_base",
        "geyser_plume",
        "geyser_poof",
        )

        val PARTICLE_ID = Regex("""particle_id:\s*([a-z0-9-]+)""")
        val TRAIL = Regex("""trail:\s*\[([^\]]*)]""")
    }
}
