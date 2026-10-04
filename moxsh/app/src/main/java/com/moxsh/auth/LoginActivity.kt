package com.moxsh.auth

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
// R 类生成在 applicationId 包（com.moxsh）下；本文件 package 是 com.moxsh.auth，
// 不同包不会自动解析到父包的 R，必须显式导入。
import com.moxsh.R
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 液态玻璃风登录页（设备流 / Device Flow）。
 * - 打开即请求设备码，展示 user_code 并自动用 Custom Tabs 打开验证页
 * - 后台轮询直到用户完成授权 → 换 token → 拉资料 → 加密存储
 * - 已登录则展示资料与「退出 / 在 GitHub 撤销授权」
 */
class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LoginScreen() }
    }
}

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

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlassSurface {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.mox_login_title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.onGlass,
                        textAlign = TextAlign.Center,
                    )

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
                                // 邮箱依附于 GitHub 会话：退出即解除绑定
                                EmailBindingStore.clear(ctx)
                                profile = null
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
            }

            Spacer(Modifier.height(14.dp))

            // 邮箱注册：必须已登录 GitHub（卡片内会强制该前提并禁用未登录态）
            EmailSignUpCard(loggedIn = profile != null)
        }
    }
}
