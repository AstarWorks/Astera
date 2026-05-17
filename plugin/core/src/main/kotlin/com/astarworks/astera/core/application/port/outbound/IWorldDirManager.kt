package com.astarworks.astera.core.application.port.outbound

import java.nio.file.Path

/**
 * Outbound port: vendor-neutral filesystem locations the server cares about.
 *
 * Wave 3 (R2) defines this port but leaves the Paper implementation to Wave 5.
 * Today, plugins resolve `content/` via `dataFolder.toPath().resolveSibling("content/...")`
 * — that hardcoded Bukkit pattern moves behind this port once a Paper impl lands.
 *
 * Paths returned must be absolute and may not exist yet; callers create them
 * lazily when needed.
 */
public interface IWorldDirManager {

    /** Root for user-editable content YAML / TOML / JSON (`<server>/content/`). */
    public fun contentDir(): Path

    /** Per-world directory (Bukkit's `getWorldContainer().resolve(worldName)`). */
    public fun worldDir(worldName: String): Path

    /** Server plugins directory (`<server>/plugins/`). */
    public fun pluginsDir(): Path
}
