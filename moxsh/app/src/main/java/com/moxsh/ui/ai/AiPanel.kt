package com.moxsh.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.ui.component.GlassTokens

/** 一条 AI 对话消息。 */
data class AiMsg(val text: String, val mine: Boolean)

/**
 * moxsh AI 面板（上滑跟手 / U 键呼出，与官网在线体验原型一致）。
 * 消息列表 + 输入框；发送后本地回声一条占位「已通过 OpenAI 兼容协议发往模型」回复。
 * 真实模型接入点预留在发送回调（此处保持 UI 闭环，不引入网络依赖）。
 */
@Composable
fun AiPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var messages by remember {
        mutableStateOf(
            listOf(
                AiMsg("你好，我是 moxsh AI。已接入你配置的模型，可以直接用自然语言查容器状态、写脚本、跑命令。", false),
            ),
        )
    }
    val listState = rememberLazyListState()

    Column(
        modifier
            .fillMaxSize()
            .background(GlassTokens.surfaceTint)
            .border(1.dp, GlassTokens.stroke)
            .padding(top = 54.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
    ) {
        // 拖拽手柄
        Box(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(38.dp).height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(GlassTokens.onGlassDim),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(messages, key = { it.text }) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(
                        m.text,
                        color = if (m.mine) Color.White else GlassTokens.onGlass,
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (m.mine) Color(0xFF0A84FF) else GlassTokens.surfaceTint)
                            .padding(11.dp, 14.dp),
                    )
                }
            }
        }

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(GlassTokens.surfaceTint)
                .border(1.dp, GlassTokens.stroke, RoundedCornerShape(18.dp))
                .padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(color = GlassTokens.onGlass, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text("问点什么，或发命令…", color = GlassTokens.onGlassDim, fontSize = 14.sp)
                    }
                    inner()
                },
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0A84FF))
                    .clickable {
                        if (input.isNotBlank()) {
                            val sent = input.trim()
                            input = ""
                            messages = messages +
                                AiMsg(sent, true) +
                                AiMsg("（已通过 OpenAI 兼容协议发往你配置的模型…）", false)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
