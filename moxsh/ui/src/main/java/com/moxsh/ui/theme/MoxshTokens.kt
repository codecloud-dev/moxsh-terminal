package com.moxsh.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream

/**
 * moxsh 设计 token 单一真源（借鉴 PodroidTokens 的结构）。
 *
 * 屏幕与组件一律从这里取间距 / 圆角 / 字号 / 字体，绝不各处写死数值，
 * 这是视觉统一、可换肤的底层保证。改一处全 App 生效。
 *
 * 字体：UI 用 Inter，终端/等宽用 JetBrains Mono，从 app assets 提取
 * （需把字体放到 moxsh/app/src/main/assets/ui-fonts/ 与 assets/fonts/）。
 * 缺字体文件时自动回退系统字体，不会崩。
 */
object MoxshTokens {

    object Spacing {
        val XS  = 4.dp
        val SM  = 8.dp
        val MD  = 12.dp
        val LG  = 16.dp
        val XL  = 20.dp
        val XL2 = 24.dp
    }

    object Radius {
        val Chip   = 4.dp
        val Button = 8.dp
        val Card   = 12.dp
        val Sheet  = 20.dp
    }

    object TypeSize {
        val Display  = 32.sp
        val Headline = 20.sp
        val Title    = 14.sp
        val Body     = 12.sp
        val Label    = 10.sp
    }

    @Volatile private var uiFamily: FontFamily? = null
    @Volatile private var monoFamily: FontFamily? = null

    /** UI 字体（Inter）。首次调用从 assets 提取并缓存；失败回退系统默认。 */
    fun uiFamily(context: Context): FontFamily {
        uiFamily?.let { return it }
        synchronized(this) {
            uiFamily?.let { return it }
            val fam = try {
                val regular  = extractAsset(context, "ui-fonts/Inter-Regular.ttf")
                val semiBold = extractAsset(context, "ui-fonts/Inter-SemiBold.ttf")
                FontFamily(
                    Font(regular, FontWeight.Normal),
                    Font(semiBold, FontWeight.SemiBold),
                )
            } catch (_: Exception) {
                FontFamily.Default
            }
            uiFamily = fam
            return fam
        }
    }

    /** 等宽字体（JetBrains Mono）。失败回退系统等宽。 */
    fun monoFamily(context: Context): FontFamily {
        monoFamily?.let { return it }
        synchronized(this) {
            monoFamily?.let { return it }
            val fam = try {
                val mono = extractAsset(context, "fonts/JetBrains-Mono.ttf")
                FontFamily(Font(mono, FontWeight.Normal))
            } catch (_: Exception) {
                FontFamily.Monospace
            }
            monoFamily = fam
            return fam
        }
    }

    @Composable
    @ReadOnlyComposable
    fun ui(): FontFamily = uiFamily(LocalContext.current)

    @Composable
    @ReadOnlyComposable
    fun mono(): FontFamily = monoFamily(LocalContext.current)

    /** 原子地从 assets 提取字体到 filesDir（抄 Podroid 的 tmp+rename 防半写）。 */
    private fun extractAsset(context: Context, assetPath: String): File {
        val outFile = File(context.filesDir, assetPath)
        if (outFile.exists() && outFile.length() > 0) return outFile
        outFile.parentFile?.mkdirs()
        val tmpFile = File(outFile.parentFile, outFile.name + ".tmp")
        context.assets.open(assetPath).use { input ->
            FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
        }
        if (!tmpFile.renameTo(outFile)) {
            tmpFile.delete()
            throw java.io.IOException("atomic rename failed: ${tmpFile.name}")
        }
        return outFile
    }
}
