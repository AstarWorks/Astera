package com.astarworks.astera.skilllint

import com.astarworks.astera.core.application.skillengine.compose.ComposeOptions
import com.astarworks.astera.core.application.skillengine.load.SkillDecoder
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * The one place that names the production decoder. The lint rules themselves only know [SkillDecoder].
 * The class is resolved by name so this module compiles while the decoder is built in parallel (W3a);
 * once merged, replace the body with a direct construction if its constructor needs arguments.
 */
object ProductionDecoder {
    private const val CLASS_NAME = "com.astarworks.astera.core.application.skillengine.load.YamlSkillDecoder"

    fun create(): SkillDecoder {
        val cls = try {
            Class.forName(CLASS_NAME)
        } catch (e: ClassNotFoundException) {
            error("$CLASS_NAME is not on the classpath; skill-lint needs the production SkillDecoder")
        }
        val ctor = cls.constructors.firstOrNull { it.parameterCount == 0 }
            ?: error("$CLASS_NAME has no no-arg constructor; construct it in ProductionDecoder.create()")
        return ctor.newInstance() as SkillDecoder
    }
}

/**
 * `./gradlew :tools:skill-lint:run --args="[--root content/skills] [--balance]"`.
 * Exit 0 when there is no FAIL, 1 otherwise. WARNs are printed only.
 */
fun main(args: Array<String>) {
    var root = Path.of("content/skills")
    var balance = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--root" -> root = Path.of(args[++i])
            "--balance" -> balance = true
            else -> {
                System.err.println("usage: skill-lint [--root <dir>] [--balance]")
                exitProcess(2)
            }
        }
        i++
    }
    val report = SkillLinter(ProductionDecoder.create()).lint(root, ComposeOptions(allowBalancePatches = balance))
    print(report.render())
    exitProcess(if (report.ok) 0 else 1)
}
