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

    // v1 WorldMechanics.kt:687-690.
    private val SOIL_WORDS: List<String> = listOf("dirt", "grass_block", "sand", "gravel", "clay", "mud", "snow", "podzol", "mycelium", "soul_soil", "farmland")
    private val WOOD_WORDS: List<String> = listOf("plank", "log", "wood", "stem", "hyphae", "leaves", "fence", "door", "bookshelf", "scaffolding", "bamboo", "wool", "hay_block", "carpet")
    private val HARD: Set<String> = setOf("obsidian", "crying_obsidian", "respawn_anchor", "enchanting_table", "anvil", "chipped_anvil", "damaged_anvil")
    private val HARDEST: Set<String> = setOf("reinforced_deepslate", "netherite_block", "ancient_debris")

    /**
     * v1 WorldMechanics.breakable (WorldMechanics.kt:457-472), 1:1 as v2 kept it. The class decides what a tool is for (shovel = soil, axe = wood,
     * pickaxe = everything else; a null [toolClass] accepts any block), and [power] must reach the block's hardness tier
     * (hardest 3, hard 2, everything else 1). Namespaces are ignored like v1's `BlockType.identifier`.
     */
    public fun breakable(blockKey: String, power: Double, toolClass: String?): Boolean {
        val id = idOf(blockKey).second
        val kind = when {
            id.contains("sandstone") || id.contains("mud_brick") -> "pickaxe"
            SOIL_WORDS.any { it in id } -> "shovel"
            WOOD_WORDS.any { it in id } -> "axe"
            else -> "pickaxe"
        }
        if (toolClass != null && toolClass != kind) return false
        val hardness = when {
            id in HARDEST -> 3
            id in HARD -> 2
            else -> 1
        }
        return hardness <= power
    }

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
