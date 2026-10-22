package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0221 persona weapons: every weapon x {left_click, right_click, sneak_right, sneak_left} is driven by a
 * protocol bot. Per cast we check the attacker's client saw display-entity spawns and their removal, that the
 * target lost health (offensive skills), and that the server log stayed free of vfx/display/dash/hit_stop errors.
 * Results land in the report table "Persona weapons".
 */
class PersonaWeaponsE2eTest : E2eBase() {

    private val suite = "PersonaWeapons"

    private val weapons = listOf(
        "couture-rapier", "gossamer-katana", "vendetta-rifle", "ember-gauntlet", "bastion-greatsword",
        "verdict-hammer", "crimson-chain-axe", "sigil-rifle", "orrery-staff", "edict-blade",
    )

    /** trigger -> offensive (expects target damage). The sneak_left binding is the defensive one. */
    private val onlyWeapons = System.getenv("E2E_WEAPONS")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    /** Weapons whose skills use narrow cones / arcs and need the crosshair on the target. */
    private val aimedWeapons = setOf("vendetta-rifle", "sigil-rifle", "orrery-staff")
    /** Narrow cones are tested at the sim's 8 blocks, where eye-height error costs little angle. */
    private fun aimPitch(sneaking: Boolean, dist: Double): Float =
        Math.toDegrees(Math.atan2((if (sneaking) 1.27 else 1.62) - 0.9, dist)).toFloat()

    /** Hit counts the game-sim persona scenarios assert (s1, s2, s3), as inclusive ranges. Report-only: a mismatch is noted, not failed. */
    private val simHits = mapOf(
        "couture-rapier" to listOf(3..3, 5..5, 6..6), "gossamer-katana" to listOf(2..2, 5..5, 6..6),
        "vendetta-rifle" to listOf(3..3, 9..9, 8..11), "ember-gauntlet" to listOf(2..2, 3..3, 6..6),
        "bastion-greatsword" to listOf(2..2, 2..2, 2..2), "verdict-hammer" to listOf(2..2, 2..2, 2..2),
        "crimson-chain-axe" to listOf(3..3, 3..3, 2..2), "sigil-rifle" to listOf(1..1, 2..2, 2..2),
        "orrery-staff" to listOf(2..2, 4..4, 7..8), "edict-blade" to listOf(3..3, 3..3, 6..6),
    )

    private val triggers = listOf("left_click" to true, "right_click" to true, "sneak_right" to true, "sneak_left" to false)

    private val displayTypes = setOf("BLOCK_DISPLAY", "ITEM_DISPLAY", "TEXT_DISPLAY")
    private val vfxLog = Regex("vfx|display|dash|hit_stop|MechanicDispatcher", RegexOption.IGNORE_CASE)

    private data class Row(
        val weapon: String, val trigger: String, val spawned: Int, val maxConcurrent: Int, val removedOk: Boolean,
        val peakMix: String, val lifetimeMs: Long, val damage: String, val hitEvents: Int, val healthDrops: Int, val dead: Boolean, val simExpected: String,
    )

    private fun hp(b: Bot): Double? =
        Regex("(-?[0-9.]+)f?$").find(rcon("data get entity ${b.name} Health").trim())?.groupValues?.get(1)?.toDoubleOrNull()

