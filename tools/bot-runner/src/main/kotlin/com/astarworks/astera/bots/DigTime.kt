package com.astarworks.astera.bots

import kotlin.math.ceil

/**
 * How long a client must hold a dig for the server to accept the break (vanilla `ServerPlayerGameMode`): per tick the block
 * loses `toolSpeed / hardness / 30` of its health (`/ 100` when the block needs a tool the hand lacks; wood needs none), the
 * break takes `ceil(1 / that)` ticks, and the server accepts a FINISH_DIGGING that arrives with at least 0.7 of that progress.
 * Assumes the bot stands on the ground, not in water, with no Haste/Efficiency (the Norma tools are unenchantable).
 * Pure and deterministic; [BotBrain] holds a dig for exactly [holdMs] of the tool in hand.
 */
object DigTime {
    const val TICK_MS = 50L

    /** Network jitter and the tick the server samples on: three extra ticks over the vanilla break time. */
    const val MARGIN_TICKS = 3

    /** Hardness of every [StarBlockScanner.PAYING] block (vanilla: barrel, chest and crafting table are all 2.5, axe-mineable wood). */
    const val STAR_BLOCK_HARDNESS = 2.5

    /** Vanilla mining speed of an axe on wood by tier; bare hand and every non-axe tool = 1. */
    fun axeSpeed(tier: String?): Double = when (tier) {
        "wooden" -> 2.0
        "stone" -> 4.0
        "iron" -> 6.0
        "diamond" -> 8.0
        "netherite" -> 9.0
        "golden" -> 12.0
        else -> 1.0
    }

    /** Tier word of a held item's display name (en and ja), null when it names none. */
    fun tierOfName(displayName: String): String? {
        val n = displayName.lowercase()
        return when {
            "netherite" in n || "ネザライト" in n -> "netherite"
            "diamond" in n || "ダイヤ" in n -> "diamond"
            "iron" in n || "鉄" in n -> "iron"
            "stone" in n || "石" in n -> "stone"
            "golden" in n || "gold" in n || "金" in n -> "golden"
            "wooden" in n || "wood" in n || "木" in n -> "wooden"
            else -> null
        }
    }

    /** Mining speed of the held item (display [heldName]) on a wooden star block: an axe's tier speed, anything else 1. */
    fun speedOfHeld(heldName: String): Double {
        val item = LoadoutClassifier.classify(heldName, "")
        return if (item.kind == LoadoutClassifier.Kind.TOOL && item.affinity == LoadoutClassifier.Affinity.AXE) axeSpeed(tierOfName(heldName)) else 1.0
    }

    /** Ticks vanilla needs to break a block of [hardness] with [toolSpeed] (no tool requirement: wood). */
    fun breakTicks(hardness: Double, toolSpeed: Double): Int {
        val perTick = toolSpeed / hardness / 30.0
        return if (perTick >= 1.0) 1 else ceil(1.0 / perTick - 1e-9).toInt()
    }

    /** Milliseconds to hold the dig on a star block with the item named [heldName]. */
    fun holdMs(heldName: String): Long =
        (breakTicks(STAR_BLOCK_HARDNESS, speedOfHeld(heldName)) + MARGIN_TICKS) * TICK_MS
}
