package com.moxsh.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moxsh.ui.theme.MoxshTokens

/**
 * moxsh 统一 UI 组件（借鉴 Podroid 的组件范式：职责单一、参数化清晰、
 * 一律消费 MaterialTheme.colorScheme.* 与 MoxshTokens.*，绝不硬编码颜色）。
 *
 * 与 Glass.kt 的关系：Glass* 是"液态玻璃"材质层（终端卡 / 控制中心 / FAB）；
 * 这里的 Moxsh* 是标准 Material 3 骨架层（顶栏 / 按钮 / 列表行 / 分组标题 /
 * 自适应容器）。两者并存——主壳用本文件走干净规范，玻璃材质在需要"苹果感"
 * 的点缀处叠用，形成"干净骨架 + 玻璃质感"的融合。
 */

// ── 顶栏：Material3 TopAppBar + 1px 分隔线 ──────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoxshTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )
            },
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 1.dp,
        )
    }
}

// ── 按钮：统一 44dp 高、8dp 圆角 ─────────────────────────────────────────────
@Composable
fun MoxshPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(MoxshTokens.Radius.Button),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}

@Composable
fun MoxshGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(MoxshTokens.Radius.Button),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}

@Composable
fun MoxshDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(MoxshTokens.Radius.Button),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}

// ── 列表行：label 左 / value 右 / 1px 分隔线（设置页通用） ───────────────────
@Composable
fun MoxshListRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    trailing: String? = null,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    rightSlot: @Composable (() -> Unit)? = null,
) {
    val rowMod = modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(vertical = MoxshTokens.Spacing.MD)

    Row(
        modifier = rowMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (rightSlot != null) {
            rightSlot()
        } else if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (mono) MoxshTokens.mono() else FontFamily.Default,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (trailing != null) {
                Spacer(Modifier.width(MoxshTokens.Spacing.SM))
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (divider) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 1.dp,
        )
    }
}

// ── 分组小标题 ───────────────────────────────────────────────────────────────
@Composable
fun MoxshSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = MoxshTokens.Spacing.MD, bottom = MoxshTokens.Spacing.XS),
    )
}

// ── 自适应容器：窄屏居中限宽 600，宽屏 900（用 BoxWithConstraints，零新依赖） ─
@Composable
fun AdaptiveContainer(
    modifier: Modifier = Modifier,
    maxWidthDp: Dp = 600.dp,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val isWide = maxWidth > 600.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = maxWidthDp)
                .padding(horizontal = if (isWide) MoxshTokens.XL2 else MoxshTokens.XL),
        ) {
            content()
        }
    }
}
