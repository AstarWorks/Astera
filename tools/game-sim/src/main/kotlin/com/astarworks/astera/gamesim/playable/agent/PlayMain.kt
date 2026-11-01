package com.astarworks.astera.gamesim.playable.agent

import com.astarworks.astera.gamesim.GameSim
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream

/**
 * `./gradlew -q :tools:game-sim:play` entry point (ADR-0316). Commands come from stdin, or from the file named by the first
 * argument. The protocol owns the real stdout; everything else the stack prints (logback, wiring chatter) is sent to stderr
 * before any of it initialises, so every stdout line is exactly one observation.
 */
fun main(args: Array<String>) {
    val protocol = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8)
    System.setOut(System.err)
    val input = if (args.isNotEmpty()) File(args[0]).bufferedReader() else BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    val content = GameSim.defaultContentDir().toFile()
    val traceDir = GameSim.defaultTraceDir()
    AgentPort(SoloMatchHost, content, traceDir).run(input, protocol)
    protocol.flush()
    System.exit(0)
}
