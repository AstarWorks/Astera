package com.astarworks.astera.core.domain.hud

/** Client-side outcome of a resource pack push, vendor-neutral (ADR-0182). */
public enum class ResourcePackStatus {
    /** The client accepted the prompt and is downloading. */
    ACCEPTED,

    /** The pack is applied: the Astera font and sprite overrides are live. */
    LOADED,

    /** The player said no. */
    DECLINED,

    /** Download / hash / parse failure, or the pack was discarded. */
    FAILED,
    ;

    public val isLoaded: Boolean get() = this == LOADED
}
