package com.moxsh.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens

/** 通用玻璃按钮（app 模块内部复用；与 plugin-distro 的同名组件解耦）。 */
@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.05f)
                    filled -> Color.White.copy(alpha = 0.26f)
                    else -> GlassTokens.surfaceTint
                }
            )
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) GlassTokens.onGlass else GlassTokens.onGlassDim, fontSize = 14.sp)
    }
}

/** 玻璃胶囊筛选标签（分类页 / 包管理器用）。 */
@Composable
fun GlassChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Color.White.copy(alpha = 0.26f) else GlassTokens.surfaceTint)
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = GlassTokens.onGlass, fontSize = 12.5.sp)
    }
}

/** 区块标题。 */
@Composable
fun GlassSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = GlassTokens.onGlass,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/**
 * 玻璃风单行输入框（与登录 / 注册页同款的紧凑样式）。
 *
 * @param error 是否处于错误态（边框变暗红提示）。
 */
@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: Boolean = false,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassTokens.surfaceTint)
            .border(
                1.dp,
                if (error) GlassTokens.strokeError else GlassTokens.stroke,
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(color = GlassTokens.onGlass, fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = GlassTokens.onGlassDim, fontSize = 14.sp)
                }
                inner()
            },
        )
    }
}

/**
 * 玻璃风密码输入框：右侧带眼睛图标，可切换明文 / 密文。
 *
 * 采用官方推荐的「尾随可点击图标」模式（androidx BasicSecureTextField 思路，
 * 此处用 BasicTextField + trailing Icon 实现，保持与 [GlassField] 一致的玻璃样式）。
 */
@Composable
fun GlassPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    var show by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassTokens.surfaceTint)
            .border(
                1.dp,
                if (error) GlassTokens.strokeError else GlassTokens.stroke,
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 34.dp),
            singleLine = true,
            textStyle = TextStyle(color = GlassTokens.onGlass, fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = GlassTokens.onGlassDim, fontSize = 14.sp)
                }
                inner()
            },
        )
        Icon(
            if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = if (show) "隐藏密码" else "显示密码",
            tint = GlassTokens.onGlassDim,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(22.dp)
                .clickable { show = !show },
        )
    }
}
