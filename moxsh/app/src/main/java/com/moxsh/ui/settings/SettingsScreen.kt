package com.moxsh.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moxsh.auth.EmailBindingStore
import com.moxsh.auth.GitHubLogin
import com.moxsh.auth.SessionStore
import com.moxsh.auth.UserProfileStore
import com.moxsh.cloud.SyncClient
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 偏好持久化（SharedPreferences）
// 主界面（perfMode/字号/主题色）与设置页共用；apply() 异步落盘。
// ---------------------------------------------------------------------------
object MoxshPrefs {
    private const val FILE = "moxsh_prefs"
    private const val KEY_FONT_SIZE = "font_size_sp"
    private const val KEY_ACCENT = "accent_color"
    private const val KEY_REALTIME_BLUR = "realtime_blur"   // true=开实时模糊（性能模式关）
    private const val KEY_MIRROR = "apt_mirror"
    private const val KEY_CLOUD_SYNC = "cloud_sync_enabled"   // 命令云同步开关（云同步 MVP）

    const val DEFAULT_FONT_SIZE = 14f
    val DEFAULT_ACCENT = 0xFF7CF9E5.toInt() // toInt() 非编译期常量，故 val（object 内等价用法）

    // APT 镜像源候选（国内优化，见 docs/architecture.md §8）：
    //  - 清华 TUNA：mirrors.tuna.tsinghua.edu.cn/termux（教育网快且稳，默认）
    //  - 中科大 USTC：mirrors.ustc.edu.cn/termux（备选）
    //  - 官方：termux.org / packages.termux.dev（国内直连慢，仅兜底）
    const val MIRROR_TUNA = "清华 TUNA"
    const val MIRROR_USTC = "中科大 USTC"
    const val MIRROR_OFFICIAL = "官方源"
    val MIRRORS = listOf(MIRROR_TUNA, MIRROR_USTC, MIRROR_OFFICIAL)
    const val DEFAULT_MIRROR = MIRROR_TUNA

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 终端字号（sp）。 */
    fun fontSize(ctx: Context): Float = sp(ctx).getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
    fun setFontSize(ctx: Context, v: Float) { sp(ctx).edit().putFloat(KEY_FONT_SIZE, v).apply() }

    /** 主题色（ARGB Int）。 */
    fun accent(ctx: Context): Int = sp(ctx).getInt(KEY_ACCENT, DEFAULT_ACCENT)
    fun setAccent(ctx: Context, v: Int) { sp(ctx).edit().putInt(KEY_ACCENT, v).apply() }

    /** 玻璃实时模糊开关；null 表示用户从未选择（按 D8 设备性能自动）。 */
    fun realtimeBlur(ctx: Context): Boolean? {
        if (!sp(ctx).contains(KEY_REALTIME_BLUR)) return null
        return sp(ctx).getBoolean(KEY_REALTIME_BLUR, true)
    }
    fun setRealtimeBlur(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean(KEY_REALTIME_BLUR, v).apply() }

    /** APT 镜像源名称。 */
    fun mirror(ctx: Context): String = sp(ctx).getString(KEY_MIRROR, DEFAULT_MIRROR) ?: DEFAULT_MIRROR
    fun setMirror(ctx: Context, v: String) { sp(ctx).edit().putString(KEY_MIRROR, v).apply() }

    /** 命令云同步开关（默认关：隐私优先，用户显式开启后才捕获命令历史）。 */
    fun cloudSync(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_CLOUD_SYNC, false)
    fun setCloudSync(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean(KEY_CLOUD_SYNC, v).apply() }
}

/**
 * 玻璃风格小圆按钮（设置页返回键 / 主界面设置入口共用，internal 限 app 模块内）。
 */
@Composable
internal fun GlassIconButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(GlassTokens.surfaceTint)
            .border(1.dp, GlassTokens.stroke, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = GlassTokens.onGlass)
    }
}

/**
 * moxsh 玻璃风格设置页。
 *
 * 全部选项经 [MoxshPrefs]（SharedPreferences）持久化，重启后恢复：
 *  - 终端字体大小（与终端双指捏合联动同一份偏好）
 *  - 主题色（极光青 / 星云紫 / 樱粉，注入 MoxshGlassTheme.primary）
 *  - 玻璃实时模糊开关（关闭即性能模式；D8：默认按 API31+ 设备能力自动）
 *  - APT 镜像源（清华 TUNA / 中科大 USTC / 官方；国内优化默认清华）
 *
 * 状态由调用方（MainActivity）持有并统一写盘，本页为纯受控 UI。
 */
