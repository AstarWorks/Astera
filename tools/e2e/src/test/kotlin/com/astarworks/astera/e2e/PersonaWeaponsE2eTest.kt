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
    private val triggers = listOf("left_click" to true, "right_click" to true, "sneak_right" to true, "sneak_left" to false)

    private val displayTypes = setOf("BLOCK_DISPLAY", "ITEM_DISPLAY", "TEXT_DISPLAY")
    private val vfxLog = Regex("vfx|display|dash|hit_stop|MechanicDispatcher", RegexOption.IGNORE_CASE)

    private data class Row(
        val weapon: String, val trigger: String, val spawned: Int, val maxConcurrent: Int, val removedOk: Boolean,
        val lifetimeMs: Long, val damage: String, val hitEvents: Int, val dead: Boolean,
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

        fun place() {
            rcon("tp ${a.name} $ax $ay $az 0 0")
            rcon("tp ${t.name} $ax $ay ${az + 3} 180 0")
            Thread.sleep(600)
        }

        for (w in weapons) {
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
                place()
                a.sneak(false)
                Thread.sleep(200)

                val evBefore = a.entityEvents.size
                val hpBefore = hp(t)
                val healthHistBefore = t.healthHistory.size
                val dmgPacketsBefore = t.packetCounts["ClientboundDamageEventPacket"]?.get() ?: 0
                val hurtBefore = t.packetCounts["ClientboundHurtAnimationPacket"]?.get() ?: 0
                val deathsBefore = t.deaths.size
                val castAt = System.currentTimeMillis()

                when (trigger) {
                    "left_click" -> { a.punch(); t.let { a.entityIdByUuid[it.uuid] }?.let(a::attack) }
                    "right_click" -> a.useItem(0f, 0f)
                    "sneak_right" -> { a.sneak(true); Thread.sleep(250); a.useItem(0f, 0f); Thread.sleep(150); a.sneak(false) }
                    "sneak_left" -> { a.sneak(true); Thread.sleep(250); a.punch(); a.entityIdByUuid[t.uuid]?.let(a::attack); Thread.sleep(150); a.sneak(false) }
                }
                // Timeline + removal window; stop early once displays have been seen and all are gone.
                val deadline = castAt + 12_000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(250)
                    if (System.currentTimeMillis() - castAt < 3_000) continue
                    val ev = a.entityEvents.drop(evBefore)
                    val spawned = ev.filter { it.added && it.type in displayTypes }.map { it.id }.toSet()
                    if (spawned.isNotEmpty() && spawned.all { id -> ev.any { !it.added && it.id == id } }) break
                    if (spawned.isEmpty() && System.currentTimeMillis() - castAt > 5_000) break
                }
                Thread.sleep(500)

                val ev = a.entityEvents.drop(evBefore)
                val spawnedAt = ev.filter { it.added && it.type in displayTypes }
                val spawnedIds = spawnedAt.map { it.id }.toSet()
                val removedAt = spawnedIds.associateWith { id -> ev.firstOrNull { !it.added && it.id == id }?.at }
                val leaked = removedAt.filterValues { it == null }.keys
                var live = 0
                var maxLive = 0
                for (e in ev) {
                    if (e.added && e.type in displayTypes) { live++; maxLive = maxOf(maxLive, live) }
                    else if (!e.added && e.id in spawnedIds) live--
                }
                val lifetime = spawnedAt.maxOfOrNull { s -> (removedAt[s.id] ?: System.currentTimeMillis()) - s.at } ?: 0L
                val typeCounts = spawnedAt.groupingBy { it.type }.eachCount()

                val hpAfter = hp(t)
                val damaged = (hpBefore != null && hpAfter != null && hpAfter < hpBefore) || t.healthHistory.drop(healthHistBefore).any { hpBefore != null && it < hpBefore }
                val hits = (t.packetCounts["ClientboundDamageEventPacket"]?.get() ?: 0) - dmgPacketsBefore
                val hurts = (t.packetCounts["ClientboundHurtAnimationPacket"]?.get() ?: 0) - hurtBefore
                val hitEvents = maxOf(hits, hurts)
                val died = t.deaths.size > deathsBefore || t.health == 0f

                rows += Row(w, trigger, spawnedIds.size, maxLive, leaked.isEmpty(), lifetime, "$hpBefore->$hpAfter", hitEvents, died)

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
                    "HP $hpBefore -> $hpAfter, damage-event packets=$hits hurt-animation=$hurts"
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

        val sb = StringBuilder("| Weapon | Binding | Displays spawned | Max concurrent | Removed ok | Longest life (ms) | Target HP | Hit events | Target died |\n|---|---|---|---|---|---|---|---|---|\n")
        rows.forEach { sb.append("| ${it.weapon} | ${it.trigger} | ${it.spawned} | ${it.maxConcurrent} | ${if (it.removedOk) "yes" else "**NO**"} | ${it.lifetimeMs} | ${it.damage} | ${it.hitEvents} | ${if (it.dead) "yes" else "no"} |\n") }
        E2eReport.tables["Persona weapons"] = sb.toString()
        println(sb)
    }
}
