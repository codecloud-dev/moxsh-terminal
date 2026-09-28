package com.moxsh.plugin.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 插件事件体系（v2）。
 *
 * 插件声明 `subscribe_events` 权限后，可通过 [PluginApi.events] 订阅以下事件：
 *  - [PluginEvent.CommandExecuted]：一条命令被送入任意会话（含命令文本与会话 id）；
 *  - [PluginEvent.OutputProduced]：任意会话产出了新的终端输出（字节量级通知，不含内容；
 *    内容读取需另声明 read_screen 并调用屏幕快照 API）；
 *  - [PluginEvent.SessionOpened] / [PluginEvent.SessionClosed]：会话生命周期；
 *  - [PluginEvent.BootCompleted]：moxsh 自有 bootstrap 环境就绪。
 *
 * 分发语义：监听回调一律在**主线程**执行（经 [Handler] 投递），
 * 插件回调里可安全触碰 UI；耗时逻辑请自行切线程。
 */
sealed class PluginEvent {
    abstract val sessionId: Long

    /** 一条命令被写入会话。 */
    data class CommandExecuted(override val sessionId: Long, val command: String) : PluginEvent()

    /** 会话产出新输出（bytes = 本轮读取的字节数，仅量级通知）。 */
    data class OutputProduced(override val sessionId: Long, val bytes: Int) : PluginEvent()

    /** 新会话创建。 */
    data class SessionOpened(override val sessionId: Long) : PluginEvent()

    /** 会话关闭。 */
    data class SessionClosed(override val sessionId: Long) : PluginEvent()

    /** moxsh 自有运行环境（bootstrap）就绪。 */
    data class BootCompleted(override val sessionId: Long = -1) : PluginEvent()
}

/** 事件监听器句柄：持用它调用 [PluginEventBus.unregister] 取消订阅。 */
class EventListenerToken internal constructor(internal val id: Long)

/**
 * 进程内事件总线。主 app（MoxshSessionService / 执行引擎封装层）负责 publish，
 * 插件经 [PluginApi.events] 订阅。线程安全：注册表用 COW 列表，回调统一主线程分发。
 */
class PluginEventBus {

    private data class Registration(
        val token: EventListenerToken,
        val eventType: Class<out PluginEvent>?,
        val listener: (PluginEvent) -> Unit,
    )

    private val main = Handler(Looper.getMainLooper())
    private val registrations = CopyOnWriteArrayList<Registration>()
    private val nextToken = java.util.concurrent.atomic.AtomicLong(1)

    /**
     * 注册监听。
     * @param eventType 只监听该类型（传 null 监听全部事件）。
     */
    fun register(
        eventType: Class<out PluginEvent>?,
        listener: (PluginEvent) -> Unit,
    ): EventListenerToken {
        val token = EventListenerToken(nextToken.getAndIncrement())
        registrations.add(Registration(token, eventType, listener))
        return token
    }

    /** 按 token 取消订阅。 */
    fun unregister(token: EventListenerToken) {
        registrations.removeAll { it.token.id == token.id }
    }

    /** 发布事件（任意线程可调用；分发到主线程）。 */
    fun publish(event: PluginEvent) {
        if (registrations.isEmpty()) return
        main.post {
            for (r in registrations) {
                if (r.eventType == null || r.eventType.isInstance(event)) {
                    runCatching { r.listener(event) }
                        .onFailure { android.util.Log.w("PluginEventBus", "事件回调异常", it) }
                }
            }
        }
    }
}