    @Test
    fun `persona weapons spawn and clean up displays and deal damage`() {
        val serverLogStart = ServerLog.read().lines().size
        val a = bot("Attkr")
        var t = bot("Tgt")
        a.waitUntil(8_000) { !a.health.isNaN() }
        op(a)
        Thread.sleep(800)
        rcon("gamemode survival ${a.name}")
        rcon("gamemode survival ${t.name}")
        val ax = a.x
        val ay = a.y
        val az = a.z
        val rows = mutableListOf<Row>()

        // Cone / narrow-arc skills (rifles, staff) test the target's body centre against the caster's eye:
        // a player aims at the target, so tilt the pitch down by atan(eye - centre / distance).
        // Standing eye 1.62, sneaking 1.27, target centre 0.9. NOTE: ServerboundUseItem carries the client's
        // rotation and the server applies it, so the use-item packet must carry the same pitch.
        fun place(pitch: Float = 0f, dist: Double = 3.0) {
            // A target killed by the previous cast respawns at the world spawn a few seconds later and that
            // respawn teleport overrides ours: re-place until it really stands at the intended spot.
            repeat(8) { attempt ->
                rcon("tp ${a.name} $ax $ay $az 0 $pitch")
                rcon("tp ${t.name} $ax $ay ${az + dist} 180 0")
                Thread.sleep(if (attempt == 0) 600 else 1_500)
                val z = Regex("\\[([-0-9.]+)d, ([-0-9.]+)d, ([-0-9.]+)d\\]").find(rcon("data get entity ${t.name} Pos"))?.groupValues?.get(3)?.toDoubleOrNull()
                if (z != null && Math.abs(z - (az + dist)) < 0.3) return
            }
            E2eReport.notes += "place(): target never reached its mark (az+dist=${az + dist})"
        }

        for (w in weapons.filter { onlyWeapons == null || it in onlyWeapons }) {
            rcon("clear ${a.name}")
            val slotsBefore = a.slotUpdates.size
            val give = rcon("astera items give ${a.name} $w")
            a.waitUntil(5_000) { a.slotUpdates.drop(slotsBefore).any { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 } }
            val slot = a.slotUpdates.drop(slotsBefore).lastOrNull { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 }?.slot?.minus(36)
            E2eReport.check(suite, "$w: item lands in a hotbar slot", "SetContainerSlot") {
                assertThat(slot).describedAs("no hotbar slot after give; reply='${give.trim()}'").isNotNull()
                "hotbar $slot"
            }
            if (slot == null) continue
            a.selectHotbar((slot + 1) % 9)
            Thread.sleep(300)
            a.selectHotbar(slot)
            Thread.sleep(700)

            for ((trigger, offensive) in triggers) {
                val label = "$w / $trigger"
                // A target at Astera-HP 0 rejects damage and vanilla heal does not revive it: swap in a fresh one.
                if (t.health == 0f || !t.joined) {
                    runCatching { rcon("kick ${t.name}") }
                    t = bot("Tgt")
                    t.waitUntil(15_000) { t.joined && !t.health.isNaN() }
                    Thread.sleep(1_000)
                    rcon("gamemode survival ${t.name}")
                }
                rcon("effect give ${t.name} minecraft:instant_health 1 4 true")
                rcon("effect give ${a.name} minecraft:instant_health 1 4 true")
                val aimed = w in aimedWeapons
                val dist = 3.0
                val pitch = if (aimed) aimPitch(trigger.startsWith("sneak"), dist) else 0f
                place(pitch, dist)
                a.sneak(false)
                Thread.sleep(200)

                val rotAtCast = rcon("data get entity ${a.name} Rotation").substringAfter("data: ").trim()
                val evBefore = a.entityEvents.size
                val hpBefore = hp(t)
                val healthHistBefore = t.healthHistory.size
                val dmgPacketsBefore = t.packetCounts["ClientboundDamageEventPacket"]?.get() ?: 0
                val hurtBefore = t.packetCounts["ClientboundHurtAnimationPacket"]?.get() ?: 0
                val deathsBefore = t.deaths.size
                val targetChatBefore = t.chat.size
                val selfDmgBefore = t.damageEventEntities.size
                val numbersBefore = a.damageNumbers.size
                val castAt = System.currentTimeMillis()

                when (trigger) {
                    "left_click" -> { a.punch(); t.let { a.entityIdByUuid[it.uuid] }?.let(a::attack) }
                    "right_click" -> a.useItem(0f, pitch)
                    "sneak_right" -> { a.sneak(true); Thread.sleep(250); a.useItem(0f, pitch); Thread.sleep(150); a.sneak(false) }
                    "sneak_left" -> { a.sneak(true); Thread.sleep(250); a.punch(); a.entityIdByUuid[t.uuid]?.let(a::attack); Thread.sleep(150); a.sneak(false) }
                }
                // Timeline + removal window; stop early once displays have been seen and all are gone.
                val trace = mutableListOf<String>()
                val deadline = castAt + 12_000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(250)
                    if (offensive && trace.size < 60) trace += "${System.currentTimeMillis() - castAt}ms hp=${hp(t)} aPos=${rcon("data get entity ${a.name} Pos").substringAfter("data: ").trim()} tPos=${rcon("data get entity ${t.name} Pos").substringAfter("data: ").trim()}"
                    if (System.currentTimeMillis() - castAt < 6_000) continue
                    val ev = a.entityEvents.drop(evBefore)
                    val spawned = ev.filter { it.added && it.type in displayTypes }.map { it.id }.toSet()
                    if (spawned.isNotEmpty() && spawned.all { id -> ev.any { !it.added && it.id == id } }) break
                    if (spawned.isEmpty() && System.currentTimeMillis() - castAt > 7_000) break
                }
                Thread.sleep(500)

                val ev = a.entityEvents.drop(evBefore)
                val spawnedAt = ev.filter { it.added && it.type in displayTypes }
                val spawnedIds = spawnedAt.map { it.id }.toSet()
                val removedAt = spawnedIds.associateWith { id -> ev.firstOrNull { !it.added && it.id == id }?.at }
                val leaked = removedAt.filterValues { it == null }.keys
                val liveNow = linkedMapOf<Int, String>()
                var maxLive = 0
                var peakMix = emptyMap<String, Int>()
                for (e in ev) {
                    if (e.added && e.type in displayTypes) {
                        liveNow[e.id] = e.type
                        if (liveNow.size > maxLive) { maxLive = liveNow.size; peakMix = liveNow.values.groupingBy { it }.eachCount() }
                    } else if (!e.added) liveNow.remove(e.id)
                }
                val lifetime = spawnedAt.maxOfOrNull { s -> (removedAt[s.id] ?: System.currentTimeMillis()) - s.at } ?: 0L
                val typeCounts = spawnedAt.groupingBy { it.type }.eachCount()

                val hpAfter = hp(t)
                // A lethal hit outside a match sends the victim to the lobby at full HP before any poll sees the dip;
                // the "You were defeated" notice is the evidence of that hit.
                val defeated = t.chatTexts().drop(targetChatBefore).any { it.contains("defeated", ignoreCase = true) }
                val damaged = defeated || (hpBefore != null && hpAfter != null && hpAfter < hpBefore) || t.healthHistory.drop(healthHistBefore).any { hpBefore != null && it < hpBefore }
                val hits = (t.packetCounts["ClientboundDamageEventPacket"]?.get() ?: 0) - dmgPacketsBefore
                val hurts = (t.packetCounts["ClientboundHurtAnimationPacket"]?.get() ?: 0) - hurtBefore
                // Reliable hit count: damage-event packets whose victim is the target itself (the server sends one per
                // damage instance); health-decrease count from SetHealth is kept as a cross-check.
                val series = listOf((hpBefore ?: 20.0).toFloat()) + t.healthHistory.drop(healthHistBefore)
                val healthDrops = series.zipWithNext().count { (x, y) -> y < x }
                // The floating damage numbers (one text display per landed hit) are the exact per-hit count: SetHealth
                // merges hits in one tick and saturates at 1 HP, so it undercounts. Numbers are broadcast to the attacker.
                val numbers = a.damageNumbers.drop(numbersBefore)
                val hitEvents = numbers.size
                val died = t.deaths.size > deathsBefore || t.health == 0f || (hpAfter ?: 20.0) <= 1.0

                if (offensive && !damaged) E2eReport.notes += "$label NO DAMAGE: trace=${trace.takeLast(12)} rotationAtCast=$rotAtCast chat=${a.chatTexts().takeLast(4)} actionbars=${a.actionBars.takeLast(2).map { visible(it.text) }} food=${a.food} target ${rcon("data get entity ${t.name} Pos").substringAfter("data: ").trim()} attacker ${rcon("data get entity ${a.name} Pos").substringAfter("data: ").trim()} attackerFoodLevel=${rcon("data get entity ${a.name} foodLevel").substringAfter("data: ").trim()}"
                val simRange = simHits[w]?.getOrNull(triggers.indexOfFirst { it.first == trigger })
                val simExp = simRange?.let { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" } ?: "-"
                if (simRange != null && offensive && hitEvents !in simRange) {
                    E2eReport.notes += "$label HIT MISMATCH: e2e=$hitEvents (SetHealth drops $healthDrops, numbers $numbers) sim=$simExp; target path=${trace.map { it.substringBefore(" aPos").plus(it.substringAfter("tPos=")) }.distinct().take(14)}"
                }
                rows += Row(w, trigger, spawnedIds.size, maxLive, leaked.isEmpty(), peakMix.entries.joinToString(" ") { "${it.key.removeSuffix("_DISPLAY").lowercase()}=${it.value}" }, lifetime, "$hpBefore->$hpAfter", hitEvents, healthDrops, died, simExp)

                E2eReport.check(suite, "$label: display entities spawn", "ClientboundAddEntity (block/item/text display)") {
                    assertThat(spawnedIds).describedAs("no display entity reached the attacker; add-packets=${ev.filter { it.added }.groupingBy { it.type }.eachCount()}; actionbar=${a.actionBars.takeLast(3).map { visible(it.text) }}").isNotEmpty()
                    "spawned ${spawnedIds.size} $typeCounts, max concurrent $maxLive"
                }
                E2eReport.check(suite, "$label: all display entities removed", "ClientboundRemoveEntities") {
                    assertThat(leaked).describedAs("display entities never removed within 12s of cast: $leaked (lifetime so far ${lifetime}ms)").isEmpty()
                    "all ${spawnedIds.size} removed, longest lifetime ${lifetime}ms"
                }
                if (offensive) E2eReport.check(suite, "$label: target health decreases", "SetHealth / server Health") {
                    assertThat(damaged).describedAs("target HP $hpBefore -> $hpAfter; SetHealth since=${t.healthHistory.drop(healthHistBefore)}; damage packets=$hits hurt=$hurts; attacker actionbar=${a.actionBars.takeLast(3).map { visible(it.text) }}; chat=${a.chatTexts().takeLast(3)}").isTrue()
                    "HP $hpBefore -> $hpAfter, hits (damage numbers)=$hitEvents $numbers, SetHealth drops=$healthDrops, health series=$series"
                }
                Thread.sleep(500)
            }
        }

        E2eReport.check(suite, "server log: no vfx/display/dash/hit_stop/MechanicDispatcher errors", "server log") {
            val lines = ServerLog.read().lines().drop(serverLogStart)
            val bad = lines.indices.filter { i -> lines[i].let { "ERROR" in it || "Exception" in it } }
                .filter { i -> (maxOf(0, i - 2)..minOf(lines.lastIndex, i + 6)).any { vfxLog.containsMatchIn(lines[it]) } }
                .map { lines[it] }
            assertThat(bad).describedAs("server errors:\n${quote(bad, 20)}").isEmpty()
            "clean (${lines.size} log lines scanned)"
        }

        val sb = StringBuilder("| Weapon | Binding | Displays spawned | Max concurrent (mix) | Removed ok | Longest life (ms) | Target HP | Hits e2e (damage numbers / SetHealth drops) | Hits sim | Target died |\n|---|---|---|---|---|---|---|---|---|\n")
        rows.forEach { sb.append("| ${it.weapon} | ${it.trigger} | ${it.spawned} | ${it.maxConcurrent} (${it.peakMix}) | ${if (it.removedOk) "yes" else "**NO**"} | ${it.lifetimeMs} | ${it.damage} | ${it.hitEvents} / ${it.healthDrops} | ${it.simExpected} | ${if (it.dead) "yes" else "no"} |\n") }
        E2eReport.tables["Persona weapons"] = sb.toString()
        println(sb)
    }
}
