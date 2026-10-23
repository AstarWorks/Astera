package com.astarworks.astera.animimport

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        require(args.size in 1..2) { "usage: anim-import <model.bbmodel> [output-directory]" }
        val written = AnimationImporter.importModel(
            Path.of(args[0]), Path.of(args.getOrElse(1) { "content/animations" }),
        )
        written.forEach { println("animation: $it") }
    } catch (error: Exception) {
        System.err.println("anim-import: ${error.message}")
        exitProcess(2)
    }
}
