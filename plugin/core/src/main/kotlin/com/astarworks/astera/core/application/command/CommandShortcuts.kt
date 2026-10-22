package com.astarworks.astera.core.application.command

/**
 * Short top-level aliases for the most common player actions (ADR-0232).
 *
 * Each shortcut is a Brigadier *redirect* to a node of the single `/astera`
 * tree, never a second spec: `/play norma` runs exactly `/astera queue join norma`
 * (same permission, completion, executor). This table is the only place the
 * mapping lives; the Paper bridge and the headless runner both read it.
 */
public object CommandShortcuts {

    /** shortcut name -> path below `/astera` of the node it redirects to. */
    public val TABLE: Map<String, List<String>> = linkedMapOf(
        "play" to listOf("queue", "join"),
        "menu" to listOf("menu"),
        "vault" to listOf("vault"),
        "shop" to listOf("shop"),
        "lang" to listOf("lang"),
    )

    /** Rewrite a leading shortcut token of [tokens] into its full path; other lines pass through unchanged. */
    public fun expand(tokens: List<String>): List<String> {
        val target = tokens.firstOrNull()?.let { TABLE[it.lowercase()] } ?: return tokens
        return target + tokens.drop(1)
    }
}
