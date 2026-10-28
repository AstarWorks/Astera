package com.astarworks.astera.core.application.skillengine.load

/**
 * Which block keys exist on this host. The world ops (`set_block`, `place_shape`) name a block by key; the decoder asks
 * here so an unknown key is a load error naming the file and path, not a block that silently never appears at cast time.
 * The Paper adapter answers vanilla keys through `Material.matchMaterial` and custom keys through the custom block registry.
 */
public fun interface IBlockKeyCatalog {
    public fun exists(key: String): Boolean

    public companion object {
        /**
         * For hosts that have no block world to ask (content lint, headless sims): every key passes. Never use it where a
         * block catalog exists.
         */
        public val Unchecked: IBlockKeyCatalog = IBlockKeyCatalog { true }
    }
}
