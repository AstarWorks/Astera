package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Match weapon restriction (skirmish.yaml `weapon_limits`: 1 per family, 2 in total): a player who queues with
 * five weapons enters the match with at most two; the rest come back when the match ends.
 */
class WeaponLimitE2eTest : E2eBase() {

    private val suite = "WeaponLimit"

    private val weaponIds = setOf(
        "pistol", "rifle", "shotgun", "vendetta-rifle", "sigil-rifle", "rtm-blaster",
        "knight-sword", "greatsword", "bastion-greatsword", "dagger", "edict-blade", "couture-rapier",
        "gossamer-katana", "crimson-chain-axe", "verdict-hammer", "ember-gauntlet",
        "fire-staff", "frost-staff", "thunder-staff", "verdant-staff", "orrery-staff",
    )

    /**
     * Astera weapon ids in the bot's inventory, read slot by slot from the `item_model` component (a whole-inventory
     * `data get` is cut off by the RCON payload limit).
     */
    private fun carried(b: Bot): List<String> = Rcon(E2eEnv.host, E2eEnv.rconPort, E2eEnv.rconPassword).use { r ->
        (0..40).mapNotNull { slot ->
            val reply = r.command("data get entity ${b.name} Inventory[{Slot:${slot}b}].components.\"minecraft:item_model\"")
            Regex("\"astera:([a-z0-9-]+)\"").find(reply)?.groupValues?.get(1)
        }.filter { it in weaponIds }
    }

    @Test
    fun `a match trims the carried weapons to the cap and the end restores them`() {
        val b = bot("Limiter")
        op(b)
        b.waitUntil(5_000) { b.sidebarLines().isNotEmpty() }
        for (item in listOf("pistol", "rifle", "shotgun", "greatsword", "fire-staff")) {
            b.command("astera items give ${b.name} $item")
            Thread.sleep(250)
        }
        Thread.sleep(500)
        val before = carried(b)

        val partner = bot("Limiter2")
        op(partner)
        partner.command("astera queue join skirmish")
        val chatBefore = b.chat.size
        val worldsBefore = b.worldHistory.size
        val posBefore = b.positionPackets.get()
        b.command("astera queue join skirmish")
        val teleported = b.waitUntil(45_000) { b.positionPackets.get() > posBefore || b.worldHistory.size > worldsBefore }
        Thread.sleep(2_500)
        val during = carried(b)
        val notices = b.chatSince(chatBefore, minNew = 1, timeoutMs = 2_000)

        E2eReport.check(suite, "match entry trims weapons to the cap (<= 2)", "Inventory (server-side) + SystemChat") {
            assertThat(teleported).describedAs("no teleport into the match").isTrue()
            assertThat(before.size).describedAs("setup: expected more than 2 weapons before the match, had $before").isGreaterThan(2)
            assertThat(during.size).describedAs("weapons carried in the match: $during (before: $before)").isLessThanOrEqualTo(2)
            assertThat(notices.any { "Weapon limit" in it || "武器制限" in it })
                .describedAs("no weapon-limit notice in chat: $notices").isTrue()
            "before=$before during=$during\nchat:\n${quote(notices)}"
        }

        endActiveMatches()
        b.waitUntil(30_000) { b.hotbar()[8] != -1 }
        Thread.sleep(1_500)
        val after = carried(b)
        E2eReport.check(suite, "match end restores the set-aside weapons", "Inventory (server-side)") {
            assertThat(after.size).describedAs("weapons after the match: $after (before: $before)").isGreaterThanOrEqualTo(before.size)
            "after=$after"
        }
    }
}
