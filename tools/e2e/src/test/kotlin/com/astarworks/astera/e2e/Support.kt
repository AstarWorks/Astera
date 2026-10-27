package com.astarworks.astera.e2e

/** A chat/sidebar line that is a raw translation key (`<astera.x.y>` from our flattener, or `[astera.x.y]`). */
private val RAW_KEY = Regex("[<\\[]astera\\.[a-z0-9_-]+(\\.[a-z0-9_-]+)+[>\\]]|^astera\\.[a-z0-9_-]+(\\.[a-z0-9_-]+)+$")

/** The word "star(s)" (not "start", "starting"): how the star line / currency shows up in sidebars and chat. */
val STAR_WORD = Regex("(?<![\\w-])stars?(?![\\w-])", RegexOption.IGNORE_CASE)

/** The top Norma level in the e2e world: the e2e tune script pins `norma_max_level` to 3 (the shipped layer has 5). */
const val E2E_NORMA_MAX_LEVEL = 3

/**
 * A chat line that grants or counts stars. The Norma explainers (the how-to-win briefing, the result screen's "To win:" hint,
 * the time-limit rule and the goal reply) legitimately say "stars": they teach the in-match mechanic and grant nothing.
 */
fun grantsStars(line: String): Boolean =
    // "Rising Star" is the Lv 3 name-colour cosmetic that the reward preview names ("Next level reward: Lv 3 - 100 GOLD, Rising Star").
    // The first-match beginner guide (briefing.first-time.stars) teaches the in-match star item; it is not a grant.
    STAR_WORD.containsMatchIn(line.replace("Rising Star", "")) && listOf("Norma:", "To win:", "Time limit", "Norma goal:", "Pick up stars from generators").none { line.trimStart().startsWith(it) }

fun rawKeys(lines: Collection<String>): List<String> = lines.filter { RAW_KEY.containsMatchIn(it.trim()) }

/** Waits until [Bot] has at least [minNew] more chat lines than [before] and returns the new ones (or whatever arrived). */
fun Bot.chatSince(before: Int, minNew: Int = 1, timeoutMs: Long = 6_000): List<String> {
    waitUntil(timeoutMs) { chat.size - before >= minNew }
    Thread.sleep(300)
    return chat.drop(before).map { it.text }
}
