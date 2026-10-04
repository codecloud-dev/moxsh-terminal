package com.moxsh.auth

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * 强约束（产品要求）：**邮箱注册必须绑定 GitHub 账号**——
 *  - 未登录 GitHub 时整个卡片禁用并提示先去登录；
 *  - 已登录时所有请求都带 GitHub token，由后端以 token 主体作为邮箱归属，
 *    因此本组件自身也不持有任何邮箱授权码（授权码只存在于后端 Worker）。
 *
 * 发送成功后进入倒计时，避免刷邮件。
 */
@Composable
fun EmailSignUpCard(
    loggedIn: Boolean,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var cooldown by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var boundEmail by remember { mutableStateOf(EmailBindingStore.get(ctx)) }

    // 登录态变化时刷新已绑定邮箱
    LaunchedEffect(loggedIn) { boundEmail = EmailBindingStore.get(ctx) }

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

            // ---- 未登录 GitHub：禁用并引导 ----
            if (!loggedIn) {
                Text(
                    stringResource(R.string.mox_email_requires_github),
                    color = GlassTokens.onGlassDim,
                    fontSize = 13.sp,
                )
                return@Column
            }

            // ---- 未配置后端地址：如实告知，不发无意义请求 ----
            if (!AuthConfig.EMAIL_IS_CONFIGURED) {
                Text(
                    stringResource(R.string.mox_email_not_configured),
                    color = GlassTokens.onGlassDim,
                    fontSize = 13.sp,
                )
                return@Column
            }

            // ---- 已绑定：展示 + 可解除 ----
            if (boundEmail != null) {
                Text(
                    stringResource(R.string.mox_email_bound) + " · " + boundEmail,
                    color = GlassTokens.onGlass,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
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
                    email = ""
                    code = ""
                    status = ""
                }
                return@Column
            }

            // ---- 邮箱输入 ----
            GlassField(
                value = email,
                onValueChange = { email = it },
                placeholder = stringResource(R.string.mox_email_hint),
                keyboardType = KeyboardType.Email,
            )

            // ---- 获取验证码 ----
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

            // ---- 验证码输入 ----
            GlassField(
                value = code,
                onValueChange = { input ->
                    // 只保留数字，限长 CODE_LENGTH
                    code = input.filter { it.isDigit() }.take(EmailAuthClient.CODE_LENGTH)
                },
                placeholder = stringResource(R.string.mox_email_code_hint),
                keyboardType = KeyboardType.NumberPassword,
            )

            // ---- 完成绑定 ----
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
                            EmailBindingStore.save(ctx, r.email)
                            boundEmail = r.email
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

            if (status.isNotBlank()) {
                Text(status, color = GlassTokens.onGlassDim, fontSize = 12.5.sp)
            }
        }
    }
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