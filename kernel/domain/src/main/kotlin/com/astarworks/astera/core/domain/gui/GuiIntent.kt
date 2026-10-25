package com.astarworks.astera.core.domain.gui

/**
 * A user-issued intent originating from a GUI interaction.
 *
 * ### Why a value, not a callback
 *
 * The GUI domain is **purely declarative**. A [GuiSlot.Static.onClick] cannot
 * be a `(GuiContext) -> Unit` lambda — that would smuggle behaviour into the
 * domain and prevent GUI specs from being serialised, shipped over the wire,
 * or rendered by a non-Minecraft adapter (Phase 4 web admin). Instead, click
 * targets emit a `GuiIntent` with a string [key] (the action name) and an
 * optional [payload] (e.g. `{"itemId": "iron-sword", "qty": "1"}`).
 *
 * The application-layer `GuiIntentDispatcher` (Stage 2 G) maps intents to use
 * cases — exactly the pattern Redux / Elm / Flux use. A click-on-this-slot
 * "purchase iron-sword × 1" intent is a *value* that can be tested, logged,
 * replayed, and inspected long before reaching any I/O.
 *
 * ### Payload contract
 *
 * Both [key] and [payload] values are plain `String` so the whole structure is
 * trivially serialisable without forcing the domain to depend on
 * `kotlinx.serialization` (forbidden in domain per ADR-0001 / Konsist rule).
 * Use cases parse payload entries into their own typed parameters.
 *
 * Examples:
 * ```
 * GuiIntent("shop.purchase", mapOf("itemId" to "iron-sword", "qty" to "1"))
 * GuiIntent("gui.close")
 * GuiIntent("loadout.equip", mapOf("slot" to "MAIN", "weaponId" to "blaster"))
 * ```
 */
public data class GuiIntent(
    public val key: String,
    public val payload: Map<String, String> = emptyMap(),
) {
    init {
        require(key.isNotBlank()) { "GuiIntent.key must not be blank" }
        require(VALID_KEY.matches(key)) {
            "GuiIntent.key must be dot-separated lower kebab/snake-case (a-z, 0-9, -, _, .): '$key'"
        }
    }

    public companion object {
        private val VALID_KEY = Regex("^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)*$")
    }
}
