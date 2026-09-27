package com.moxsh.plugin.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moxsh.ui.component.GlassBackdrop
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.theme.MoxshGlassTheme

/**
 * 部件配置 Activity（appwidget_provider.xml 的 android:configure 指向本类）。
 *
 * 添加部件到桌面时由系统拉起，携带 EXTRA_APPWIDGET_ID；玻璃面板里为
 * 4 个按钮槽位分别绑定命令（保存即写 [WidgetBindingStore]），
 * 「完成」后刷新部件并按 AppWidget 配置契约回传 RESULT_OK。
 *
 * 直接从应用抽屉打开（无 EXTRA_APPWIDGET_ID）时仅展示说明。
 */
class WidgetGlassActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // 配置契约：先默认 CANCELED，用户显式「完成」才置 OK
        setResult(RESULT_CANCELED)

        setContent {
            MoxshGlassTheme {
                var bound by remember {
                    mutableStateOf(
                        (0 until WidgetBindingStore.SLOTS).map { slot ->
                            WidgetBindingStore.command(this@WidgetGlassActivity, appWidgetId, slot) ?: ""
                        },
                    )
                }
                var saved by remember { mutableStateOf(false) }

                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    GlassBackdrop(performantBlur = true, modifier = Modifier.fillMaxSize())

                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text(
                            "玻璃卡片 · 按钮绑定",
                            color = GlassTokens.onGlass,
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(12.dp))

                        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                            // 从抽屉直接打开：无目标部件，仅展示用法
                            GlassSurface(Modifier.fillMaxWidth()) {
                                Text(
                                    "请先在桌面长按空白处 → 小部件 → 添加「moxsh 玻璃卡片」，" +
                                        "添加时会自动进入本配置面板。",
                                    color = GlassTokens.onGlassDim,
                                )
                            }
                        } else {
                            GlassSurface(Modifier.fillMaxWidth()) {
                                Text(
                                    "为部件 #$appWidgetId 的 4 个按钮槽位绑定命令（留空 = 未绑定）",
                                    color = GlassTokens.onGlassDim,
                                )
                            }
                            Spacer(Modifier.height(12.dp))

                            GlassSurface(Modifier.fillMaxWidth().weight(1f)) {
                                Column {
                                    repeat(WidgetBindingStore.SLOTS) { slot ->
                                        Text("按钮 ${slot + 1}", color = GlassTokens.onGlass)
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedTextField(
                                            value = bound[slot],
                                            onValueChange = { newText ->
                                                bound = bound.toMutableList().apply { set(slot, newText) }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = {
                                                Text("如：termux-battery-status", color = GlassTokens.onGlassDim)
                                            },
                                        )
                                        Spacer(Modifier.height(10.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    // 逐槽位落盘 → 刷新部件 → 按配置契约回传
                                    repeat(WidgetBindingStore.SLOTS) { slot ->
                                        WidgetBindingStore.setCommand(
                                            this@WidgetGlassActivity, appWidgetId, slot, bound[slot],
                                        )
                                    }
                                    GlassWidgetProvider.pushUpdate(this@WidgetGlassActivity, appWidgetId)
                                    saved = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (saved) "已保存（可继续调整）" else "保存绑定") }

                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                                    finish()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = saved,
                            ) { Text("完成") }
                        }
                    }
                }
            }
        }
    }
}
