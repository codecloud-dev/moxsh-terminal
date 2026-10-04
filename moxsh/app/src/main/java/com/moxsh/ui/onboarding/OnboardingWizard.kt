package com.moxsh.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.widgets.GlassButton

/**
 * 新手引导（首启 4 步，与官网在线体验原型一致）：
 *  ① 欢迎 → ② 挑发行版 → ③ 换国内源 → ④ 装第一个软件。
 * 覆盖层 + 步骤圆点 + 上一步/下一步。完成后回到终端。
 */
@Composable
fun OnboardingWizard(onFinish: () -> Unit, modifier: Modifier = Modifier) {
    var step by remember { mutableStateOf(0) }
    var distro by remember { mutableStateOf("ubuntu") }
    var mirror by remember { mutableStateOf("tuna") }
    var firstPkg by remember { mutableStateOf("python") }

    val steps = listOf(
        WizardStep(
            title = "欢迎来到 moxsh",
            body = "moxsh 是一个零命令行门槛的移动 Linux 终端：100% 自研内核、全面兼容 Termux 生态、全应用液态玻璃界面，图形化装软件、看资源，全程不用敲命令。",
        ),
        WizardStep(
            title = "挑一个发行版装上",
            options = listOf(
                Option("ubuntu", "Ubuntu", "新手首选"),
                Option("debian", "Debian", ""),
                Option("kali", "Kali", ""),
                Option("alpine", "Alpine", "最轻量"),
            ),
        ),
        WizardStep(
            title = "换国内源（下载提速）",
            options = listOf(
                Option("tuna", "清华 TUNA", ""),
                Option("ustc", "中科大 USTC", ""),
            ),
        ),
        WizardStep(
            title = "装你的第一个软件",
            options = listOf(
                Option("python", "python", "新手首选"),
                Option("nodejs", "nodejs", ""),
                Option("vim", "vim", ""),
            ),
        ),
    )

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 54.dp, start = 18.dp, end = 18.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 步骤圆点
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                steps.indices.forEach { i ->
                    Box(
                        Modifier
                            .height(8.dp)
                            .width(if (i == step) 22.dp else 8.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (i == step) Color(0xFF7CF9E5) else GlassTokens.surfaceTint),
                    )
                    if (i < steps.lastIndex) Spacer(Modifier.width(7.dp))
                }
            }

            GlassSurface(Modifier.fillMaxWidth().weight(1f)) {
                val s = steps[step]
                Column(Modifier.fillMaxWidth()) {
                    Text(s.title, color = GlassTokens.onGlass, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    if (s.body != null) {
                        Text(s.body, color = GlassTokens.onGlassDim, fontSize = 14.sp, lineHeight = 22.sp)
                    }
                    s.options?.forEach { opt ->
                        val selected = when (step) {
                            1 -> distro == opt.id
                            2 -> mirror == opt.id
                            3 -> firstPkg == opt.id
                            else -> false
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) Color.White.copy(alpha = 0.18f) else GlassTokens.surfaceTint)
                                .border(1.5.dp, if (selected) Color(0xFF7CF9E5) else GlassTokens.stroke, RoundedCornerShape(14.dp))
                                .clickable {
                                    when (step) {
                                        1 -> distro = opt.id
                                        2 -> mirror = opt.id
                                        3 -> firstPkg = opt.id
                                    }
                                }
                                .padding(14.dp, 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(opt.label, color = GlassTokens.onGlass, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (opt.note.isNotEmpty()) {
                                Text(opt.note, color = Color(0xFF7CF9E5), fontSize = 11.5.sp)
                            }
                        }
                        Spacer(Modifier.height(9.dp))
                    }
                    if (step == 3) {
                        Text("点「完成，开始使用」进入终端。进阶：想跑图形界面？（桌面环境 + VNC 后续版本提供）", color = GlassTokens.onGlassDim, fontSize = 12.sp)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("上一步", enabled = step > 0) { if (step > 0) step-- }
                GlassButton(if (step < 3) "下一步" else "完成，开始使用", filled = true) {
                    if (step < 3) step++ else onFinish()
                }
            }
        }
    }
}

private data class WizardStep(
    val title: String,
    val body: String? = null,
    val options: List<Option>? = null,
)
private data class Option(val id: String, val label: String, val note: String)
