package com.astarworks.astera.e2e

/** A chat/sidebar line that is a raw translation key (`<astera.x.y>` from our flattener, or `[astera.x.y]`). */
private val RAW_KEY = Regex("[<\\[]astera\\.[a-z0-9_-]+(\\.[a-z0-9_-]+)+[>\\]]|^astera\\.[a-z0-9_-]+(\\.[a-z0-9_-]+)+$")

fun rawKeys(lines: Collection<String>): List<String> = lines.filter { RAW_KEY.containsMatchIn(it.trim()) }

/** Waits until [Bot] has at least [minNew] more chat lines than [before] and returns the new ones (or whatever arrived). */
fun Bot.chatSince(before: Int, minNew: Int = 1, timeoutMs: Long = 6_000): List<String> {
    waitUntil(timeoutMs) { chat.size - before >= minNew }
    Thread.sleep(300)
    return chat.drop(before).map { it.text }
}
