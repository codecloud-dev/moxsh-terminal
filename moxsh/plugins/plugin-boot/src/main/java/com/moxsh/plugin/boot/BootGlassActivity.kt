package com.moxsh.plugin.boot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moxsh.ui.component.GlassBackdrop
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.theme.MoxshGlassTheme

/**
 * 自启命令的玻璃配置面板（重写 Termux:Boot 的黑盒脚本目录）。
 *
 *  - 命令列表可视化：玻璃卡片逐条展示，可增删（保存即生效）；
 *  - 可观测性：展示最近一次开机执行摘要（不再「根本不触发也不知道」）；
 *  - 引导式豁免：一键跳转系统电池优化设置，缓解厂商杀后台导致的
 *    「BOOT_COMPLETED 收不到」痛点（docs/plugins-rewrite.md §2.2）。
 */
class BootGlassActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoxshGlassTheme {
                val ctx = LocalContext.current
                var commands by remember { mutableStateOf(BootCommandStore.load(ctx)) }
                var input by remember { mutableStateOf("") }
                var lastRun by remember { mutableStateOf(BootCommandStore.lastRunSummary(ctx)) }

                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    GlassBackdrop(performantBlur = true, modifier = Modifier.fillMaxSize())

                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text(
                            "开机自启 · 玻璃配置",
                            color = GlassTokens.onGlass,
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(12.dp))

                        // ── 卡片 1：状态摘要 + 厂商豁免引导 ──
                        GlassSurface(Modifier.fillMaxWidth()) {
                            Text(
                                lastRun ?: "尚未记录开机执行（重启一次后回看）",
                                color = GlassTokens.onGlassDim,
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = {
                                // 跳系统电池优化白名单页，降低 BOOT_COMPLETED 被厂商拦截概率
                                runCatching {
                                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                }
                            }) {
                                Text("电池优化豁免引导")
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // ── 卡片 2：命令列表（增删） ──
                        GlassSurface(Modifier.fillMaxWidth().weight(1f)) {
                            Text("自启命令（开机后按顺序执行）", color = GlassTokens.onGlass)
                            Spacer(Modifier.height(8.dp))
                            Column(Modifier.weight(1f)) {
                                if (commands.isEmpty()) {
                                    Text("暂无命令，下方输入框添加", color = GlassTokens.onGlassDim)
                                }
                                commands.forEachIndexed { index, cmd ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            cmd,
                                            color = GlassTokens.onGlass,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "删除",
                                            color = GlassTokens.onGlassDim,
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .clickable {
                                                    commands = commands.toMutableList().apply { removeAt(index) }
                                                    BootCommandStore.save(ctx, commands)
                                                },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))

                            // 添加命令
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("如：termux-wake-lock", color = GlassTokens.onGlassDim) },
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    val line = input.trim()
                                    if (line.isNotEmpty()) {
                                        commands = commands + line
                                        BootCommandStore.save(ctx, commands)
                                        input = ""
                                    }
                                }) { Text("添加") }
                            }
                        }
                    }
                }
            }
        }
    }
}
