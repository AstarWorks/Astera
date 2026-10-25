package com.astarworks.astera.e2e.live

import com.astarworks.astera.e2e.Bot
import com.astarworks.astera.e2e.E2eReport
import com.astarworks.astera.e2e.visible
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `make live-bots SCENARIO=weapons`: every resource-using weapon in content/items, each binding cast at a target bot
 * inside a real `skirmish` match on the LIVE game backend (the lobby world cancels damage, and the game backend sends
 * anybody who is not in a match back to the lobby after 3 s, so a match is the only place where damage applies).
 * Both bots are never op. Nothing is announced; `clear` is only ever run on the two bot names.
 */
class WeaponsLiveTest : LiveBase() {

    private val suite = "Live weapons"
    private val srv = "game"

    /** One `skills:` entry of an item file. */
    data class Binding(val trigger: String, val skillId: String, val cooldownOverride: Int?)
    data class WeaponDef(val id: String, val resourceType: String, val itemCost: Int, val bindings: List<Binding>)

    private fun parseWeapons(): List<WeaponDef> =
        File(LiveEnv.contentDir, "items").listFiles { f -> f.extension == "yaml" }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            val lines = f.readLines()
            val id = lines.firstOrNull { it.startsWith("id:") }?.substringAfter(":")?.trim() ?: return@mapNotNull null
            val res = lines.firstOrNull { it.startsWith("resource_type:") }?.substringAfter(":")?.substringBefore("#")?.trim() ?: return@mapNotNull null
            val bindings = mutableListOf<Binding>()
            var inSkills = false
            var trigger: String? = null
            var skill: String? = null
            var cd: Int? = null
            fun flush() {
                if (trigger != null && skill != null) bindings += Binding(trigger!!, skill!!, cd)
                trigger = null; skill = null; cd = null
            }
            for (l in lines) {
                if (l.startsWith("skills:")) { inSkills = true; continue }
                if (inSkills && l.isNotBlank() && !l.startsWith(" ") && !l.startsWith("#")) { flush(); inSkills = false }
                if (!inSkills) continue
                val t = l.trim()
                if (t.startsWith("- trigger:")) { flush(); trigger = t.substringAfter(":").substringBefore("#").trim() }
                else if (t.startsWith("skill_id:")) skill = t.substringAfter(":").substringBefore("#").trim()
                else if (t.startsWith("cooldown_override_ticks:")) cd = t.substringAfter(":").substringBefore("#").trim().toIntOrNull()
            }
            flush()
            val itemCost = lines.firstOrNull { it.startsWith("resource_cost_per_use:") }?.substringAfter(":")?.substringBefore("#")?.trim()?.toIntOrNull() ?: 0
            if (bindings.isEmpty()) null else WeaponDef(id, res, itemCost, bindings)
        }

    private fun skillText(id: String): String = File(LiveEnv.contentDir, "skills/$id.yaml").takeIf { it.exists() }?.readText().orEmpty()
    private fun expectsDamage(skillId: String) = Regex("(?m)^\\s*-?\\s*type:\\s*damage\\b").containsMatchIn(skillText(skillId))

    /** Resource spent by one cast: the skill's own `resource_cost` when it has one, else the item's default. */
    private fun cost(skillId: String, w: WeaponDef): Int =
        Regex("(?m)^resource_cost:\\s*(\\d+)").find(skillText(skillId))?.groupValues?.get(1)?.toInt() ?: w.itemCost

    private fun where(b: Bot): String =
        rcon(srv, "data get entity ${b.name} Pos").substringAfter("data: ").trim() + " / " + rcon(srv, "data get entity ${b.name} Rotation").substringAfter("data: ").trim() +
            " ground=" + rcon(srv, "data get entity ${b.name} OnGround").substringAfter("data: ").trim()

    private fun hp(target: Bot): Double? =
        Regex("(-?[0-9.]+)f?$").find(rcon(srv, "data get entity ${target.name} Health").trim())?.groupValues?.get(1)?.toDoubleOrNull()

    private fun counts(b: Bot): Map<String, Int> = b.packetCounts.mapValues { it.value.get() }
    private fun delta(before: Map<String, Int>, b: Bot, vararg kinds: String): Int =
        kinds.sumOf { (b.packetCounts[it]?.get() ?: 0) - (before[it] ?: 0) }

    @Test
    fun `every weapon binding fires on the live game backend`() {
        val only = System.getProperty("astera.live.weapons")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val weapons = parseWeapons().filter { only == null || it.id in only }
        E2eReport.check(suite, "content/items has resource weapons to test", "content") {
            assertThat(weapons).describedAs("no weapons parsed from ${LiveEnv.contentDir}/items").isNotEmpty()
            weapons.joinToString("\n") { w -> "${w.id} (${w.resourceType}): " + w.bindings.joinToString { "${it.trigger}->${it.skillId}" } } +
                "\n(rtm-blaster / simple-shoot: ${if (weapons.any { it.id == "rtm-blaster" || it.id == "simple-shoot" }) "present" else "not present in content/items"})"
        }

        val mark = LogMark(srv)
        val lobbyMark = LogMark("lobby")
        val busy = matchLines().filter { matchIdOf(it) != null }
        E2eReport.check(suite, "game backend has no other running match (precondition)", "RCON astera match list") {
            assertThat(busy).describedAs("other matches are running; refusing to interfere:\n${quote(busy)}").isEmpty()
            "none"
        }
        if (busy.isNotEmpty()) return
        val shooter = bot("shoot")
        var target = bot("dummy")
        val matchId = enterNormaMatch(suite, shooter, target, mark, lobbyMark)
        if (matchId == null) {
            // No match could be started (the checks above carry the reason). Fall back to the bare game backend, where
            // damage still applies, so the weapons themselves are still verified; the strays-go-home bounce is
            // re-countered below by hopping the bots back whenever the server sends them away.
            E2eReport.notes += "weapons: no ACTIVE match; testing on the bare game backend instead (outside a match)"
            listOf(shooter, target).forEach { if (!(it.joined && serverOf(it) == "game")) it.forceHop("game") }
            listOf(shooter, target).forEach { p -> p.waitUntil(30_000) { p.joined && serverOf(p) == "game" } }
        }
        Thread.sleep(3_000)
        rcon(srv, "gamemode survival ${shooter.name}")
        rcon(srv, "gamemode survival ${target.name}")

        // Inside the example-arena region (-50..50), on the flat world's surface (y=-60).
        val ax = if (matchId != null) 0.5 else 6000.5
        val ay = -60.0
        val az = if (matchId != null) -20.5 else 6000.5
        fun place(distance: Double) {
            listOf(shooter, target).forEach { b ->
                if (!(b.joined && serverOf(b) == "game")) {
                    E2eReport.notes += "${b.name} was sent away from the game backend; hopping back"
                    b.forceHop("game")
                    b.waitUntil(30_000) { b.joined && serverOf(b) == "game" }
                    Thread.sleep(2_500)
                    rcon(srv, "gamemode survival ${b.name}")
                }
            }
            rcon(srv, "tp ${shooter.name} $ax $ay $az 0 0")
            rcon(srv, "tp ${target.name} $ax $ay ${az + distance} 180 0")
            Thread.sleep(700)
        }
        place(4.0)
        E2eReport.check(suite, "both bots are in the match on the game backend", "ClientboundLogin world / position") {
            assertThat(shooter.joined && target.joined).describedAs("joined: shooter=${shooter.joined}(${shooter.disconnectReason}) target=${target.joined}(${target.disconnectReason})").isTrue()
            "shooter world=${shooter.worldName} pos=(${shooter.x.toInt()},${shooter.y.toInt()},${shooter.z.toInt()}); target world=${target.worldName} pos=(${target.x.toInt()},${target.y.toInt()},${target.z.toInt()}); gamemodes ${shooter.gameModes}/${target.gameModes}"
        }

        for (w in weapons) {
            if (matchId == null && w !== weapons.first()) {
                // Astera keeps its own HP (Attributes.CURRENT_HP, vanilla health is only a mirror). A target that was
                // driven to Astera-HP 0 rejects all further damage and a vanilla heal does not revive it, so every
                // weapon gets a fresh dummy.
                drop(target)
                target = bot("dummy", LiveEnv.game)
                target.waitUntil(20_000) { target.joined }
                Thread.sleep(3_000)
                rcon(srv, "gamemode survival ${target.name}")
            }
            rcon(srv, "clear ${shooter.name}")
            val slotsBefore = shooter.slotUpdates.size
            val give = rcon(srv, "astera items give ${shooter.name} ${w.id}")
            shooter.waitUntil(5_000) { shooter.slotUpdates.drop(slotsBefore).any { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 } }
            val slot = shooter.slotUpdates.drop(slotsBefore).lastOrNull { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 }?.slot?.minus(36)
            E2eReport.check(suite, "${w.id}: item lands in a hotbar slot", "SetContainerSlot") {
                assertThat(slot).describedAs("no hotbar slot update after give; reply='${give.trim()}'; updates=${shooter.slotUpdates.drop(slotsBefore)}").isNotNull()
                "hotbar index $slot; give reply='${give.trim().take(100)}'"
            }
            if (slot == null) continue
            // A real player swaps to the weapon (held-item change drives the resource HUD), so go away and back.
            shooter.selectHotbar((slot + 1) % 9)
            Thread.sleep(300)
            shooter.selectHotbar(slot)
            Thread.sleep(700)

            for (bd in w.bindings) {
                val label = "${w.id} / ${bd.trigger} (${bd.skillId})"
                listOf(shooter, target).forEach { b ->
                    if (b.health == 0f) { b.respawn(); Thread.sleep(800) }
                    rcon(srv, "effect give ${b.name} minecraft:instant_health 1 4 true")
                }
                val deathsBefore = shooter.deaths.size to target.deaths.size
                place(if (w.id.contains("sword") || w.id == "dagger" || w.id == "greatsword") 2.2 else 4.0)
                Thread.sleep(600)
                val hpBefore = hp(target)
                val hhBefore = target.healthHistory.size
                val sBefore = counts(shooter)
                val tBefore = counts(target)
                val foodBefore = shooter.food
                val foodHistBefore = shooter.foodHistory.size
                val abLast = shooter.actionBars.lastOrNull()?.text
                val chatBefore = shooter.chat.size
                val abBefore = shooter.actionBars.size

                fun castOnce() {
                    when (bd.trigger) {
                        "right_click" -> shooter.useItem(0f, 0f)
                        "left_click" -> shooter.punch()
                        "sneak_right" -> { shooter.sneak(true); Thread.sleep(250); shooter.useItem(0f, 0f); Thread.sleep(150); shooter.sneak(false) }
                        "drop" -> shooter.dropSelected(false)
                        "sneak_drop" -> { shooter.sneak(true); Thread.sleep(250); shooter.dropSelected(false); Thread.sleep(150); shooter.sneak(false) }
                        else -> E2eReport.notes += "$label: trigger ${bd.trigger} not driven by the harness"
                    }
                }
                castOnce()
                Thread.sleep(1_800)
                if (shooter.deaths.size > deathsBefore.first || target.deaths.size > deathsBefore.second) {
                    E2eReport.notes += "$label: DEATH during the cast - shooter ${shooter.deaths.drop(deathsBefore.first)}, target ${target.deaths.drop(deathsBefore.second)}"
                }
                fun pollHp(): Double? {
                    var v = hp(target)
                    var tries = 0
                    while (hpBefore != null && v != null && v >= hpBefore && tries++ < 4) { Thread.sleep(500); v = hp(target) }
                    return v
                }
                var hpAfter = pollHp()
                // A projectile may legitimately miss a bot that stands still on an unloaded chunk edge etc.: allow two
                // more casts before calling it a failure, and report how many it took.
                var attempts = 1
                while (expectsDamage(bd.skillId) && attempts < 3 && !(hpBefore != null && hpAfter != null && hpAfter < hpBefore)) {
                    attempts++
                    E2eReport.notes += "$label: no HP drop yet; retrying (attempt $attempts)"
                    listOf(shooter, target).forEach { b -> rcon(srv, "effect give ${b.name} minecraft:instant_health 1 4 true") }
                    place(if (w.id.contains("sword") || w.id == "dagger" || w.id == "greatsword") 2.2 else 4.0)
                    Thread.sleep(2_000)
                    castOnce()
                    Thread.sleep(1_800)
                    hpAfter = pollHp()
                }
                val flightAndFx = delta(sBefore, shooter, "ClientboundLevelParticlesPacket", "ClientboundAddEntityPacket", "ClientboundSoundPacket") +
                    delta(tBefore, target, "ClientboundLevelParticlesPacket", "ClientboundAddEntityPacket", "ClientboundSoundPacket")

                E2eReport.check(suite, "$label: cast is audible/visible (particle, entity or sound packets arrive)", "ClientboundLevelParticles / AddEntity / Sound") {
                    assertThat(flightAndFx).describedAs(
                        "no particle/entity/sound packet after the cast. shooter packet delta=${diff(sBefore, shooter)}; target delta=${diff(tBefore, target)}; " +
                            "shooter chat=${shooter.chat.drop(chatBefore).map { it.text }}; actionbar=${shooter.actionBars.drop(abBefore).map { visible(it.text) }.takeLast(3)}",
                    ).isGreaterThan(0)
                    "shooter delta=${diff(sBefore, shooter)}\ntarget delta=${diff(tBefore, target)}"
                }
                if (expectsDamage(bd.skillId)) {
                    E2eReport.check(suite, "$label: target HP drops", "SetHealth / server Health") {
                        val dropped = hpBefore != null && hpAfter != null && hpAfter < hpBefore
                        assertThat(dropped).describedAs(
                            "target HP unchanged: before=$hpBefore after=$hpAfter (SetHealth since cast=${target.healthHistory.drop(hhBefore)}); gamemodes ${shooter.gameModes}; shooter at ${where(shooter)}; target at ${where(target)}; target SetHealth history tail=${target.healthHistory.takeLast(5)}; " +
                                "target DamageEvent/Hurt packets=${delta(tBefore, target, "ClientboundDamageEventPacket", "ClientboundHurtAnimationPacket")}; shooter chat=${shooter.chat.drop(chatBefore).map { it.text }}",
                        ).isTrue()
                        "HP $hpBefore -> $hpAfter after $attempts cast(s); target damage/hurt packets=${delta(tBefore, target, "ClientboundDamageEventPacket", "ClientboundHurtAnimationPacket")}"
                    }
                }
                if (cost(bd.skillId, w) > 0) E2eReport.check(suite, "$label: energy/ammo readout (${w.resourceType}, cost ${cost(bd.skillId, w)}) reacts, no boss bar", "SetHealth(food) / action bar (ADR-0194)") {
                    assertThat(shooter.bossBars).describedAs("a boss bar is shown (ADR-0194 removed them); events=${shooter.bossBarEvents.takeLast(5)}").isEmpty()
                    if (w.resourceType == "ammo") {
                        val abAfter = shooter.actionBars.drop(abBefore).map { visible(it.text) }.distinct()
                        val last = shooter.actionBars.lastOrNull()?.text
                        assertThat(last != abLast).describedAs("status line (ammo) unchanged by the cast: before='${visible(abLast.orEmpty())}' after distinct lines=$abAfter").isTrue()
                        "status line before='${visible(abLast.orEmpty())}' after='${visible(last.orEmpty())}'"
                    } else {
                        val seen = shooter.foodHistory.drop(foodHistBefore)
                        assertThat(seen.any { it < foodBefore }).describedAs("food bar never dropped below $foodBefore after the cast (server foodLevel=${rcon(srv, "data get entity ${shooter.name} foodLevel").substringAfter("data: ").trim()}, shooter at ${where(shooter)}); food packets since=$seen; actionbar lines=${shooter.actionBars.drop(abBefore).map { visible(it.text) }.distinct()}").isTrue()
                        "food bar $foodBefore -> ${seen.minOrNull()} (history since cast=$seen)"
                    }
                }
                // cooldown evidence: an immediate re-cast should be throttled (no new projectile entity / damage).
                val sMid = counts(shooter)
                val hpMid = hp(target)
                when (bd.trigger) {
                    "right_click" -> shooter.useItem(0f, 0f)
                    "left_click" -> shooter.punch()
                    else -> Unit
                }
                Thread.sleep(250)
                E2eReport.notes += "$label: immediate re-cast 250ms later -> shooter delta=${diff(sMid, shooter)}; target HP $hpMid -> ${hp(target)}; override=${bd.cooldownOverride}"
                Thread.sleep(1_500)
                shooter.sneak(false)
            }
        }

        if (matchId != null) runCatching { rcon(srv, "astera match end $matchId") }
        E2eReport.notes += "shooter worlds=${shooter.worldHistory} deaths=${shooter.deaths} gameModes=${shooter.gameModes} healthHistory(first 12)=${shooter.healthHistory.take(12)} chat=${shooter.chatTexts().takeLast(12)}"
        E2eReport.notes += "target worlds=${target.worldHistory} deaths=${target.deaths} healthHistory(first 12)=${target.healthHistory.take(12)} chat=${target.chatTexts().takeLast(12)}"
        E2eReport.check(suite, "game server log has no errors/exceptions during the scenario", "server log") {
            val errs = mark.errors()
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean (${mark.lines().size} log lines)"
        }
    }

    private fun diff(before: Map<String, Int>, b: Bot): String =
        b.packetCounts.mapNotNull { (k, v) -> (v.get() - (before[k] ?: 0)).takeIf { it > 0 && !k.contains("Move") && !k.contains("Rotate") }?.let { "${k.removePrefix("Clientbound").removeSuffix("Packet")}+$it" } }
            .sorted().joinToString(" ")
}
