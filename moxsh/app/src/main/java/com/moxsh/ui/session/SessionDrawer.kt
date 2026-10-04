package com.moxsh.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.widgets.GlassButton

/** 会话环境类型（决定色点颜色），与官网在线体验原型一致。 */
enum class SessionEnv { CONTAINER, BUILD, NORMAL }

/** 一个终端会话的展示模型（抽屉用）；引擎侧真实会话以 [id] 关联。 */
data class MoxSession(
    val id: Long,
    val name: String,
    val env: SessionEnv,
    val cwd: String,
    val exitCode: Int = 0,
)

private val ENV_COLOR = mapOf(
    SessionEnv.CONTAINER to Color(0xFF0A84FF),
    SessionEnv.BUILD to Color(0xFF30D158),
    SessionEnv.NORMAL to Color(0xFF8A90A0),
)
private val ENV_LABEL = mapOf(
    SessionEnv.CONTAINER to "容器",
    SessionEnv.BUILD to "构建",
    SessionEnv.NORMAL to "本地",
)

/**
 * Termux 式会话抽屉（与官网在线体验原型 1:1 对齐）：
 *  - 环境色点（容器蓝 / 构建绿 / 本地灰）
 *  - 点切换、长按改名（玻璃重命名对话框）
 *  - 退出码非 0 → 名称红色删除线 + 「结束」按钮
 *  - 底部「＋ 新建会话」
 *
 * 会话数据与引擎逻辑由调用方（MoxshRoot）持有并传入，本组件只负责渲染与交互回调。
 */
@Composable
fun SessionDrawer(
    sessions: List<MoxSession>,
    activeId: Long,
    onSwitch: (Long) -> Unit,
    onKill: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<MoxSession?>(null) }

    Box(modifier.fillMaxSize()) {
        // 遮罩
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable { onDismiss() },
        )

        // 抽屉面板
        Column(
            Modifier
                .fillMaxHeight()
                .width(290.dp)
                .background(
                    GlassTokens.surfaceTint,
                    RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
                )
                .border(
                    1.dp,
                    GlassTokens.stroke,
                    RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
                )
                .padding(top = 58.dp, start = 14.dp, end = 14.dp, bottom = 22.dp)
                .clickable { },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("会话", color = GlassTokens.onGlass, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${sessions.size} 个会话", color = GlassTokens.onGlassDim, fontSize = 11.sp)
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(sessions, key = { it.id }) { s ->
                    SessionRow(
                        s = s,
                        active = s.id == activeId,
                        onSwitch = { onSwitch(s.id) },
                        onKill = { onKill(s.id) },
                        onRename = { renaming = s },
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0A84FF))
                    .clickable { onNew() }
                    .padding(13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("＋ 新建会话", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 重命名对话框（玻璃材质，与全应用一致）
        renaming?.let { target ->
            var text by remember(target.id) { mutableStateOf(target.name) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { renaming = null },
                contentAlignment = Alignment.Center,
            ) {
                GlassSurface(
                    Modifier.padding(24.dp).fillMaxWidth().clickable { },
                    tint = GlassTokens.surfaceTint,
                ) {
                    Text("重命名会话", color = GlassTokens.onGlass, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = TextStyle(color = GlassTokens.onGlass, fontSize = 14.sp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        GlassButton("取消") { renaming = null }
                        Spacer(Modifier.width(8.dp))
                        GlassButton("确定", filled = true) {
                            onRename(target.id, text.trim().ifBlank { target.name })
                            renaming = null
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    s: MoxSession,
    active: Boolean,
    onSwitch: () -> Unit,
    onKill: () -> Unit,
    onRename: () -> Unit,
) {
    val dead = s.exitCode != 0
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(if (active) Color.White.copy(alpha = 0.18f) else GlassTokens.surfaceTint)
            .border(
                1.dp,
                if (active) Color(0xFF0A84FF).copy(alpha = 0.5f) else GlassTokens.stroke,
                RoundedCornerShape(15.dp),
            )
            .combinedClickable(
                onClick = onSwitch,
                onLongClick = onRename,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(ENV_COLOR[s.env] ?: GlassTokens.onGlassDim, CircleShape),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.name,
                color = if (dead) Color(0xFFFF6A5E) else GlassTokens.onGlass,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textDecoration = if (dead) TextDecoration.LineThrough else null,
            )
            Text(
                "${ENV_LABEL[s.env]} · ${s.cwd}" + if (dead) " · 退出码 ${s.exitCode}" else "",
                color = GlassTokens.onGlassDim,
                fontSize = 11.sp,
            )
        }
        if (dead) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFF453A).copy(alpha = 0.12f))
                    .clickable { onKill() }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) { Text("结束", color = Color(0xFFFF6A5E), fontSize = 11.sp) }
        } else {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(GlassTokens.surfaceTint)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) { Text(if (active) "当前" else "切换", color = GlassTokens.onGlassDim, fontSize = 10.sp) }
        }
    }
}
