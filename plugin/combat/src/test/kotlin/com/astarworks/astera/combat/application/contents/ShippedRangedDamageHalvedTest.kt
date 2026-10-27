package com.astarworks.astera.combat.application.contents

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ranged-weapon balance pin against the real `content/skills/`: every ranged skill's damage literals are exactly
 * HALF of the b17f0d7e5 (origin/main before the halving) values, and a representative melee set is unchanged.
 *
 * Damage literals = every `base: { type: literal, value }` under a `components:` / `damage:` list, plus
 * `per_stack.amount` (sorted, so field order does not matter). Ranged DoT is halved through the status duration
 * (burn / bleed tick once per 20 ticks, poison too): the shared status defs also drive melee (ember-gauntlet) and
 * mobs (cave-spider), so the `content/status` files stay untouched.
 */
class ShippedRangedDamageHalvedTest {

    private val skillsDir: Path = locateContentRoot().resolve("skills")

    @Test
    fun `every ranged skill deals exactly half of its pre-halving damage`() {
        val wrong = RANGED_BEFORE.mapNotNull { (id, before) ->
            val actual = damageLiterals(id)
            val expected = before.map { it * 0.5 }.sorted()
            if (actual == expected) null else "$id: expected $expected (half of $before) but was $actual"
        }
        assertThat(wrong).`as`("ranged skills not at x0.5").isEmpty()
    }

    @Test
    fun `melee skills keep their damage`() {
        val wrong = MELEE_UNCHANGED.mapNotNull { (id, values) ->
            val actual = damageLiterals(id)
            if (actual == values) null else "$id: expected $values but was $actual"
        }
        assertThat(wrong).`as`("melee skills changed").isEmpty()
    }

    @Test
    fun `ranged damage over time is halved and melee damage over time is unchanged`() {
        val wrong = (RANGED_DOT + MELEE_DOT).mapNotNull { (id, durations) ->
            val actual = statusDurations(id)
            if (actual == durations) null else "$id: expected $durations but was $actual"
        }
        assertThat(wrong).`as`("DoT durations").isEmpty()
    }

    /**
     * Plain maps / lists / strings. The generated persona skills use YAML anchors, which kaml refuses; none of them
     * sits on a damage or status node, so the anchor (`&idN`) is dropped and each alias (`*idN`) becomes null.
     */
    private fun load(id: String): Any? {
        val text = Files.readString(skillsDir.resolve("$id.yaml")).replace(Regex(" &id\\d+"), "").replace(Regex("\\*id\\d+"), "null")
        return plain(Yaml.default.parseToYamlNode(text))
    }

    private fun plain(node: YamlNode): Any? = when (node) {
        is YamlMap -> node.entries.entries.associate { (k, v) -> k.content to plain(v) }
        is YamlList -> node.items.map(::plain)
        is YamlScalar -> node.content
        else -> null
    }

    private fun literal(base: Any?): Double? {
        val map = base as? Map<*, *> ?: return null
        return if (map["type"] == "literal") map["value"].toString().toDouble() else null
    }

