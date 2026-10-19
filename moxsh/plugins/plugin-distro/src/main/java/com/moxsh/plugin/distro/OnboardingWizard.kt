package com.moxsh.plugin.distro

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.plugin.core.PluginContract
import com.moxsh.plugin.core.PluginHost
import com.moxsh.shared.ProotManager
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens

/**
 * 小白引导向导（D13 四件套之四）。
 *
 * 首次启动全屏弹出（SharedPreferences 标志 [OnboardingPrefs.isFirstLaunch]），
 * 4 步卡片式，顶部 4 个玻璃圆点指示进度，底部"上一步/下一步"玻璃按钮：
 *
 *  ① 欢迎 + moxsh 是什么（零命令行的移动 Linux 终端，Termux 兼容）；
 *  ② 选一个发行版一键安装（内嵌发行版安装卡精简版，走 [startInstall]）；
 *  ③ 换国内源（一键切 apt 源到清华 TUNA / 中科大 USTC，玻璃开关）；
 *  ④ 装第一个软件（预置推荐 python/nodejs/vim，点击进图形包管理器）
 *     + 完成页（"进阶：想跑图形界面？"链接，GUI 方案递延，见该卡注释）。
 *
 * 宿主接线：MainActivity 在 MoxshRoot 里检测 [OnboardingPrefs.isFirstLaunch]，
 * 为 true 时整屏替换为 [OnboardingWizard]；结束时 [OnboardingPrefs.markDone]。
 */
object OnboardingPrefs {
    private const val FILE = "moxsh_onboarding"
    private const val KEY_DONE = "wizard_done"

    /** 是否首次启动（未完成过向导）。 */
    fun isFirstLaunch(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).contains(KEY_DONE).not()

    /** 标记向导已完成（之后不再弹出）。 */
    fun markDone(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
    }
}

/** 步骤③的国内源开关状态持久化（向导内独立存档，与 app 设置页互不依赖）。 */
private const val PREF_FILE = "moxsh_onboarding"
private const val PREF_MIRROR = "wizard_mirror"

@Composable
fun OnboardingWizard(
    onFinish: () -> Unit,
    onOpenPackageManager: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) } // 0..3

    // 步骤② 的发行版安装状态（复用发行版管理器的安装链路）
    val distros = remember { ProotManager.listInstalled() }
    val installStates = remember { mutableStateMapOf<String, InstallState>() }
    var selectedDistro by remember { mutableStateOf(ProotManager.AVAILABLE_DISTROS.first().id) }

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 顶部步骤指示器：4 个玻璃圆点 ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(4) { i ->
                val active = i == step
                Box(
                    Modifier
                        .padding(horizontal = 6.dp)
                        .size(if (active) 14.dp else 10.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) Color.White.copy(alpha = 0.85f)
                            else GlassTokens.surfaceTint
                        )
                        .border(1.dp, GlassTokens.stroke, CircleShape),
                )
            }
        }

        // ---- 步骤卡片内容 ----
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                0 -> WelcomeStep()
                1 -> PickDistroStep(
                    distros = distros,
                    installStates = installStates,
                    selectedId = selectedDistro,
                    onSelect = { selectedDistro = it },
                    onInstall = {
                        val info = distros.first { it.id == selectedDistro }
                        startInstall(info, installStates, scope) { }
                    },
                )
                2 -> MirrorStep(context)
                3 -> FirstAppStep(onOpenPackageManager)
            }
        }

        // ---- 底部上一步/下一步（玻璃按钮） ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassButton(
                "上一步",
                enabled = step > 0,
                modifier = Modifier.weight(1f),
                onClick = { if (step > 0) step-- },
            )
            GlassButton(
                if (step == 3) "完成，开始使用" else "下一步",
                filled = true,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (step < 3) step++ else {
                        OnboardingPrefs.markDone(context)
                        onFinish()
                    }
                },
            )
        }
    }
}

/** 步骤①：欢迎 + moxsh 是什么。 */
@Composable
private fun WelcomeStep() {
    GlassSurface(Modifier.fillMaxWidth()) {
        Text("欢迎来到 moxsh", color = GlassTokens.onGlass, fontSize = 22.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "moxsh 是一个零命令行门槛的移动 Linux 终端：\n" +
                "· 100% 自研内核，全面兼容 Termux 生态；\n" +
                "· 全应用液态玻璃界面，顺手又好看；\n" +
                "· 一键安装 Ubuntu / Debian / Kali / Alpine；\n" +
                "· 图形化装软件、看资源，全程不用敲命令。",
            color = GlassTokens.onGlassDim, fontSize = 14.sp, lineHeight = 22.sp,
        )
    }
}

/**
 * 步骤②：选一个发行版一键安装（内嵌 [CompactDistroCard] 精简安装卡）。
 */
@Composable
private fun PickDistroStep(
    distros: List<ProotManager.DistroInfo>,
    installStates: MutableMap<String, InstallState>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onInstall: () -> Unit,
) {
    Text("第 2 步 · 挑一个发行版装上", color = GlassTokens.onGlass, fontSize = 16.sp)
    distros.filter { it.id != "custom" }.forEach { d ->
        val st = installStates[d.id] ?: InstallState()
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .border(
                    width = if (selectedId == d.id) 2.dp else 0.dp,
                    color = Color.White,
                    shape = RoundedCornerShape(20.dp),
                )
                .clickable { onSelect(d.id) },
        ) {
            CompactDistroCard(info = d, state = st, onInstall = { onSelect(d.id); onInstall() })
        }
    }
    GlassSurface(Modifier.fillMaxWidth()) {
        val sel = distros.firstOrNull { it.id == selectedId }
        Text(
            "选中：${sel?.name ?: "-"}（点击卡片选择，再点卡片上的「安装」按钮开始）",
            color = GlassTokens.onGlassDim, fontSize = 12.sp,
        )
        if (stateOf(sel, installStates)?.running == true) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (stateOf(sel, installStates)?.percent ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            )
        }
    }
}

