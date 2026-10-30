package com.astarworks.astera.core.application.skillengine.load

/**
 * Which vanilla block states and item keys exist on this host. An op that names one (the `particle` op's `block:` / `item:`
 * data) asks here at lowering, so an unknown key is a load error naming the file and path, not a particle the host silently drops
 * at cast time. The Paper adapter answers from the live server; [VanillaRegistryKeys] answers from the bundled registry of the
 * same MC version, so a headless sim and content lint load exactly like the server.
 */
public interface IVanillaKeyCatalog {
    /** `null` when [state] (`minecraft:stone`, `oak_log[axis=y]`) is a valid block state, otherwise the reason. */
    public fun blockProblem(state: String): String?

    /** `null` when [key] (`minecraft:diamond`) is an item, otherwise the reason. */
    public fun itemProblem(key: String): String?
}
