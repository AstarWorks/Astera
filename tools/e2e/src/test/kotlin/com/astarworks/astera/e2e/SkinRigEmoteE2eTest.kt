package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0250: `/astera emote wave` swaps the player's body for a rig of Display entities and `/astera emote stop`
 * gives everything back. Two bots: the actor emotes, the watcher is the client that has to see the swap
 * (spawned item displays, the actor flagged invisible); the server side is read over RCON.
 */
class SkinRigEmoteE2eTest : E2eBase() {

    private val suite = "SkinRig"
    private val invisibleBit = 0x20

    private fun rigDisplays(actor: Bot): Int {
        val reply = rcon("execute as ${actor.name} at @s if entity @e[type=minecraft:item_display,distance=..3]").trim()
        return Regex("(?i)count: (\\d+)").find(reply)?.groupValues?.get(1)?.toInt() ?: if (reply.startsWith("Test passed")) 1 else 0
    }

    private fun speed(actor: Bot): Double =
        Regex("(-?[0-9.]+)\\s*$").find(rcon("attribute ${actor.name} minecraft:movement_speed get").trim())?.groupValues?.get(1)?.toDouble() ?: Double.NaN

    @Test
    fun `emote wave spawns a rig, hides the body, and stop restores everything`() {
        val actor = bot("EmoteA")
        val watcher = bot("EmoteW")
        rcon("execute in minecraft:overworld run tp ${actor.name} 0.5 -60 0.5 0 0")
        rcon("execute in minecraft:overworld run tp ${watcher.name} 0.5 -60 4.5 180 0")
        Thread.sleep(1_500)
        val actorId = checkNotNull(watcher.entityIdByUuid[Bot.offlineUuid(actor.name)]) { "watcher never saw ${actor.name}" }

        val displaysBefore = rigDisplays(actor)
        val speedBefore = speed(actor)
        val addedBefore = watcher.entityEvents.size

        actor.command("astera emote wave")
        // `wave` is a 30-tick non-loop emote (about 1.65 s with blend-out), so every "during" reading is taken in
        // one pass and only counts once the rig, the invisible flag and the speed lock are all seen together.
        var displaysDuring = displaysBefore
        var flagsDuring = 0
        var speedDuring = Double.NaN
        val spawned = actor.waitUntil(4_000) {
            displaysDuring = rigDisplays(actor)
            flagsDuring = watcher.entityFlags[actorId] ?: 0
            speedDuring = speed(actor)
            displaysDuring >= displaysBefore + 6 && (flagsDuring and invisibleBit) != 0 && speedDuring < speedBefore
        }

        E2eReport.check(suite, "display rig entities spawn near the emoting bot", "RCON @e[type=item_display,distance=..3]") {
            assertThat(spawned).describedAs("rig, invisible flag and speed lock never held together (displays $displaysBefore -> $displaysDuring, flags=0x${flagsDuring.toString(16)}, speed $speedBefore -> $speedDuring)").isTrue()
            "item_display near actor: $displaysBefore -> $displaysDuring"
        }
        E2eReport.check(suite, "watcher client receives the rig displays", "ClientboundAddEntity(ITEM_DISPLAY)") {
            val added = watcher.entityEvents.drop(addedBefore).count { it.added && it.type == "ITEM_DISPLAY" }
            assertThat(added).describedAs("item displays added at the watcher").isGreaterThanOrEqualTo(6)
            "ITEM_DISPLAY adds seen by watcher: $added"
        }
        E2eReport.check(suite, "emoting bot is invisible to the watcher", "ClientboundSetEntityData(flags 0x20)") {
            assertThat(flagsDuring and invisibleBit).describedAs("actor flags=0x${flagsDuring.toString(16)}").isNotZero()
            "actor flags=0x${flagsDuring.toString(16)}"
        }
        E2eReport.check(suite, "movement speed is locked during the emote", "RCON attribute movement_speed") {
            assertThat(speedDuring).describedAs("speed before=$speedBefore during=$speedDuring").isLessThan(speedBefore)
            "movement_speed $speedBefore -> $speedDuring"
        }

        // The emote may already have ended on its own (then `stop` just answers emote-not-playing); the end state
        // below is what counts either way.
        actor.command("astera emote stop")
        val gone = actor.waitUntil(5_000) { rigDisplays(actor) <= displaysBefore }
        Thread.sleep(500)

        E2eReport.check(suite, "stop removes the rig entities", "RCON @e[type=item_display,distance=..3]") {
            assertThat(gone).describedAs("rig displays still present: ${rigDisplays(actor)} (before=$displaysBefore)").isTrue()
            "item_display near actor after stop: ${rigDisplays(actor)} (before $displaysBefore)"
        }
        E2eReport.check(suite, "bot is visible again", "ClientboundSetEntityData(flags 0x20)") {
            val flags = watcher.entityFlags[actorId] ?: 0
            assertThat(flags and invisibleBit).describedAs("actor flags=0x${flags.toString(16)}").isZero()
            "actor flags=0x${flags.toString(16)}"
        }
        E2eReport.check(suite, "movement speed is restored", "RCON attribute movement_speed") {
            val after = speed(actor)
            assertThat(after).describedAs("speed before=$speedBefore after=$after").isEqualTo(speedBefore)
            "movement_speed after stop: $after"
        }
    }
}
