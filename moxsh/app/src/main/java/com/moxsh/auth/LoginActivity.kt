package com.moxsh.auth

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.moxsh.R
import com.moxsh.auth.EmailAuthClient
import com.moxsh.auth.EmailBindingStore
import com.moxsh.auth.EmailSessionStore
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.widgets.GlassButton
import com.moxsh.ui.widgets.GlassChip
import com.moxsh.ui.widgets.GlassField
import com.moxsh.ui.widgets.GlassPasswordField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 液态玻璃风登录页（GitHub 设备流 / 邮箱密码）。
 *
 * 顶部双 Tab 切换：
 *  - **GitHub**：设备流登录（公开客户端，无需 secret）。
 *  - **邮箱**：用「邮箱 + 密码」独立登录设备（见 [EmailBindingStore]）。
 *    注册页内置于此 Tab 的「创建新账号」折叠区，注册成功即自动登录。
 *
 * 默认 Tab：若本机已存在邮箱账号（已登录或已注册），直接进入邮箱 Tab。
 */
class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LoginScreen() }
    }
}

private enum class Tab { GITHUB, EMAIL }

private sealed interface Phase {
    data object Idle : Phase
    data object Loading : Phase
    data object Waiting : Phase
    data object Done : Phase
    data object Error : Phase
    data object NotConfigured : Phase
}