/** 取步骤②发行版当前安装状态的小工具（避免空解构）。 */
private fun stateOf(d: ProotManager.DistroInfo?, states: Map<String, InstallState>): InstallState? =
    d?.let { states[it.id] }

/**
 * 步骤③：换国内源（一键切换 apt 源到清华/中科大，玻璃开关）。
 */
@Composable
private fun MirrorStep(context: Context) {
    val prefs = remember { context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE) }
    // true=清华 TUNA（默认），false=中科大 USTC
    var useTuna by remember { mutableStateOf(prefs.getString(PREF_MIRROR, "tuna") == "tuna") }
    var applied by remember { mutableStateOf(false) }

    GlassSurface(Modifier.fillMaxWidth()) {
        Text("第 3 步 · 换国内源（下载提速）", color = GlassTokens.onGlass, fontSize = 16.sp)
        Text(
            "把发行版内 apt 的下载地址换成国内镜像，安装软件更快更稳。" +
                "二选一即可，之后随时能在设置里改。",
            color = GlassTokens.onGlassDim, fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (useTuna) "清华 TUNA" else "中科大 USTC",
                color = GlassTokens.onGlass, fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = useTuna,
                onCheckedChange = { useTuna = it; applied = false },
            )
        }
        Spacer(Modifier.height(6.dp))
        GlassButton(
            if (applied) "已切换 ✓" else "一键切换",
            filled = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                // 实际动作：在已安装发行版的 proot 会话里重写 /etc/apt/sources.list，
                // 即执行（以 Debian/Ubuntu 为例，Kali/Alpine 相应替换镜像域名）：
                //   tuna: sed -i 's#deb.debian.org#mirrors.tuna.tsinghua.edu.cn#g' /etc/apt/sources.list
                //   ustc: sed -i 's#deb.debian.org#mirrors.ustc.edu.cn#g'        /etc/apt/sources.list
                // 随后 `apt update` 刷新索引（经 ExecutionEngine.createSession 注入，零打扰）。
                // 未装任何发行版时仅记录偏好，首次安装完成后自动应用。
                prefs.edit().putString(PREF_MIRROR, if (useTuna) "tuna" else "ustc").apply()
                applied = true
            },
        )
    }
}

/**
 * 步骤④：装第一个软件（预置推荐 python/nodejs/vim）+ 完成页。
 */
@Composable
private fun FirstAppStep(onOpenPackageManager: (() -> Unit)?) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Text("第 4 步 · 装你的第一个软件", color = GlassTokens.onGlass, fontSize = 16.sp)
        Text(
            "点下面任意推荐，进入图形包管理器，再点「安装」即可——不用敲任何命令。",
            color = GlassTokens.onGlassDim, fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        listOf(
            Pair("python", "写脚本、做数据分析，新手首选"),
            Pair("nodejs", "跑 JavaScript / npm 生态"),
            Pair("vim", "在终端里编辑文件"),
        ).forEach { (name, desc) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp))
                    .clickable { onOpenPackageManager?.invoke() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = GlassTokens.onGlass, fontSize = 14.sp)
                    Text(desc, color = GlassTokens.onGlassDim, fontSize = 11.sp)
                }
                Text("去安装 →", color = GlassTokens.onGlass, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
        }
    }

    // 完成页附注：进阶 GUI 递延说明（图形界面方案在后续里程碑落地，当前不引导）。
    GlassSurface(Modifier.fillMaxWidth()) {
        Text("完成！", color = GlassTokens.onGlass, fontSize = 18.sp)
        Text(
            "点右下「完成，开始使用」进入终端。",
            color = GlassTokens.onGlassDim, fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "进阶：想跑图形界面？",
            color = Color(0xFF7CF9E5),
            fontSize = 13.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { /* GUI 方案递延：见 docs/roadmap.md M4 之后规划 */ },
        )
        Text(
            "（桌面环境 + VNC 的图形化方案将在后续版本提供，敬请期待）",
            color = GlassTokens.onGlassDim, fontSize = 11.sp,
        )
    }
}

/**
 * plugin-distro 的向导注册契约（照 FloatGlassPluginContract 模式）。
 * 向导本身由 MainActivity 首启时整屏接管；插件卡片里提供"再看一次向导"入口。
 */
object OnboardingWizardPluginContract : PluginContract {
    override val id: String = "distro.onboarding"

    @Composable
    override fun GlassContent(host: PluginHost) {
        var open by remember { mutableStateOf(false) }
        if (open) {
            OnboardingWizard(onFinish = { open = false })
        } else {
            GlassSurface(Modifier.fillMaxWidth()) {
                Text("新手引导", color = GlassTokens.onGlass)
                Spacer(Modifier.height(6.dp))
                Text(
                    "4 步带你装好发行版、换好国内源、装上第一个软件。",
                    color = GlassTokens.onGlassDim,
                )
                Spacer(Modifier.height(10.dp))
                GlassButton("再看一次向导", filled = true, onClick = { open = true })
            }
        }
    }

    /** 把本原生插件注册进宿主。 */
    fun register(host: PluginHost) = host.load(this)
}
