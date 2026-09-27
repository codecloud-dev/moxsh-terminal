package com.moxsh.plugin.float

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moxsh.ui.component.*
import com.moxsh.ui.theme.MoxshGlassTheme

/**
 * 悬浮终端入口 Activity：先引导「显示在其它应用上」授权，再拉起 [FloatGlassService]。
 * 仍是 moxsh 原生玻璃插件（D7 体系②），独立签名、与 Termux 插件不互通。
 */
class FloatGlassActivity : ComponentActivity() {
    // 授权结果回调：拿到权限后直接启动服务
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { if (Settings.canDrawOverlays(this)) startFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoxshGlassTheme {
                var granted by remember { mutableStateOf(Settings.canDrawOverlays(this@FloatGlassActivity)) }
                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    GlassBackdrop(performantBlur = true, modifier = Modifier.fillMaxSize())
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        GlassSurface(Modifier.fillMaxWidth()) {
                            Text("悬浮玻璃终端", color = GlassTokens.onGlass)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (granted) "已授权，点击下方按钮启动悬浮窗"
                                else "需要「显示在其它应用上」权限才能悬浮",
                                color = GlassTokens.onGlassDim,
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = {
                                if (Settings.canDrawOverlays(this@FloatGlassActivity)) {
                                    startFloat()
                                } else {
                                    // 跳转系统授权页
                                    overlayLauncher.launch(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.fromParts("package", packageName, null),
                                        ),
                                    )
                                }
                                granted = Settings.canDrawOverlays(this@FloatGlassActivity)
                            }) {
                                Text(if (granted) "启动悬浮终端" else "授权并启动")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startFloat() {
        startService(Intent(this, FloatGlassService::class.java))
        finish()
    }
}
