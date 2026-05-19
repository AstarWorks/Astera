package com.astarworks.astera.core.application.command

/**
 * Sealed catalogue of argument types a [CommandSpec.Argument] may declare.
 *
 * Each variant constrains what the platform's command parser will accept and
 * what the resolved value's runtime type will be when read out of
 * [CommandContext.args]. The runtime type is documented per-variant.
 *
 * Designed for the Astera CommandAPI replacement (silly-knuth §6.1 E):
 *
 * - **Vendor-neutral**: no Bukkit / Brigadier types leak into this layer.
 * - **Closed hierarchy**: adding a new argument type forces the adapter to
 *   handle it (sealed exhaustive `when`); no silent fallthroughs.
 * - **Domain-aware where it helps**: [ItemIdArg] / [PlayerSelector] resolve to
 *   identifiers and selectors the use cases already speak.
 *
 * The Phase 1 starter set is intentionally minimal. Add new variants as use
 * cases require them; do NOT pack extra knowledge into an existing variant.
 */
public sealed interface ArgumentType {

    /**
     * One online player. Resolves to a [com.astarworks.astera.core.domain.player.PlayerId].
     *
     * Phase 1 accepts a single name; vanilla `@p` / `@s` style selectors are an
     * adapter-level enhancement and may be implemented later without touching
     * this interface (the resolved type stays `PlayerId`).
     */
    public data object PlayerSelector : ArgumentType

    /**
     * 32-bit integer with optional inclusive bounds. Resolves to [Int].
     *
     * `min` / `max` are inclusive; pass `null` to leave a side unconstrained.
     */
    public data class IntegerArg(
        public val min: Int? = null,
        public val max: Int? = null,
    ) : ArgumentType {
        init {
            if (min != null && max != null) {
                require(min <= max) { "IntegerArg: min ($min) must be <= max ($max)" }
            }
        }
    }

    /**
     * Free-form string. Resolves to [String].
     *
     * - [Mode.SingleWord] consumes one whitespace-delimited token.
     * - [Mode.Greedy] consumes the rest of the input line, spaces included.
     *   Greedy strings, by definition, must be the final argument in a branch.
     */
    public data class StringArg(public val mode: Mode = Mode.SingleWord) : ArgumentType {
        public enum class Mode { SingleWord, Greedy }
    }

    /**
     * An Astera content item id (e.g. `example-sword`, `mythic:fireball-staff`).
     * Resolves to [String]; the use case re-parses to its strong id type
     * ([com.astarworks.astera.core.domain.item.ItemId], `SkillId`, ...).
     *
     * Keeping the resolved type as `String` here avoids a hard dependency from
     * the command DSL on every individual id value class, and means the
     * adapter does not need to know about each registry.
     */
    public data object ItemIdArg : ArgumentType
}
