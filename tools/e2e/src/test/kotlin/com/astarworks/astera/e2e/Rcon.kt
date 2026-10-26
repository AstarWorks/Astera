package com.astarworks.astera.e2e

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal Source-RCON client: server-side ground truth (op, tp, `e2eprobe`, `list`) next to what bots see. */
class Rcon(host: String, port: Int, password: String) : Closeable {
    private val socket = Socket().apply {
        connect(InetSocketAddress(host, port), 5_000)
        soTimeout = 10_000
    }
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private var nextId = 1

    init {
        val id = send(TYPE_LOGIN, password)
        val reply = read()
        check(reply.first != -1 && reply.first == id) { "RCON login rejected" }
    }

    @Synchronized
    fun command(cmd: String): String {
        val id = send(TYPE_COMMAND, cmd)
        val sb = StringBuilder()
        // Paper answers with one packet for everything we use; read until the id matches once.
        val (rid, body) = read()
        check(rid == id) { "RCON id mismatch ($rid != $id)" }
        sb.append(body)
        return sb.toString()
    }

    private fun send(type: Int, body: String): Int {
        val id = nextId++
        val bytes = body.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(14 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(10 + bytes.size).putInt(id).putInt(type).put(bytes).put(0).put(0)
        output.write(buf.array())
        output.flush()
        return id
    }

    private fun read(): Pair<Int, String> {
        val head = ByteArray(4).also { input.readFully(it) }
        val len = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).int
        val payload = ByteArray(len).also { input.readFully(it) }
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val id = bb.int
        bb.int // type
        val body = ByteArray(len - 10).also { bb.get(it) }
        return id to String(body, Charsets.UTF_8)
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val TYPE_COMMAND = 2
        const val TYPE_LOGIN = 3
    }
}
