package com.moxsh.auth

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 液态玻璃风登录页。
 * - 由深链 moxsh://auth.callback 拉起时，自动解析 token 并存入 [SessionStore]
 * - 也可被 App 内「登录」入口直接启动
 */
class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { LoginScreen() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        setContent { LoginScreen() }
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val token = GitHubLogin.parseToken(intent?.data)
        if (token != null) {
            SessionStore.save(this, token)
            Toast.makeText(this, "已使用 GitHub 登录", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun LoginScreen() {
    val ctx = LocalContext.current
    var loggedIn by remember { mutableStateOf(SessionStore.isLoggedIn(ctx)) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            GlassCard {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("mox 账号", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        if (loggedIn) "已登录 · 会话保存在本机加密存储" else "使用 GitHub 登录以同步配置、解锁会员功能",
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    if (loggedIn) {
                        Button(onClick = {
                            SessionStore.clear(ctx)
                            loggedIn = false
                        }) { Text("退出登录") }
                    } else {
                        Button(
                            onClick = { GitHubLogin.start(ctx) },
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("使用 GitHub 登录") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.clip(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(28.dp), content = content)
    }
}
