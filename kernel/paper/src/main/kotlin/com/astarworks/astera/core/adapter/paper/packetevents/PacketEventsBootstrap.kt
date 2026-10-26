package com.astarworks.astera.core.adapter.paper.packetevents

import com.github.retrooper.packetevents.PacketEvents
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import org.bukkit.plugin.java.JavaPlugin

/** Required bundled PacketEvents library lifecycle (ADR-0260). */
public object PacketEventsBootstrap {
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
