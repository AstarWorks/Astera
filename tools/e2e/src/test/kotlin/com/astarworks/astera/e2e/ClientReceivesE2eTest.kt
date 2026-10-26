package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * "What the player actually receives": the packets a client gets for the sounds and the HP line, not the server
 * state behind them. Two regressions slipped past every state assertion and were found only by a human:
 *  - `astera:*` skill sounds were silently dropped server-side (no packet at all);
 *  - the HP line vanished after a hot reload, and combat HP updated up to a second late.
 *
 * Not coverable here: how the HP glyph bar and the sounds actually look / sound in a real client (needs a real-client
 * screenshot or capture); the bots only decode the glyph code points and record the sound ids.
 */
class ClientReceivesE2eTest : E2eBase() {

    private val suite = "ClientReceives"

    /** 3 ticks (150 ms) from the vanilla SetHealth to the HP line carrying the same hit, plus 50 ms of poll / network jitter. */
    private val hudLagBudgetMs = 200L

    private fun give(a: Bot, item: String): Int? = giveOnce(a, item) ?: giveOnce(a, item)

    private fun giveOnce(a: Bot, item: String): Int? {
        rcon("clear ${a.name}")
        Thread.sleep(300)
        val before = a.slotUpdates.size
        a.command("astera items give ${a.name} $item")
        a.waitUntil(4_000) { a.slotUpdates.size > before }
        Thread.sleep(300)
        val slot = a.slotUpdates.drop(before).lastOrNull { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 }?.slot?.minus(36)
        if (slot != null) {
            a.selectHotbar(slot)
            Thread.sleep(400)
        }
        return slot
    }

    private fun hp(b: Bot): Double? =
        Regex("(-?[0-9.]+)f?$").find(rcon("data get entity ${b.name} Health").trim())?.groupValues?.get(1)?.toDoubleOrNull()

    private fun inOverworld(b: Bot): Boolean = "overworld" in rcon("data get entity ${b.name} Dimension")

    /** Both bots in the overworld (damage is cancelled in the lobby), [distance] apart, `a` looking at `b`, at full HP. */
    private fun stage(a: Bot, b: Bot, distance: Double = 4.0) {
        repeat(6) {
            rcon("execute in minecraft:overworld run tp ${a.name} 0.5 -60 0.5 0 0")
            rcon("execute in minecraft:overworld run tp ${b.name} 0.5 -60 ${0.5 + distance} 180 0")
            Thread.sleep(1_500)
            rcon("effect give ${a.name} minecraft:instant_health 1 4 true")
            rcon("effect give ${b.name} minecraft:instant_health 1 4 true")
            Thread.sleep(800)
            if (inOverworld(a) && inOverworld(b) && (hp(a) ?: 0.0) >= 19.9 && (hp(b) ?: 0.0) >= 19.9) return
        }
        E2eReport.notes += "ClientReceives stage gave up: hp a=${hp(a)} b=${hp(b)}"
    }

    private fun heardAfter(b: Bot, from: Int, expected: String, timeoutMs: Long = 3_000): Boolean {
        val want = soundKey(expected)
        return b.waitUntil(timeoutMs) { b.sounds.drop(from).any { want in soundKey(it) } }
    }

    @Test
    fun `joining the lobby starts the astera lobby BGM track as a sound packet`() {
        val a = bot("BgmListener")
        a.waitUntil(15_000) { a.sounds.any { soundKey("astera:music/lobby-loop") in soundKey(it) } }
        E2eReport.check(suite, "joining the lobby sends the astera:music/lobby-loop BGM event to the player", "ClientboundSound") {
            assertThat(a.sounds.map(::soundKey).any { soundKey("astera:music/lobby-loop") in it })
                .describedAs("MISSING astera:music/lobby-loop after joining; sound packets=${a.sounds.take(10)}").isTrue()
            "heard ${a.sounds.take(6)}"
        }
    }

    @Test
    fun `basic gun sword and wand skills send their astera sound packets to the caster`() {
        val a = bot("SoundCaster")
        op(a)
        rcon("gamemode survival ${a.name}")
        a.waitUntil(8_000) { !a.health.isNaN() }
        Thread.sleep(500)

        // item -> (how a client casts it, the astera sound id its skill's cast phase plays to the caster)
        val cases = listOf(
            Triple("pistol", "right", "astera:weapons/gun-shot"),
            Triple("knight-sword", "left", "astera:weapons/sword-swing"),
            Triple("fire-staff", "right", "astera:skill/wand/cast"),
        )
        for ((item, click, soundId) in cases) {
            val slot = give(a, item)
            val soundsBefore = a.sounds.size
            if (slot != null) {
                if (click == "left") a.punch() else a.useItem(0f, 0f)
                heardAfter(a, soundsBefore, soundId)
            }
            E2eReport.check(suite, "$item cast sends $soundId to the caster", "ClientboundSound") {
                assertThat(slot).describedAs("$item never reached a hotbar slot").isNotNull()
                val heard = a.sounds.drop(soundsBefore)
                assertThat(heard.map(::soundKey).any { soundKey(soundId) in it })
                    .describedAs("MISSING sound id $soundId after casting $item ($click click); sound packets received=${heard.take(10)}")
                    .isTrue()
                "heard $soundId; all=${heard.take(6)}"
            }
            Thread.sleep(1_200) // let the cooldown / energy settle before the next weapon
        }
    }

