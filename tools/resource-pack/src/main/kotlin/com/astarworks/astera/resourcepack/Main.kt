package com.astarworks.astera.resourcepack

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.system.exitProcess

/**
 * CLI of `:tools:resource-pack`.
 *
 *  - `pack <contentDir> <outDir>`  build `astera.zip` (+ `.sha1`) into outDir
 *  - `hud-art <hudDir>`            rewrite the committed HUD art under content/hud
 */
public fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "pack" -> {
            require(args.size == 3) { "usage: pack <contentDir> <outDir>" }
            val built = ResourcePackBuilder(Path(args[1])).buildTo(Path(args[2]))
            println("resource pack: ${built.zip} (${built.entryCount} files, sha1 ${built.sha1})")
        }
        "hud-art" -> {
            require(args.size == 2) { "usage: hud-art <hudDir>" }
            val dir: Path = Path(args[1])
            val written = HudArtGenerator.writeTo(dir)
            println("hud art: ${written.size} files written under $dir")
        }
        else -> {
            System.err.println("usage: pack <contentDir> <outDir> | hud-art <hudDir>")
            exitProcess(2)
        }
    }
}
