package com.astarworks.astera.core.adapter.paper.packetevents

import com.github.retrooper.packetevents.PacketEvents
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import org.bukkit.plugin.java.JavaPlugin

/**
 * Thin lifecycle wrapper around the PacketEvents 2.x bootstrap sequence
 * (ADR-0170 Wave 6c-stub-packetevents).
 *
 * Isolated in its own class **so that the JVM does not load it unless the
 * caller has already confirmed PacketEvents is installed.** AsteraCorePlugin
 * gates entry with a plain `Bukkit.getPluginManager().getPlugin("packetevents")
 * != null` check (no PE class reference), and only invokes [load] / [init]
 * / [terminate] inside that gate. When PacketEvents is absent at runtime,
 * the JVM never touches the `com.github.retrooper.packetevents.*` symbols
 * in this file, avoiding `NoClassDefFoundError` per JLS §5.3.4 (lazy
 * resolution at first active use).
 *
 * The companion [isInstalled] helper performs the gate check without
 * touching any PacketEvents class itself — safe to call from anywhere.
 *
 * ## Sequence (per the official PacketEvents 2.x docs)
 *
 *  - `onLoad`  → [load]      (`setAPI(builder.build(plugin))` + `.load()`)
 *  - `onEnable` → [init]     (`PacketEvents.getAPI().init()`)
 *  - `onDisable` → [terminate] (`PacketEvents.getAPI().terminate()`)
 *
 * Calling [init] without a prior [load] throws; AsteraCorePlugin honours
 * the sequence by exposing both `onLoad` (added by Wave 6c-stub-packetevents
 * for this) and `onEnable`.
 */
public object PacketEventsBootstrap {

    private const val PLUGIN_NAME = "packetevents"

    /**
     * Runtime check for the PacketEvents Paper plugin. Uses only Bukkit
     * APIs — never references a PacketEvents class — so it is safe to call
     * from anywhere, including class-init paths.
     */
    public fun isInstalled(plugin: JavaPlugin): Boolean =
        plugin.server.pluginManager.getPlugin(PLUGIN_NAME) != null

    /** Phase 1 of the PE 2.x lifecycle; call from `JavaPlugin.onLoad`. */
    public fun load(plugin: JavaPlugin) {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin))
        PacketEvents.getAPI().load()
    }

    /** Phase 2 of the PE 2.x lifecycle; call from `JavaPlugin.onEnable`. */
    public fun init() {
        PacketEvents.getAPI().init()
    }

    /** Phase 3 of the PE 2.x lifecycle; call from `JavaPlugin.onDisable`. */
    public fun terminate() {
        PacketEvents.getAPI().terminate()
    }
}