    private class Lag(val hudBefore: HpReading?, val vanillaMs: Long?, val hudMs: Long?, val hudAfter: HpReading?)

    /** `a` fires the pistol at `b`; times (ms after the shot) when `b` first receives the lowered vanilla health and the lowered HP line. */
    private fun shootAndTime(a: Bot, b: Bot): Lag {
        repeat(4) {
            val vanillaBefore = b.health
            val hudBefore = b.hpReadings().lastOrNull() ?: return Lag(null, null, null, null)
            val bars = b.actionBars.size
            val hist = b.healthHistory.size
            val t0 = System.nanoTime()
            a.useItem(0f, 0f)
            var vanillaMs: Long? = null
            var hudMs: Long? = null
            var hudAfter: HpReading? = null
            var settleUntil = Long.MAX_VALUE
            val end = t0 + 4_000_000_000L
            while (System.nanoTime() < end && System.nanoTime() < settleUntil) {
                val now = (System.nanoTime() - t0) / 1_000_000
                if (vanillaMs == null && b.healthHistory.drop(hist).any { it < vanillaBefore }) vanillaMs = now
                if (hudMs == null) {
                    b.hpReadings(bars).firstOrNull { it.current < hudBefore.current }?.let { hudMs = now; hudAfter = it }
                }
                if ((vanillaMs != null || hudMs != null) && settleUntil == Long.MAX_VALUE) settleUntil = System.nanoTime() + 1_000_000_000L
                if (vanillaMs != null && hudMs != null) break
                Thread.sleep(2)
            }
            if (vanillaMs != null || hudMs != null) return Lag(hudBefore, vanillaMs, hudMs, hudAfter)
            Thread.sleep(800)
        }
        return Lag(b.hpReadings().lastOrNull(), null, null, null)
    }

    private fun checkHitLag(a: Bot, b: Bot, label: String) {
        val soundsBefore = a.sounds.size
        val lag = shootAndTime(a, b)
        E2eReport.check(suite, "$label: the HP line the target receives drops within 3 ticks of the hit", "SetActionBarText HP glyphs vs ClientboundSetHealth") {
            assertThat(lag.hudBefore).describedAs("target never received a parsable HP line; bars=${b.actionBars.takeLast(6).map { visible(it.text) }}").isNotNull()
            assertThat(lag.hudMs).describedAs("HP line never dropped after the hit (before=${lag.hudBefore}, vanilla SetHealth at ${lag.vanillaMs} ms); bars=${b.actionBars.takeLast(6).map { visible(it.text) }}").isNotNull()
            assertThat(lag.vanillaMs).describedAs("target never received a lowered SetHealth (hearts); HP line dropped at ${lag.hudMs} ms").isNotNull()
            val behind = lag.hudMs!! - lag.vanillaMs!!
            assertThat(behind).describedAs("HP line is $behind ms behind the hearts (budget $hudLagBudgetMs ms = 3 ticks + jitter); vanilla ${lag.vanillaMs} ms, line ${lag.hudMs} ms").isLessThanOrEqualTo(hudLagBudgetMs)
            "hearts at ${lag.vanillaMs} ms, line at ${lag.hudMs} ms ($behind ms behind); ${lag.hudBefore} -> ${lag.hudAfter}; glyph bar=${lag.hudAfter?.glyphs}"
        }
        E2eReport.check(suite, "$label: the shooter hears exactly one hit marker (no vanilla double)", "ClientboundSound") {
            val heard = a.sounds.drop(soundsBefore)
            assertThat(heard.map(::soundKey).none { soundKey("entity.arrow.hit_player") in it })
                .describedAs("vanilla entity.arrow.hit_player still plays on a hit (double marker); heard=${heard.take(10)}").isTrue()
            "heard ${heard.take(6)}"
        }
        E2eReport.check(suite, "$label: the shooter hears Astera's own hit-marker cue (astera:combat/hit, not a vanilla stand-in)", "ClientboundSound") {
            val heard = a.sounds.drop(soundsBefore)
            assertThat(heard.map(::soundKey).any { soundKey("astera:combat/hit") in it || soundKey("astera:combat/crit") in it })
                .describedAs("MISSING astera:combat/hit (or crit) for the shooter after a hit; heard=${heard.take(10)}").isTrue()
            "heard ${heard.take(6)}"
        }
    }