@Composable
fun SettingsScreen(
    perfMode: Boolean,
    onPerfModeChange: (Boolean) -> Unit,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    accent: Int,
    onAccentChange: (Int) -> Unit,
    mirror: String,
    onMirrorChange: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(12.dp)) {
        // 顶栏：返回 + 标题
        GlassSurface(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton("←", onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Text("设置", color = GlassTokens.onGlass, style = MaterialTheme.typography.titleMedium)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 字体大小 ----
            GlassSurface(Modifier.fillMaxWidth()) {
                Text("终端字体大小（终端内双指捏合同样生效）", color = GlassTokens.onGlass)
                Slider(
                    value = fontSizeSp,
                    onValueChange = onFontSizeChange,
                    valueRange = 8f..32f,
                )
                Text("${fontSizeSp.roundToInt()} sp", color = GlassTokens.onGlassDim)
            }

            // ---- 主题色 ----
            GlassSurface(Modifier.fillMaxWidth()) {
                Text("主题色", color = GlassTokens.onGlass)
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        0xFF7CF9E5.toInt() to "极光青",
                        0xFFB388FF.toInt() to "星云紫",
                        0xFFFF8FB1.toInt() to "樱粉",
                    ).forEach { (color, name) ->
                        val selected = accent == color
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(color), CircleShape)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) Color.White else GlassTokens.stroke,
                                        shape = CircleShape,
                                    )
                                    .clickable { onAccentChange(color) },
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                name,
                                color = if (selected) GlassTokens.onGlass else GlassTokens.onGlassDim,
                            )
                        }
                    }
                }
            }

            // ---- 玻璃实时模糊（性能模式） ----
            GlassSurface(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("玻璃实时模糊", color = GlassTokens.onGlass)
                        Text(
                            "关闭即性能模式：API31+ 停用 RenderEffect 实时模糊，" +
                                "低版本本就静态回退；低配设备建议关闭更流畅（D8）",
                            color = GlassTokens.onGlassDim,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    // Switch 语义用"开模糊"，与 perfMode（开性能）互补
                    Switch(checked = !perfMode, onCheckedChange = { onPerfModeChange(!it) })
                }
            }

            // ---- APT 镜像源 ----
            GlassSurface(Modifier.fillMaxWidth()) {
                Text("APT 镜像源（国内优化）", color = GlassTokens.onGlass)
                Text(
                    "默认清华 TUNA：教育网快且稳；官方源国内直连慢，仅作兜底。" +
                        "选择后由 moxsh-change-repo 写入 sources.list",
                    color = GlassTokens.onGlassDim,
                )
                Spacer(Modifier.size(6.dp))
                MoxshPrefs.MIRRORS.forEach { name ->
                    val selected = mirror == name
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onMirrorChange(name) }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .border(
                                    width = 2.dp,
                                    color = if (selected) GlassTokens.onGlass else GlassTokens.stroke,
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(GlassTokens.onGlass, CircleShape),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            name,
                            color = if (selected) GlassTokens.onGlass else GlassTokens.onGlassDim,
                        )
                    }
                }
            }

            // ---- 账号与云同步（GitHub 登录 + 命令历史云同步 MVP）----
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var loggedIn by remember { mutableStateOf(SessionStore.isLoggedIn(context)) }
            var cloudOn by remember { mutableStateOf(MoxshPrefs.cloudSync(context)) }
            var syncMsg by remember { mutableStateOf("未同步") }
            var syncing by remember { mutableStateOf(false) }

            // 登录态可能因 LoginActivity 刚完成而变化：回到本页时刷新
            LaunchedEffect(Unit) { loggedIn = SessionStore.isLoggedIn(context) }

            GlassSurface(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("账号", color = GlassTokens.onGlass)
                        Text(
                            if (loggedIn) "已通过 GitHub 登录" else "未登录——登录后可云同步命令历史",
                            color = GlassTokens.onGlassDim,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    if (loggedIn) {
                        GlassIconButton("退出") {
                            SessionStore.clear(context)
                            UserProfileStore.clear(context)
                            // 邮箱依附于 GitHub 会话：退出即解除绑定
                            EmailBindingStore.clear(context)
                            MoxshPrefs.setCloudSync(context, false)
                            loggedIn = false
                            cloudOn = false
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "在 GitHub 撤销授权",
                            color = GlassTokens.onGlassDim,
                            modifier = Modifier.clickable {
                                GitHubLogin.openUrl(context, GitHubLogin.revokeManagementUrl())
                            },
                        )
                    } else {
                        GlassIconButton("登录") { GitHubLogin.startLogin(context) }
                    }
                }

                Spacer(Modifier.size(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("命令云同步", color = GlassTokens.onGlass)
                        Text(
                            "把终端命令历史同步到你的账号（mox-id 后端）。开启后新输入的命令会加密上传；需先登录。",
                            color = GlassTokens.onGlassDim,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = cloudOn,
                        // 未登录时强制走登录，不允许直接开
                        enabled = loggedIn,
                        onCheckedChange = {
                            cloudOn = it
                            MoxshPrefs.setCloudSync(context, it)
                        },
                    )
                }

                Spacer(Modifier.size(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        syncMsg,
                        color = GlassTokens.onGlassDim,
                        modifier = Modifier.weight(1f),
                    )
                    GlassIconButton("同步") {
                        if (!syncing) {
                            syncing = true
                            syncMsg = "同步中…"
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { SyncClient.syncNow(context) }
                                syncing = false
                                syncMsg = when (r) {
                                    is SyncClient.SyncResult.Ok ->
                                        "同步成功：上传 ${r.pushed} 条，拉回 ${r.pulled} 条"
                                    is SyncClient.SyncResult.Error -> "同步失败：${r.message}"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
