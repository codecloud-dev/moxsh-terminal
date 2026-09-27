package com.moxsh.plugin.floating

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import com.moxsh.core.TerminalCore
import com.moxsh.ui.theme.MoxshGlassTheme

/**
 * 悬浮玻璃终端服务（D5 体系②）。
 *
 * 用 `SYSTEM_ALERT_WINDOW` + WindowManager 在任意界面之上挂一个液态玻璃终端窗：
 *  - 通过 [WindowManager.LayoutParams] 控制位置 / 尺寸；
 *  - 内容是一颗 [ComposeView]，里面渲染 [GlassFloatingTerminal]；
 *  - 拖拽 / 缩放由 Composable 把位移回传，这里写回 LayoutParams 并 `updateViewLayout`；
 *  - 关闭时移除窗口、关闭会话、结束服务。
 *
 * 首次使用需 `Settings.canDrawOverlays` 授权（见 [FloatGlassActivity] 的引导）。
 */
class FloatGlassService : Service() {

    private lateinit var wm: WindowManager
    private var composeView: ComposeView? = null
    private var session: TerminalCore.Session? = null
    private val core = TerminalCore()
    private var savedState: FloatWindowState = FloatWindowState()

    private val params by lazy {
        val dm = resources.displayMetrics
        val d = dm.density
        savedState = WindowStateStore.load(this)
        WindowManager.LayoutParams(
            (savedState.width * d).toInt(),
            (savedState.height * d).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (savedState.x * d).toInt()
            y = (savedState.y * d).toInt()
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // 打开一个共享终端会话（与主 app 同一内核）
        session = core.open("/system/bin/sh", 80, 24)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (composeView == null) showWindow()
        return START_STICKY
    }

    private fun showWindow() {
        val s = session ?: return
        composeView = ComposeView(this).apply {
            setContent {
                MoxshGlassTheme {
                    GlassFloatingTerminal(
                        session = s,
                        perfBlur = true,
                        fontSizeSp = savedState.fontSizeSp,
                        onClose = { dismiss() },
                        onMove = { dx, dy ->
                            params.x += dx
                            params.y += dy
                            wm.updateViewLayout(composeView, params)
                        },
                        onResize = { dw, dh ->
                            params.width = (params.width + dw).coerceAtLeast(200)
                            params.height = (params.height + dh).coerceAtLeast(160)
                            wm.updateViewLayout(composeView, params)
                        },
                    )
                }
            }
        }
        wm.addView(composeView, params)
    }

    private fun dismiss() {
        composeView?.let { wm.removeView(it) }
        composeView = null
        // 持久化最终位置 / 尺寸
        val d = resources.displayMetrics.density
        WindowStateStore.save(
            this,
            FloatWindowState(
                x = (params.x / d).toInt(),
                y = (params.y / d).toInt(),
                width = (params.width / d).toInt(),
                height = (params.height / d).toInt(),
                fontSizeSp = savedState.fontSizeSp,
            ),
        )
        stopSelf()
    }

    override fun onDestroy() {
        composeView?.let { wm.removeView(it) }
        composeView = null
        session?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