    @Test
    fun `hp line follows a hit within three ticks and shows full max after death and respawn`() {
        val a = bot("HpShooter")
        val b = bot("HpTarget")
        op(a)
        op(b)
        rcon("gamemode survival ${a.name}")
        rcon("gamemode survival ${b.name}")
        b.waitUntil(10_000) { b.hpReadings().isNotEmpty() }
        val slot = give(a, "pistol")
        stage(a, b)

        E2eReport.check(suite, "the target receives an HP line on join", "SetActionBarText") {
            val r = b.hpReadings().lastOrNull()
            assertThat(r).describedAs("no HP line among action bars=${b.actionBars.takeLast(8).map { visible(it.text) }}").isNotNull()
            assertThat(slot).describedAs("pistol never reached a hotbar slot").isNotNull()
            "$r"
        }
        checkHitLag(a, b, "first hit")

        // Death + respawn: the line must come back at full max, not stay at the dead value or vanish.
        rcon("effect give ${b.name} minecraft:instant_health 1 4 true")
        Thread.sleep(500)
        val barsAtKill = b.actionBars.size
        rcon("kill ${b.name}")
        Thread.sleep(1_000)
        repeat(5) {
            b.respawn()
            Thread.sleep(500)
        }
        b.waitUntil(10_000) { b.hpReadings(barsAtKill).any { it.current == it.max } }
        Thread.sleep(500)
        E2eReport.check(suite, "after death and respawn the HP line shows full max", "SetActionBarText HP glyphs") {
            val after = b.hpReadings(barsAtKill)
            assertThat(after).describedAs("no HP line at all after respawn; bars=${b.actionBars.drop(barsAtKill).map { visible(it.text) }.takeLast(8)}").isNotEmpty()
            val last = after.last()
            assertThat(last.current).describedAs("HP line after respawn is $last, not full; readings=$after").isEqualTo(last.max)
            assertThat(last.max).isGreaterThan(0)
            "readings after respawn: ${after.takeLast(4)}; vanilla health=${b.health}"
        }
    }

    @Test
    fun `hp line and resource pack come back after an in-place reload while a bot is connected`() {
        // ADR-0186 rejects unloading / re-enabling a plugin (second classloader generation), so the dev loop's in-place
        // paths are the reload that exists: `reload all` (content) and `pack reload force` (live pack re-push).
        val a = bot("ReloadShooter")
        val b = bot("ReloadTarget")
        op(a)
        op(b)
        rcon("gamemode survival ${a.name}")
        rcon("gamemode survival ${b.name}")
        b.waitUntil(10_000) { b.hpReadings().isNotEmpty() && b.packPushes.isNotEmpty() }
        give(a, "pistol")
        stage(a, b)

        val pushesBefore = b.packPushes.size
        val statusesBefore = b.packStatusesSent.size
        val bars = b.actionBars.size
        val reload = rcon("astera admin reload all")
        val pack = rcon("astera admin pack reload force")
        b.waitUntil(10_000) { b.packPushes.size > pushesBefore }
        b.waitUntil(10_000) { b.hpReadings(bars).isNotEmpty() }
        Thread.sleep(1_000)

        E2eReport.check(suite, "after a reload the client is pushed the pack again and answers LOADED", "ClientboundResourcePackPush") {
            assertThat(b.packPushes.size).describedAs("no pack push after `pack reload force` (reply='${pack.trim()}', reload reply='${reload.trim()}')").isGreaterThan(pushesBefore)
            assertThat(b.packStatusesSent.size).describedAs("bot never answered the re-push").isGreaterThan(statusesBefore)
            "pushes $pushesBefore -> ${b.packPushes.size}; reply='${pack.trim()}'"
        }
        E2eReport.check(suite, "after a reload the HP line is still delivered, as the glyph bar", "SetActionBarText HP glyphs") {
            val after = b.hpReadings(bars)
            assertThat(after).describedAs("HP line vanished after reload; bars=${b.actionBars.drop(bars).map { visible(it.text) }.takeLast(8)}; reload='${reload.trim()}'").isNotEmpty()
            assertThat(after.last().glyphs).describedAs("pack LOADED state not restored: HP line is the text fallback ${after.last()}").isTrue()
            "after reload: ${after.takeLast(3)}"
        }
        stage(a, b)
        checkHitLag(a, b, "after reload")

        // `reload all` once emptied the sound registry (reload read only the root of content/sounds/); the item, its skill
        // and the astera:* cast cue must all still resolve afterwards.
        Thread.sleep(1_200)
        stage(a, b)
        val soundsBefore = a.sounds.size
        a.useItem(0f, 0f)
        heardAfter(a, soundsBefore, "astera:weapons/gun-shot")
        E2eReport.check(suite, "after a reload the pistol (item + skill) still casts and plays its astera:* sound cue", "ClientboundSound") {
            val heard = a.sounds.drop(soundsBefore)
            assertThat(heard.map(::soundKey).any { soundKey("astera:weapons/gun-shot") in it })
                .describedAs("MISSING astera:weapons/gun-shot after reload; sound packets=${heard.take(10)}; reload='${reload.trim()}'").isTrue()
            "heard gun-shot after reload; all=${heard.take(6)}"
        }
    }
}