@Composable
private fun LoginScreen() {
    val ctx = LocalContext.current
    var profile by remember { mutableStateOf(UserProfileStore.get(ctx)) }
    // 已存在邮箱账号则默认进邮箱 Tab，否则 GitHub
    var tab by remember {
        mutableStateOf(
            if (EmailSessionStore.isActive(ctx) || EmailBindingStore.get(ctx) != null) {
                Tab.EMAIL
            } else {
                Tab.GITHUB
            },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlassSurface {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        stringResource(R.string.mox_login_title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.onGlass,
                        textAlign = TextAlign.Center,
                    )

                    // ---- Tab 切换（分段控制）----
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlassChip(
                            stringResource(R.string.mox_login_tab_github),
                            selected = tab == Tab.GITHUB,
                            modifier = Modifier.weight(1f),
                        ) { tab = Tab.GITHUB }
                        GlassChip(
                            stringResource(R.string.mox_login_tab_email),
                            selected = tab == Tab.EMAIL,
                            modifier = Modifier.weight(1f),
                        ) { tab = Tab.EMAIL }
                    }

                    when (tab) {
                        Tab.GITHUB -> GitHubLoginContent(ctx) { profile = it }
                        Tab.EMAIL -> EmailLoginContent(ctx, githubLoggedIn = profile != null)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
        }
    }
}

// ==================================================================
// GitHub 设备流（原逻辑完整保留，仅封装为独立内容）
// ==================================================================

@Composable
private fun GitHubLoginContent(
    ctx: android.content.Context,
    onProfile: (GitHubLogin.GitHubUser?) -> Unit,
) {
    var profile by remember { mutableStateOf(UserProfileStore.get(ctx)) }
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var deviceCode by remember { mutableStateOf<GitHubLogin.DeviceCode?>(null) }
    var status by remember { mutableStateOf("") }

    fun begin() {
        if (!AuthConfig.IS_CONFIGURED) {
            phase = Phase.NotConfigured
            return
        }
        phase = Phase.Loading
        (ctx as ComponentActivity).lifecycleScope.launch {
            try {
                val dc = GitHubLogin.requestDeviceCode()
                deviceCode = dc
                GitHubLogin.openUrl(ctx, dc.verificationUri)
                phase = Phase.Waiting
                status = ctx.getString(R.string.mox_login_device_waiting)
                var interval = dc.interval
                while (phase == Phase.Waiting) {
                    delay((interval * 1000).toLong())
                    when (val r = GitHubLogin.pollOnce(dc.deviceCode)) {
                        is GitHubLogin.PollResult.Success -> {
                            val token = r.token
                            val user = GitHubLogin.fetchUserProfile(token.accessToken)
                            withContext(Dispatchers.IO) {
                                SessionStore.save(ctx, token.accessToken, token.refreshToken)
                                UserProfileStore.save(ctx, user)
                            }
                            profile = user
                            onProfile(user)
                            phase = Phase.Done
                            Toast.makeText(ctx, R.string.mox_login_success, Toast.LENGTH_SHORT).show()
                        }
                        is GitHubLogin.PollResult.Pending ->
                            status = ctx.getString(R.string.mox_login_device_waiting)
                        is GitHubLogin.PollResult.SlowDown -> {
                            interval += 5
                            status = ctx.getString(R.string.mox_login_device_slow)
                        }
                        is GitHubLogin.PollResult.Failed -> {
                            status = r.message
                            phase = Phase.Error
                        }
                    }
                }
            } catch (e: Exception) {
                status = ctx.getString(R.string.mox_login_error_prefix) + (e.message ?: "")
                phase = Phase.Error
            }
        }
    }

    when {
        phase == Phase.NotConfigured -> {
            Text(
                stringResource(R.string.mox_login_device_not_configured),
                color = GlassTokens.onGlassDim,
                textAlign = TextAlign.Center,
            )
            Button(onClick = {
                GitHubLogin.openUrl(ctx, "https://github.com/codecloud-dev/moxsh-terminal#github-登录")
            }) { Text(stringResource(R.string.mox_login_view_config)) }
        }

        profile != null && phase != Phase.Loading -> {
            Text(
                stringResource(R.string.mox_login_logged_in) + " · ${profile!!.login}",
                color = GlassTokens.onGlass,
            )
            Text(
                stringResource(R.string.mox_login_logged_in_desc),
                color = GlassTokens.onGlassDim,
            )
            Button(onClick = {
                SessionStore.clear(ctx)
                UserProfileStore.clear(ctx)
                // 邮箱是独立账号，不随 GitHub 退出而清除
                profile = null
                onProfile(null)
                phase = Phase.Idle
            }) { Text(stringResource(R.string.mox_login_logout)) }
            TextButton(onClick = {
                GitHubLogin.openUrl(ctx, GitHubLogin.revokeManagementUrl())
            }) {
                Text(
                    stringResource(R.string.mox_login_manage_github),
                    color = GlassTokens.onGlassDim,
                )
            }
        }

        phase == Phase.Loading -> {
            CircularProgressIndicator(color = GlassTokens.onGlass)
            Text(stringResource(R.string.mox_login_logging_in), color = GlassTokens.onGlassDim)
        }

        phase == Phase.Waiting -> {
            Text(
                stringResource(R.string.mox_login_device_code_label),
                color = GlassTokens.onGlassDim,
                textAlign = TextAlign.Center,
            )
            Text(
                deviceCode?.userCode ?: "",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = GlassTokens.onGlass,
                textAlign = TextAlign.Center,
            )
            Button(onClick = {
                deviceCode?.let { GitHubLogin.openUrl(ctx, it.verificationUri) }
            }) { Text(stringResource(R.string.mox_login_device_open)) }
            Text(status, color = GlassTokens.onGlassDim, textAlign = TextAlign.Center)
        }

        phase == Phase.Error -> {
            Text(status, color = GlassTokens.onGlassDim, textAlign = TextAlign.Center)
            Button(onClick = { begin() }) { Text(stringResource(R.string.mox_login_retry)) }
        }

        else -> {
            Text(
                stringResource(R.string.mox_login_subtitle_logged_out),
                color = GlassTokens.onGlassDim,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { begin() }) {
                Text(stringResource(R.string.mox_login_button))
            }
        }
    }
}

// ==================================================================
// 邮箱密码登录（独立账号；见 EmailBindingStore / EmailSessionStore）
// ==================================================================

@Composable
private fun EmailLoginContent(
    ctx: android.content.Context,
    githubLoggedIn: Boolean,
) {
    val scope = rememberCoroutineScope()

    var email by remember {
        mutableStateOf(EmailBindingStore.get(ctx)?.let { it } ?: "")
    }
    var password by remember { mutableStateOf("") }
    var emailError by remember { mutableStateOf(false) }
    var pwError by remember { mutableStateOf(false) }
    var loggingIn by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    var activeEmail by remember { mutableStateOf(EmailSessionStore.get(ctx)) }
    var showRegister by remember { mutableStateOf(false) }
    var showForgot by remember { mutableStateOf(false) }

    // 注册/登录态变化后刷新展示
    LaunchedEffect(Unit) {
        activeEmail = EmailSessionStore.get(ctx)
        if (email.isBlank()) email = EmailBindingStore.get(ctx)?.let { it } ?: ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (activeEmail != null) {
            // ---- 已登录：展示身份 + 退出 ----
            Text(
                stringResource(R.string.mox_email_logged_in_as) + " · $activeEmail",
                color = GlassTokens.onGlass,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.mox_email_logged_in_desc),
                color = GlassTokens.onGlassDim,
                fontSize = 12.5.sp,
            )
            GlassButton(
                text = stringResource(R.string.mox_login_logout),
                modifier = Modifier.fillMaxWidth(),
            ) {
                EmailSessionStore.clear(ctx)
                activeEmail = null
                password = ""
                status = ""
            }
        } else {
            // ---- 登录表单 ----
            GlassField(
                value = email,
                onValueChange = {
                    email = it
                    emailError = false
                    status = ""
                },
                placeholder = stringResource(R.string.mox_email_email_hint),
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                error = emailError,
            )
            GlassPasswordField(
                value = password,
                onValueChange = {
                    password = it
                    pwError = false
                    status = ""
                },
                placeholder = stringResource(R.string.mox_email_login_password_hint),
                error = pwError,
            )
            if (status.isNotBlank()) {
                Text(status, color = GlassTokens.strokeError, fontSize = 12.5.sp)
            }
            GlassButton(
                text = if (loggingIn) {
                    stringResource(R.string.mox_email_login_loading)
                } else {
                    stringResource(R.string.mox_email_login_button)
                },
                modifier = Modifier.fillMaxWidth(),
                filled = true,
                enabled = !loggingIn && email.isNotBlank() && password.isNotBlank(),
            ) {
                if (!EmailAuthClient.isEmailLooksValid(email)) {
                    emailError = true
                    return@GlassButton
                }
                if (password.isEmpty()) {
                    pwError = true
                    return@GlassButton
                }
                loggingIn = true
                status = ""
                scope.launch {
                    val target = email.trim().lowercase()
                    val bound = EmailBindingStore.get(ctx)?.lowercase()
                    when {
                        bound == null || bound != target -> {
                            status = ctx.getString(R.string.mox_email_login_wrong)
                        }
                        !EmailBindingStore.hasPassword(ctx) -> {
                            status = ctx.getString(R.string.mox_email_login_no_password)
                        }
                        EmailBindingStore.verifyPassword(ctx, password) -> {
                            EmailSessionStore.set(ctx, EmailBindingStore.get(ctx)!!)
                            activeEmail = EmailBindingStore.get(ctx)
                            password = ""
                            Toast.makeText(
                                ctx,
                                R.string.mox_email_login_success,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        else -> {
                            status = ctx.getString(R.string.mox_email_login_wrong)
                        }
                    }
                    loggingIn = false
                }
            }

            // 辅助行：注册入口 + 忘记密码
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { showRegister = !showRegister }) {
                    Text(
                        stringResource(R.string.mox_email_login_no_account),
                        color = GlassTokens.onGlassDim,
                        fontSize = 12.5.sp,
                    )
                }
                TextButton(onClick = { showForgot = !showForgot }) {
                    Text(
                        stringResource(R.string.mox_email_login_forgot),
                        color = GlassTokens.onGlassDim,
                        fontSize = 12.5.sp,
                    )
                }
            }
            if (showForgot) {
                Text(
                    stringResource(R.string.mox_email_forgot_hint),
                    color = GlassTokens.onGlassDim,
                    fontSize = 12.sp,
                )
            }
        }

        // ---- 注册区（折叠）----
        if (showRegister && activeEmail == null) {
            Spacer(Modifier.height(4.dp))
            EmailSignUpCard(
                loggedIn = githubLoggedIn,
                onRegistered = { registeredEmail ->
                    // 注册成功即建立登录态（本地凭据刚创建，可信）
                    EmailSessionStore.set(ctx, registeredEmail)
                    activeEmail = registeredEmail
                    showRegister = false
                    email = registeredEmail
                    password = ""
                    Toast.makeText(
                        ctx,
                        R.string.mox_email_login_registered,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }
}
