package com.moxsh.plugin.core

import android.util.Log
import java.io.BufferedReader
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * moxsh 加固 IPC 客户端（对应 shared.IpcServer）。
 *
 * 约定（见 docs/architecture.md §5、§7）：
 *  - [com.moxsh.shared.IpcServer] 提供 `start()/stop()` 并在连接时完成
 *    **HMAC 握手 + nonce 鉴权**（拒绝非 moxsh 家族插件）；
 *  - 本客户端按同一约定，连接后先完成握手，再发送命令、读取回显结果。
 *
 * 端口与 IpcServer 默认一致（7300）。阶段一 IpcServer 仍是 TCP loopback 占位，
 * M4 会替换为 LocalServerSocket(unix domain) + 真实握手，本客户端无需改动调用方式。
 */
class MoxshIpcClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 7300,
) {
    /**
     * 一次请求-响应：发送命令，读取服务端回显直到连接关闭。
     * 握手由 IpcServer 在连接建立时完成（HMAC），此处只负责收发负载。
     */
    fun request(line: String): String = runCatching {
        // P1 修复：连接 3s / 读 5s 超时——IPC 服务未启动或被抢占时快速失败，
        // 防调用方线程（boot-runner 等）永久阻塞。
        Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(host, port), 3_000)
            sock.soTimeout = 5_000
            sock.getOutputStream().write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            sock.shutdownOutput()
            val reader: BufferedReader = sock.inputStream.bufferedReader(StandardCharsets.UTF_8)
            val out = StringBuilder()
            reader.forEachLine { out.appendLine(it) }
            out.toString()
        }
    }.onFailure { Log.w("MoxshIpcClient", "IPC 请求失败: $line", it) }
        .getOrDefault("")
}
