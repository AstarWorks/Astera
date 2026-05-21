package com.astarworks.astera.core.domain.mob

/**
 * Stable identifier for a [MobSpec] registered with `IMobRegistry`
 * (Wave 5pve, ADR-0085).
 *
 * Naming rule: namespaced lower-kebab-case (`astera:forest-wolf`,
 * `mythic:skeletal-knight`). Validated at construction so YAML typos surface
 * immediately — same regex shape family as
 * [com.astarworks.astera.core.domain.item.ItemId] but with a mandatory
 * namespace segment (mob ids cross plugin boundaries more often than item
 * ids, so the namespace prefix is structural rather than optional).
 *
 * # Namespaces
 *
 * - `astera:` — first-party content under `content/mobs/`.
 * - `mythic:` — MythicMobs-bridged definitions (Wave 6b Paper adapter
 *   translates a MythicMobs config → [MobSpec] on registration).
 * - `<custom>:` — operator-defined add-on packs.
 *
 * The namespace itself is opaque to the registry — anything matching
 * `[a-z][a-z0-9_]*` is accepted. The `IMobAdapter` selector keys off the
 * adapter's own `backendId()`, not the [MobTypeId] prefix, so namespaces are
 * purely a content-organization aid.
 */
@JvmInline
public value class MobTypeId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "MobTypeId must be namespaced lower-kebab-case (e.g., astera:forest-wolf): '$value'"
        }
    }

    /** Namespace segment (before the colon). */
    public val namespace: String get() = value.substringBefore(':')

    /** Path segment (after the colon). */
    public val path: String get() = value.substringAfter(':')

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9_]*:[a-z][a-z0-9-]*$")
    }
}
