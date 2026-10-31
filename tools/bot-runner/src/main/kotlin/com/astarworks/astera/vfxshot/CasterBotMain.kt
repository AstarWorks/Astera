package com.astarworks.astera.vfxshot

import com.astarworks.astera.e2e.Bot
import com.astarworks.astera.e2e.Endpoint
import com.astarworks.astera.e2e.PackPolicy
import kotlin.system.exitProcess

/**
 * Protocol-level stand-in for the second capture client of `tools/vfx-shot`: the player that stands in the arena and casts the
 * skill (the server casts it by RCON). It never renders anything (a real client costs about 2 GB resident under software GL,
 * this JVM about 150 MB). Usage: `CasterBotMainKt <name> <host> <port>`; it stays connected until the server drops it.
 */
fun main(args: Array<String>) {
    val name = args.getOrElse(0) { "Caster" }
    val host = args.getOrElse(1) { "127.0.0.1" }
    val port = args.getOrElse(2) { "25565" }.toInt()
    val bot = Bot.join(prefix = name, packPolicy = PackPolicy.LOAD, endpoint = Endpoint(host, port), exactName = name)
    if (!bot.joined) {
        System.err.println("[caster-bot] $name did not join: ${bot.disconnectReason}")
        exitProcess(2)
    }
    println("[caster-bot] $name joined $host:$port")
    while (bot.joined && bot.disconnectReason == null) Thread.sleep(500)
    println("[caster-bot] $name left: ${bot.disconnectReason}")
    bot.close()
    exitProcess(0)
}
