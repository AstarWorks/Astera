package com.astarworks.astera.core.domain.skillengine.world

/** What a skill may touch, independent of the platform. */
public object WorldBlockRules {
    private val AIR_LIKE: Set<String> = setOf(
        "air", "cave_air", "void_air", "water", "lava", "short_grass", "tall_grass", "fern", "snow", "fire",
        "light", "vine", "glow_lichen",
    )

    /** Never removed, never placed: unbreakable, technical, or holding data that a revert would lose. */
    private val PROTECTED: Set<String> = setOf(
        "bedrock", "barrier", "light", "structure_void", "structure_block", "jigsaw", "command_block",
        "chain_command_block", "repeating_command_block", "end_portal", "end_portal_frame", "end_gateway",
        "nether_portal", "spawner", "trial_spawner", "vault", "chest", "trapped_chest", "ender_chest", "barrel",
        "furnace", "blast_furnace", "smoker", "hopper", "dropper", "dispenser", "beacon", "lectern",
        "brewing_stand", "jukebox", "crafter",
    )

    public fun normalize(key: String): String = if (':' in key) key.lowercase() else "minecraft:${key.lowercase()}"

    private fun idOf(key: String): Pair<String, String> {
        val k = normalize(key)
        return k.substringBefore(':') to k.substringAfter(':')
    }

    public fun isAir(key: String?): Boolean {
        if (key == null) return true
        val (ns, id) = idOf(key)
        return ns == "minecraft" && id.endsWith("air")
    }

    public fun isReplaceable(key: String?): Boolean {
        if (key == null) return true
        val (ns, id) = idOf(key)
        return ns == "minecraft" && id in AIR_LIKE
    }

    /** Non-vanilla blocks carry their own durability, so a skill neither removes nor overwrites them. */
    public fun isProtected(key: String): Boolean {
        val (ns, id) = idOf(key)
        return ns != "minecraft" || id in PROTECTED || id.endsWith("shulker_box") || id.endsWith("_sign") || id.endsWith("_bed")
    }
}
