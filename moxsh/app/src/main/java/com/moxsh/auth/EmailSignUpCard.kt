package com.moxsh.auth

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.R
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.widgets.GlassButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 邮箱注册 / 绑定卡片（液态玻璃风）。
 *
 * ## 不再强制 GitHub 登录
 *
 * 主路径是**邮箱 + 密码**独立注册，任何人都能用，无需登录 GitHub：
 *
 * ```
 * 填邮箱 + 设密码（≥8 位） → 本地派生存储（PBKDF2） → 账号创建完成
 * ```
 *
 * GitHub 已验证邮箱降级为**可选的免密快捷方式**（仅当已登录时显示）：
 * 一键把 GitHub verified 邮箱填入并标记来源，省去记忆密码，但非必须。
 * 验证码路径（需登录 + 后端已部署）保留为折叠的高级选项。
 *
 * 因此本组件自身不持有任何邮件授权码（授权码只可能存在于后端 Worker 的环境变量）。
 */
@Composable
fun EmailSignUpCard(
    loggedIn: Boolean,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pwError by remember { mutableStateOf(false) }
    var registering by remember { mutableStateOf(false) }

    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var cooldown by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }

    var boundEmail by remember { mutableStateOf(EmailBindingStore.get(ctx)) }
    var src by remember { mutableStateOf(EmailBindingStore.source(ctx)) }

    // ---- 路径 A 的状态（GitHub 已验证邮箱，折叠） ----
    var showGh by remember { mutableStateOf(false) }
    var ghLoading by remember { mutableStateOf(false) }
    var ghEmails by remember { mutableStateOf<List<GitHubVerifiedEmail.VerifiedEmail>>(emptyList()) }
    var ghNone by remember { mutableStateOf(false) }

    // ---- 路径 B 的状态（验证码，折叠） ----
    var showAlt by remember { mutableStateOf(false) }

    // 登录态变化时刷新已绑定邮箱与来源
    LaunchedEffect(loggedIn) {
        boundEmail = EmailBindingStore.get(ctx)
        src = EmailBindingStore.source(ctx)
    }

    // 倒计时
    LaunchedEffect(cooldown) {
        while (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    GlassSurface(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.mox_email_title),
                color = GlassTokens.onGlass,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.mox_email_desc),
                color = GlassTokens.onGlassDim,
                fontSize = 12.5.sp,
            )

            // ============== 已绑定：展示 + 可解除 ==============
            if (boundEmail != null) {
                Text(
                    stringResource(R.string.mox_email_bound) + " · " + boundEmail,
                    color = GlassTokens.onGlass,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(
                        when (src) {
                            EmailBindingStore.Source.GITHUB -> R.string.mox_email_gh_badge
                            EmailBindingStore.Source.PASSWORD -> R.string.mox_email_password_badge
                            else -> R.string.mox_email_code_badge
                        },
                    ),
                    color = GlassTokens.onGlassDim,
                    fontSize = 12.sp,
                )
                Text(
                    stringResource(R.string.mox_email_bound_desc),
                    color = GlassTokens.onGlassDim,
                    fontSize = 12.5.sp,
                )
                GlassButton(
                    text = stringResource(R.string.mox_email_unbind),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    EmailBindingStore.clear(ctx)
                    boundEmail = null
                    src = EmailBindingStore.Source.PASSWORD
                    email = ""
                    password = ""
                    code = ""
                    status = ""
                }
                return@Column
            }

            // ============== 主路径：邮箱 + 密码（始终可用，无需 GitHub） ==============
            GlassField(
                value = email,
                onValueChange = { email = it },
                placeholder = stringResource(R.string.mox_email_email_hint),
                keyboardType = KeyboardType.Email,
            )
            GlassField(
                value = password,
                onValueChange = {
                    password = it
                    pwError = false
                },
                placeholder = stringResource(R.string.mox_email_password_hint),
                keyboardType = KeyboardType.Password,
            )
            if (pwError) {
                Text(
                    stringResource(R.string.mox_email_password_short),
                    color = GlassTokens.onGlassDim,
                    fontSize = 12.sp,
                )
            }
            GlassButton(
                text = if (registering) {
                    stringResource(R.string.mox_email_register_loading)
                } else {
                    stringResource(R.string.mox_email_register)
                },
                modifier = Modifier.fillMaxWidth(),
                filled = true,
                enabled = !registering && email.isNotBlank() && password.length >= 8,
            ) {
                if (!EmailAuthClient.isEmailLooksValid(email)) {
                    status = stringResource(R.string.mox_email_invalid)
                    return@GlassButton
                }
                if (password.length < 8) {
                    pwError = true
                    return@GlassButton
                }
                registering = true
                status = ""
                scope.launch {
                    val salt = EmailBindingStore.newSaltB64()
                    val hash = EmailBindingStore.hashPassword(password, salt)
                    EmailBindingStore.save(
                        ctx,
                        email.trim(),
                        EmailBindingStore.Source.PASSWORD,
                        passwordHashB64 = hash,
                        saltB64 = salt,
                    )
                    boundEmail = email.trim()
                    src = EmailBindingStore.Source.PASSWORD
                    email = ""
                    password = ""
                    registering = false
                    Toast.makeText(ctx, R.string.mox_email_registered, Toast.LENGTH_SHORT).show()
                }
            }

            // ============== 路径 A：GitHub 已验证邮箱（仅登录后，折叠） ==============
            if (loggedIn) {
                TextButtonish(
                    label = stringResource(R.string.mox_email_fast_title),
                    onClick = { showGh = !showGh },
                )
                if (showGh) {
                    Text(
                        stringResource(R.string.mox_email_fast_desc),
                        color = GlassTokens.onGlassDim,
                        fontSize = 12.5.sp,
                    )
                    when {
                        ghLoading -> Text(
                            stringResource(R.string.mox_email_fast_loading),
                            color = GlassTokens.onGlassDim,
                            fontSize = 13.sp,
                        )
                        ghNone -> {
                            Text(
                                stringResource(R.string.mox_email_fast_none),
                                color = GlassTokens.onGlassDim,
                                fontSize = 13.sp,
                            )
                            GlassButton(
                                text = stringResource(R.string.mox_email_fast_add),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                GitHubLogin.openUrl(ctx, GITHUB_EMAIL_SETTINGS)
                            }
                        }
                        else -> {
                            ghEmails.forEach { candidate ->
                                GhEmailRow(
                                    label = candidate.label,
                                    onPick = {
                                        val token = SessionStore.get(ctx).orEmpty()
                                        ghLoading = true
                                        scope.launch {
                                            when (val r = GitHubVerifiedEmail.fetch(token)) {
                                                is GitHubVerifiedEmail.Result.Ok -> {
                                                    EmailBindingStore.save(
                                                        ctx,
                                                        candidate.email,
                                                        EmailBindingStore.Source.GITHUB,
                                                    )
                                                    boundEmail = candidate.email
                                                    src = EmailBindingStore.Source.GITHUB
                                                    status = ""
                                                    showGh = false
                                                    Toast.makeText(
                                                        ctx,
                                                        R.string.mox_email_success,
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                                is GitHubVerifiedEmail.Result.None -> ghNone = true
                                                is GitHubVerifiedEmail.Result.Failed -> status = r.reason
                                            }
                                            ghLoading = false
                                        }
                                    },
                                )
                            }
                            TextButtonish(
                                label = stringResource(R.string.mox_email_gh_settings),
                                onClick = { GitHubLogin.openUrl(ctx, GITHUB_EMAIL_SETTINGS) },
                            )
                        }
                    }
                    // 首次展开时拉一次 GitHub 邮箱
                    LaunchedEffect(showGh) {
                        if (showGh && ghEmails.isEmpty() && !ghNone && !ghLoading) {
                            ghLoading = true
                            val token = SessionStore.get(ctx).orEmpty()
                            when (val r = GitHubVerifiedEmail.fetch(token)) {
                                is GitHubVerifiedEmail.Result.Ok -> ghEmails = r.emails
                                is GitHubVerifiedEmail.Result.None -> ghNone = true
                                is GitHubVerifiedEmail.Result.Failed -> status = r.reason
                            }
                            ghLoading = false
                        }
                    }
                }
            }

            // ============== 路径 B：其他邮箱（仅登录 + 后端已配置，折叠） ==============
            if (loggedIn && AuthConfig.EMAIL_IS_CONFIGURED) {
                TextButtonish(
                    label = stringResource(R.string.mox_email_alt_title),
                    onClick = { showAlt = !showAlt },
                )
                if (showAlt) {
                    Text(
                        stringResource(R.string.mox_email_alt_desc),
                        color = GlassTokens.onGlassDim,
                        fontSize = 12.sp,
                    )
                    GlassField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = stringResource(R.string.mox_email_hint),
                        keyboardType = KeyboardType.Email,
                    )
                    GlassButton(
                        text = when {
                            sending -> stringResource(R.string.mox_email_sending)
                            cooldown > 0 -> stringResource(R.string.mox_email_resend, cooldown)
                            else -> stringResource(R.string.mox_email_send_code)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        filled = true,
                        enabled = !sending && cooldown <= 0 && email.isNotBlank(),
                    ) {
                        sending = true
                        status = ""
                        val token = SessionStore.get(ctx).orEmpty()
                        scope.launch {
                            when (val r = EmailAuthClient.sendCode(email, token)) {
                                is EmailAuthClient.SendResult.Ok -> {
                                    cooldown = r.cooldownSec.takeIf { it > 0 }
                                        ?: EmailAuthClient.RESEND_COOLDOWN_SEC
                                    status = ctx.getString(R.string.mox_email_code_sent)
                                }
                                is EmailAuthClient.SendResult.TooFrequent -> {
                                    cooldown = r.retryAfterSec
                                    status = ctx.getString(R.string.mox_email_resend, r.retryAfterSec)
                                }
                                is EmailAuthClient.SendResult.Failed -> status = r.reason
                            }
                            sending = false
                        }
                    }
                    GlassField(
                        value = code,
                        onValueChange = { input ->
                            code = input.filter { it.isDigit() }.take(EmailAuthClient.CODE_LENGTH)
                        },
                        placeholder = stringResource(R.string.mox_email_code_hint),
                        keyboardType = KeyboardType.NumberPassword,
                    )
                    GlassButton(
                        text = if (verifying) {
                            stringResource(R.string.mox_email_verifying)
                        } else {
                            stringResource(R.string.mox_email_submit)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !verifying
                            && email.isNotBlank()
                            && code.length == EmailAuthClient.CODE_LENGTH,
                    ) {
                        verifying = true
                        status = ""
                        val token = SessionStore.get(ctx).orEmpty()
                        scope.launch {
                            when (val r = EmailAuthClient.verify(email, code, token)) {
                                is EmailAuthClient.VerifyResult.Ok -> {
                                    EmailBindingStore.save(ctx, r.email, EmailBindingStore.Source.CODE)
                                    boundEmail = r.email
                                    src = EmailBindingStore.Source.CODE
                                    email = ""
                                    code = ""
                                    status = ctx.getString(R.string.mox_email_success)
                                    Toast.makeText(
                                        ctx,
                                        R.string.mox_email_success,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                is EmailAuthClient.VerifyResult.Failed -> status = r.reason
                            }
                            verifying = false
                        }
                    }
                }
            } else if (loggedIn && !AuthConfig.EMAIL_IS_CONFIGURED) {
                Text(
                    stringResource(R.string.mox_email_not_configured),
                    color = GlassTokens.onGlassDim,
                    fontSize = 12.sp,
                )
            }

            if (status.isNotBlank()) {
                Text(status, color = GlassTokens.onGlassDim, fontSize = 12.5.sp)
            }
        }
    }
}

/** GitHub 邮箱管理页（用户在 GitHub 侧添加 / 验证邮箱）。 */
private const val GITHUB_EMAIL_SETTINGS = "https://github.com/settings/emails"

/** 一个可选的 GitHub 已验证邮箱行。 */
@Composable
private fun GhEmailRow(label: String, onPick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlassTokens.surfaceTint)
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp))
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = GlassTokens.onGlass,
                fontSize = 13.5.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.mox_email_pick),
                color = GlassTokens.onGlassDim,
                fontSize = 12.5.sp,
            )
        }
    }
}

/** 轻量文字按钮（用于折叠区与外链入口）。 */
@Composable
private fun TextButtonish(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = GlassTokens.onGlassDim,
        fontSize = 12.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

/** 玻璃风单行输入框（与终端/AI 面板同款的紧凑样式）。 */
@Composable
private fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassTokens.surfaceTint)
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(14.dp))
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
