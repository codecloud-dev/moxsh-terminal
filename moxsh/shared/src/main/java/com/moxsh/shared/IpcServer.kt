package com.moxsh.shared

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * 加固本地 socket IPC（unix domain，[android.net.LocalServerSocket]）。
 *
 * 解决 Termux 原 IPC **无鉴权、同 UID 应用可连** 的缺陷（架构 §5 / D7）：
 *  1. 采用 **nonce + HMAC-SHA256 包签名** 握手：服务端发随机 nonce，客户端回
 *     `HMAC-SHA256(secret, nonce)` 的 hex，服务端验签通过才授权，否则立即关闭连接。
 *  2. 运行在 **foreground service** 中（由调用方保证），对抗 Android 12+ 的
 *     Phantom Process Killer——后台进程被回收后 socket 失效，前台服务可保活。
 *  3. 采用 **length-prefixed 帧** 读写（含 verb + payload），便于后续加 schema / 超时 / 背压。
 *  4. 断开自愈：accept 循环常驻，client 线程独立，单个连接异常不影响服务端。
 *
 * 鉴权算法与服务端 Rust 侧 `terminal-core/src/ipc.rs` 保持一致：
 *   - 服务端生成 16 字节随机 nonce，以 hex（32 字符）下发；
 *   - 客户端计算 `HMAC-SHA256(key = secret, message = nonce)` 并回传 hex；
 *   - 服务端用同样算法重算并做 **常量时间比较**，防时序侧信道。
 *
 * shared secret 实际应由 AndroidKeyStore 派生（AES/HMAC 密钥，绝不落盘明文）；
 * 这里以 [start] 的 `secret: ByteArray` 入参注入，由上层（foreground service）从
 * KeyStore 取出后传入，保证本类不依赖具体密钥存储实现。
 *
 * 注意：本类被其它 agent 依赖其公开方法签名（[start]/[stop]/[IpcMessage]）。
 */
class IpcServer(private val name: String = "moxsh_ipc") {

    /**
     * 一条 IPC 消息：动词（如 "exec" / "ping"）+ 二进制负载。
     * 帧格式见 [writeFrame]/[readFrame]。
     */
    data class IpcMessage(val verb: String, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as IpcMessage
            return verb == other.verb && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * verb.hashCode() + payload.contentHashCode()
    }

    private val running = AtomicBoolean(false)
    private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null

    /** 所有活跃 client 处理线程，stop 时统一中断。 */
    private val clientThreads = mutableListOf<Thread>()
    private val clientLock = Any()

    /**
     * 启动 IPC 服务。通常在 foreground service 的 `onCreate`/启动路径里调用。
     * @param secret 共享密钥（HMAC 验证用，由 AndroidKeyStore 派生后注入）。
     */
    fun start(secret: ByteArray) {
        if (running.get()) return
        running.set(true)
        acceptThread = thread(name = "moxsh-ipc-accept") {
            try {
                // LocalServerSocket 绑定到 unix domain 抽象名 "moxsh_ipc"。
                server = LocalServerSocket(name)
                while (running.get()) {
                    val client = server?.accept() ?: break
                    handleClient(client, secret)
                }
            } catch (_: Throwable) {
                // accept 失败 / 服务被关闭：退出循环，等待下次 start。
            }
        }
    }

    /** 停止 IPC 服务：关闭监听、中断所有 client 线程。 */
    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
        synchronized(clientLock) {
            clientThreads.forEach { it.interrupt() }
            clientThreads.clear()
        }
    }

    /**
     * 为一个客户端连接建立握手与服务循环（独立线程，避免阻塞 accept）。
     */
    private fun handleClient(socket: LocalSocket, secret: ByteArray) {
        val t = thread(name = "moxsh-ipc-client") {
            socket.use { s ->
                val input = DataInputStream(BufferedInputStream(s.inputStream))
                val output = DataOutputStream(BufferedOutputStream(s.outputStream))
                // 未通过握手即关闭，杜绝未授权访问。
                if (!handshake(output, input, secret)) {
                    return@thread
                }
                // 握手成功：进入消息循环，逐帧分发。
                while (running.get()) {
                    val msg = readFrame(input) ?: break
                    dispatch(msg, output)
                }
            }
        }
        synchronized(clientLock) { clientThreads.add(t) }
    }

    /**
     * 握手协议：
     *  1. 服务端发 16 字节 nonce 的 hex（32 字符）；
     *  2. 客户端回 `HMAC-SHA256(secret, nonce)` 的 hex（64 字符）；
     *  3. 服务端重算并常量时间比较，通过返回 true。
     */
    private fun handshake(out: DataOutputStream, input: DataInputStream, secret: ByteArray): Boolean {
        val nonce = ByteArray(16).also { SECURE_RANDOM.nextBytes(it) }
        val nonceHex = toHex(nonce)
        // 用 writeUTF 下发 nonce（带长度前缀，便于客户端解析）。
        out.writeUTF(nonceHex)
        out.flush()

        val clientHex = runCatching { input.readUTF() }.getOrNull() ?: return false
        val expected = hmacHex(secret, nonce)
        return constantTimeEquals(expected, clientHex)
    }

    /**
     * 消息分发：当前为最小实现（处理 ping，其余回显 ack）。
     * 后续接入实际 verb（exec / notify / sensor 等）在此扩展。
     */
    private fun dispatch(msg: IpcMessage, out: DataOutputStream) {
        when (msg.verb) {
            "ping" -> writeFrame(out, IpcMessage("pong", msg.payload))
            else -> writeFrame(out, IpcMessage("ack", byteArrayOf()))
        }
    }

    /** 写出一条 length-prefixed 帧：[verbLen][verb][payloadLen][payload]。 */
    private fun writeFrame(out: DataOutputStream, msg: IpcMessage) {
        val verbBytes = msg.verb.toByteArray(Charsets.UTF_8)
        out.writeInt(verbBytes.size)
        out.write(verbBytes)
        out.writeInt(msg.payload.size)
        out.write(msg.payload)
        out.flush()
    }

    /** 读取一条帧；连接断开（EOF）时返回 null。 */
    private fun readFrame(input: DataInputStream): IpcMessage? = runCatching {
        val verbLen = input.readInt()
        val verbBytes = ByteArray(verbLen).also { input.readFully(it) }
        val payloadLen = input.readInt()
        val payload = ByteArray(payloadLen).also { input.readFully(it) }
        IpcMessage(verbBytes.toString(Charsets.UTF_8), payload)
    }.getOrNull()

    /** HMAC-SHA256(key = secret, message) 的 hex 串。 */
    private fun hmacHex(key: ByteArray, message: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return toHex(mac.doFinal(message))
    }

    /** 常量时间比较，防时序侧信道攻击。 */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    /** 字节数组转小写 hex。 */
    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() ushr 4) and 0xf])
            sb.append(HEX[b.toInt() and 0xf])
        }
        return sb.toString()
    }

    companion object {
        private val SECURE_RANDOM = SecureRandom()
        private const val HEX = "0123456789abcdef"
    }
}