    private fun damageLiterals(id: String): List<Double> {
        val out = mutableListOf<Double>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> for ((key, child) in node) {
                    if ((key == "components" || key == "damage") && child is List<*>) {
                        child.filterIsInstance<Map<*, *>>().mapNotNull { literal(it["base"]) }.forEach { out += it }
                    }
                    if (key == "per_stack" && child is Map<*, *>) child["amount"]?.let { out += it.toString().toDouble() }
                    walk(child)
                }
                is List<*> -> node.forEach(::walk)
            }
        }
        walk(load(id))
        return out.sorted()
    }

    private fun statusDurations(id: String): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    if (node["type"] == "apply_status") {
                        val ticks = literal(node["effect_duration_ticks"])?.toInt()
                        if (ticks != null) out += node["status_id"].toString() to ticks
                    }
                    node.values.forEach(::walk)
                }
                is List<*> -> node.forEach(::walk)
            }
        }
        walk(load(id))
        return out
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
        /** Ranged skill -> damage literals as shipped on b17f0d7e5, before the x0.5 pass. */
        val RANGED_BEFORE: Map<String, List<Double>> = mapOf(
        "chain-lightning" to listOf(36.0),
        "crimson-needle-projectile" to listOf(35.0),
        "ember-rod-burst" to listOf(45.0),
        "ember-rod-spark" to listOf(40.0),
        "fireball-cast" to listOf(55.0, 55.0),
        "flame-burst" to listOf(80.0),
        "frag-grenade-throw" to listOf(70.0, 70.0),
        "frost-nova" to listOf(50.0),
        "frost-rod-chill" to listOf(25.0),
        "frost-rod-sleet" to listOf(36.0),
        "frost-shard" to listOf(50.0),
        "glacier-spike" to listOf(30.0),
        "grapple-shot" to listOf(10.0),
        "hand-crossbow-bolt" to listOf(30.0),
        "hand-crossbow-venom" to listOf(20.0),
        "harpoon-throw" to listOf(36.0),
        "lightning-bolt" to listOf(50.0),
        "longbow-arrow" to listOf(48.0),
        "longbow-heavy-arrow" to listOf(80.0),
        "orrery-ego" to listOf(0.3, 112.5),
        "orrery-s1" to listOf(43.75, 56.25),
        "orrery-s2" to listOf(56.25, 75.0),
        "orrery-s3" to listOf(37.5, 37.5, 37.5, 93.75),
        "orrery-s3-repeat" to listOf(37.5, 37.5, 37.5, 93.75),
        "pistol-shot" to listOf(50.0),
        "repeater-barrage" to listOf(52.0),
        "repeater-bolt" to listOf(15.0),
        "revolver-fan" to listOf(42.0),
        "revolver-shot" to listOf(60.0),
        "rifle-shot" to listOf(50.0),
        "shock-wand-arc" to listOf(30.0),
        "shock-wand-zap" to listOf(32.0),
        "shortbow-arrow" to listOf(28.0),
        "shortbow-snare" to listOf(14.0),
        "shotgun-blast" to listOf(50.0),
        "sigil-bullet-projectile" to listOf(62.5),
        "sigil-rifle-ego" to listOf(0.1, 0.1, 75.0, 87.5),
        "sigil-rifle-s1" to listOf(56.25),
        "sigil-rifle-s3" to listOf(112.5),
        "simple-shoot" to listOf(50.0),
        "smg-burst" to listOf(11.0),
        "sniper-shot" to listOf(95.0),
        "sticky-bomb-throw" to listOf(60.0, 60.0),
        "thorn-bolt" to listOf(47.0),
        "throwing-knife" to listOf(28.0),
        )

        /** Melee skills (representative set) -> damage literals, unchanged by the ranged pass. */
        val MELEE_UNCHANGED: Map<String, List<Double>> = mapOf(
        "sword-slash" to listOf(40.0),
        "greatsword-cleave" to listOf(65.0),
        "dagger-stab" to listOf(25.0),
        "katana-slash" to listOf(26.0),
        "mace-smash" to listOf(60.0),
        "war-axe-chop" to listOf(55.0),
        "cleaver-chop" to listOf(62.0),
        "rapier-thrust" to listOf(24.0),
        "spear-thrust" to listOf(34.0),
        "twin-daggers-flurry" to listOf(18.0),
        "club-slam" to listOf(16.0),
        "mace-quake" to listOf(30.0),
        "bastion-greatsword-s1" to listOf(35.0, 45.0),
        "verdict-hammer-s1" to listOf(35.0, 50.0),
        "edict-s1" to listOf(40.0, 55.0),
        "gossamer-katana-s3" to listOf(100.0),
        "ember-gauntlet-ego" to listOf(15.0, 15.0, 20.0),
        "vendetta-rifle-s1" to listOf(35.0, 50.0),
        "vendetta-rifle-ego" to listOf(10.0, 10.0, 10.0, 10.0, 20.0),
        "couture-rapier-s2" to listOf(30.0, 50.0),
        "cleaver-rend" to listOf(24.0),
        "iron-blade-slash" to listOf(36.0),
        "halberd-sweep" to listOf(38.0),
        )

        /** Ranged DoT applications (status to duration ticks) after halving: burn / bleed 10 / 5 per 20 ticks, poison 2. */
        val RANGED_DOT: Map<String, List<Pair<String, Int>>> = mapOf(
            "ember-rod-spark" to listOf("burn" to 20),
            "ember-rod-burst" to listOf("burn" to 20),
            "flame-burst" to listOf("burn" to 20),
            "fireball-cast" to listOf("burn" to 20),
            "frag-grenade-throw" to listOf("bleed" to 20, "bleed" to 20),
            "thorn-bolt" to listOf("poison" to 80),
            "hand-crossbow-venom" to listOf("poison" to 60),
            "poison-cloud" to listOf("poison" to 80, "poison" to 80),
        )

        val MELEE_DOT: Map<String, List<Pair<String, Int>>> = mapOf(
            "ember-gauntlet-ego" to listOf("burn" to 20, "burn" to 20, "burn" to 20),
            "cleaver-chop" to listOf("bleed" to 60),
            "cleaver-rend" to listOf("bleed" to 100),
            "dagger-stab" to listOf("bleed" to 100),
            "twin-daggers-flurry" to listOf("bleed" to 80),
        )
    }
}
