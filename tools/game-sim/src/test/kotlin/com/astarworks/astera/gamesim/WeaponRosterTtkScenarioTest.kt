package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.CriticalHit
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * WP7 balance sanity: for every shipped weapon, spam its primary input against a stationary
 * 20-HP enemy player and measure the time to kill (first input -> `PlayerKilled`).
 *
 * The attacker presses the primary input every tick (cooldown / resource rejections are part of
 * the weapon's real cadence) and presses the drop key to reload when an ammo weapon runs dry.
 * Status chances (bleed) and crits come from the sim's seeded random source; each weapon is run
 * [TRIALS] times on different seeds and the slowest and fastest kill must both sit inside the
 * band. The seeds are crit-free (a crit, 10 percent x2 in CombatWiring, only shortens a kill;
 * shotgun / greatsword can one-shot on a crit, by design).
 *
 * Players have 20-tick invulnerability frames (PaperDamageGateway), so a direct hit lands at
 * most once a second regardless of fire rate; DoTs (burn, bleed, poison) bypass them.
 *
 * Reference run, seconds:
 *
 * | weapon        | input       | skill             | dist | TTK (s)          |
 * |---------------|-------------|-------------------|------|------------------|
 * | pistol        | right click | pistol-shot       | 8    | 2.55             |
 * | rifle         | right click | rifle-shot        | 12   | 2.95             |
 * | shotgun       | right click | shotgun-blast     | 4    | 1.35             |
 * | knight-sword  | left click  | sword-slash       | 2.2  | 2.05             |
 * | greatsword    | left click  | greatsword-cleave | 2.2  | 1.45             |
 * | dagger        | left click  | dagger-stab       | 2.0  | 2.45 - 3.00      |
 * | frost-staff   | right click | frost-shard       | 8    | 4.70             |
 * | fire-staff    | right click | flame-burst       | 8    | 4.00             |
 * | thunder-staff | right click | lightning-bolt    | 8    | 3.65             |
 * | verdant-staff | right click | thorn-bolt        | 8    | 3.75             |
 *
 * The measured numbers are printed per weapon (`TTK <weapon>: ...`) and recorded in the
 * "Weapon roster" section of `docs/contributing/playtest.md`.
 */
class WeaponRosterTtkScenarioTest {

    private enum class Input { LEFT, RIGHT }

    private data class Weapon(val item: String, val input: Input, val distance: Double, val usesAmmo: Boolean = false)

    private val roster = listOf(
        Weapon("pistol", Input.RIGHT, 8.0, usesAmmo = true),
        Weapon("rifle", Input.RIGHT, 12.0, usesAmmo = true),
        Weapon("shotgun", Input.RIGHT, 4.0, usesAmmo = true),
        Weapon("knight-sword", Input.LEFT, 2.2),
        Weapon("greatsword", Input.LEFT, 2.2),
        Weapon("dagger", Input.LEFT, 2.0),
        Weapon("frost-staff", Input.RIGHT, 8.0),
        Weapon("fire-staff", Input.RIGHT, 8.0),
        Weapon("thunder-staff", Input.RIGHT, 8.0),
        Weapon("verdant-staff", Input.RIGHT, 8.0),
    )

    /**
     * Ticks from the first input to the victim's death, or null if it survived [LIMIT_TICKS].
     * Each trial runs on its own fixed seed ([GameSim.DEFAULT_SEED] + [SEED_OFFSETS]), so the run is
     * reproducible; the seeds are chosen so no trial rolls a critical hit (asserted in [runOnce]),
     * which keeps the table the crit-free baseline.
     */
    private fun timeToKill(weapon: Weapon, trial: Int): Int? = runOnce(weapon, trial)

    private fun runOnce(weapon: Weapon, trial: Int): Int? {
        var result: Int? = null
        scenario("roster-ttk-${weapon.item}-$trial", seed = GameSim.DEFAULT_SEED + SEED_OFFSETS[trial - 1]) {
            val attacker = player("attacker", team = "red")
            val victim = player("victim", at = Vec3(0.0, groundY, weapon.distance), team = "blue")
            attacker.holds(weapon.item)
            attacker.faces(victim)
            val start = sim.tick
            for (i in 0 until LIMIT_TICKS) {
                if (eventsOf<PlayerKilled>().isNotEmpty()) break
                if (weapon.usesAmmo && ammo(attacker) == 0) {
                    attacker.drop() // reload; the cast is refused while it runs, so keep pressing
                } else when (weapon.input) {
                    Input.LEFT -> attacker.leftClick()
                    Input.RIGHT -> attacker.rightClick()
                }
                advance(1)
            }
            val kill = eventsOf<PlayerKilled>().firstOrNull()
            if (kill != null) {
                assertThat(kill.victim).isEqualTo(victim.id)
                // A DoT (burn / bleed / poison) death has no killer; a direct kill must credit the attacker.
                kill.killer?.let { assertThat(it).isEqualTo(attacker.id) }
                result = (sim.tick - start).toInt()
            }
            assertThat(eventsOf<CriticalHit>()).`as`("${weapon.item} trial $trial rolled a crit; use another seed").isEmpty()
        }
        return result
    }

    @Test
    fun `every shipped weapon kills a 20 HP player within the target time band`() {
        val failures = mutableListOf<String>()
        for (weapon in roster) {
            val ttks = (1..TRIALS).map { timeToKill(weapon, it) }
            val seconds = ttks.map { it?.let { t -> t / 20.0 } }
            println("TTK ${weapon.item}: ${seconds.joinToString { it?.let { s -> "%.2fs".format(s) } ?: "no-kill" }}")
            val min = seconds.filterNotNull().minOrNull()
            val max = if (seconds.any { it == null }) Double.POSITIVE_INFINITY else seconds.filterNotNull().maxOrNull()
            if (min == null || min < MIN_SECONDS || max == null || max > MAX_SECONDS) {
                failures += "${weapon.item}: $seconds outside [$MIN_SECONDS, $MAX_SECONDS]s"
            }
        }
        assertThat(failures).`as`("weapons outside the TTK band").isEmpty()
    }

    private companion object {
        /** Seeds (offsets from the default) for which no weapon rolls a crit in any trial. */
        val SEED_OFFSETS = listOf(9, 12, 18, 27, 32)
        val TRIALS = SEED_OFFSETS.size
        const val LIMIT_TICKS = 20 * 12
        const val MIN_SECONDS = 1.0
        const val MAX_SECONDS = 6.0
    }
}
