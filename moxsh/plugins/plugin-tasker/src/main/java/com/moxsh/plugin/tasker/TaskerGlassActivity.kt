package com.moxsh.plugin.tasker

import android.os.Bundle
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
import kotlin.concurrent.thread

/**
 * Tasker 联动的玻璃配置面板（docs/plugins-rewrite.md §5.3）。
 *
 *  - 命令模板管理：玻璃卡片列表增删，保存即生效；
 *  - 试运行：点击模板立即执行并回显结果（可观测性，替代静默失败）；
 *  - 意图约定卡：展示 action / extra 约定，Tasker 侧照抄配置即可。
 */
class TaskerGlassActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoxshGlassTheme {
                val ctx = LocalContext.current
                var commands by remember { mutableStateOf(TaskerCommandStore.load(ctx)) }
                var input by remember { mutableStateOf("") }
                var runResult by remember { mutableStateOf<String?>(null) }
                var running by remember { mutableStateOf(false) }

                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    GlassBackdrop(performantBlur = true, modifier = Modifier.fillMaxSize())

                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text(
                            "Tasker 联动 · 玻璃配置",
                            color = GlassTokens.onGlass,
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(12.dp))

                        // ── 卡片 1：意图约定说明 ──
                        GlassSurface(Modifier.fillMaxWidth()) {
                            Text("Tasker 侧配置约定", color = GlassTokens.onGlass)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "action：${TaskerPluginContract.ACTION_RUN}\n" +
                                    "extra command：整行命令（必填）\n" +
                                    "extra args：参数数组（可选）\n" +
                                    "extra result_pending_intent：回传通道（可选）",
                                color = GlassTokens.onGlassDim,
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        // ── 卡片 2：命令模板列表（点击试运行） ──
                        GlassSurface(Modifier.fillMaxWidth().weight(1f)) {
                            Text("命令模板（点击试运行）", color = GlassTokens.onGlass)
                            Spacer(Modifier.height(8.dp))
                            Column(Modifier.weight(1f)) {
                                if (commands.isEmpty()) {
                                    Text("暂无模板，下方输入框添加", color = GlassTokens.onGlassDim)
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
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    // 试运行：独立线程执行，结果回显在下方
                                                    running = true
                                                    thread(name = "moxsh-tasker-try") {
                                                        val out = TaskerRouter.executeSafely(cmd, null)
                                                        runResult = out
                                                        running = false
                                                    }
                                                },
                                        )
                                        Text(
                                            "删除",
                                            color = GlassTokens.onGlassDim,
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .clickable {
                                                    commands = commands.toMutableList().apply { removeAt(index) }
                                                    TaskerCommandStore.save(ctx, commands)
                                                },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("如：termux-battery-status", color = GlassTokens.onGlassDim) },
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    val line = input.trim()
                                    if (line.isNotEmpty()) {
                                        commands = commands + line
                                        TaskerCommandStore.save(ctx, commands)
                                        input = ""
                                    }
                                }) { Text("添加") }
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // ── 卡片 3：试运行结果 ──
                        GlassSurface(Modifier.fillMaxWidth()) {
                            Text(
                                if (running) "执行中…"
                                else runResult ?: "试运行结果将显示在这里",
                                color = GlassTokens.onGlassDim,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                            Text("完成")
                        }
                    }
                }
            }
        }
    }
}
