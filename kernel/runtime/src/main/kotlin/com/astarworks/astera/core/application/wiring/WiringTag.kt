package com.astarworks.astera.core.application.wiring

/** Who made a registration: the plugin.yml name (`Astera-Combat`), the same string `CommandMount` uses (ADR-0311). */
@JvmInline
public value class WiringOwner(public val id: String) {
    override fun toString(): String = id
}

/**
 * Owner plus generation of one wiring run (ADR-0311). Every registration (service, event subscription, GUI intent,
 * reloadable category, scheduler task, Bukkit listener, command) is recorded under the tag of the
 * [com.astarworks.astera.core.platform.wiring.WiringContext] that made it, so one generation can be torn down
 * without touching the others.
 */
public data class WiringTag(public val owner: WiringOwner, public val gen: Int) {
    override fun toString(): String = "$owner#$gen"

    public companion object {
        /** Objects created before any wiring and never rewired (service registry, bus, scheduler, ...). */
        public val SUBSTRATE: WiringTag = WiringTag(WiringOwner("substrate"), 0)

        /** A call that bypassed the funnel (tests, tooling). The Konsist `RewireOwnershipTest` keeps main code off it. */
        public val UNTAGGED: WiringTag = WiringTag(WiringOwner("untagged"), 0)
    }
}
