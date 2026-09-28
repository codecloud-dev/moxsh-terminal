package com.moxsh.plugin.floating

import android.content.Context
import android.content.SharedPreferences

/**
 * 悬浮窗几何状态持久化（位置 / 尺寸 / 字号）。
 * 落盘到插件私有 SharedPreferences，复现 Termux:Float「不记忆窗口」的痛点。
 */
data class FloatWindowState(
    val x: Int = 120,
    val y: Int = 240,
    val width: Int = 360,
    val height: Int = 280,
    val fontSizeSp: Float = 14f,
)

object WindowStateStore {
    private const val NAME = "moxsh.float.window"
    private const val K_X = "x"
    private const val K_Y = "y"
    private const val K_W = "w"
    private const val K_H = "h"
    private const val K_FS = "fs"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): FloatWindowState {
        val s = sp(ctx)
        return FloatWindowState(
            x = s.getInt(K_X, 120),
            y = s.getInt(K_Y, 240),
            width = s.getInt(K_W, 360),
            height = s.getInt(K_H, 280),
            fontSizeSp = s.getFloat(K_FS, 14f),
        )
    }

    fun save(ctx: Context, state: FloatWindowState) {
        sp(ctx).edit()
            .putInt(K_X, state.x)
            .putInt(K_Y, state.y)
            .putInt(K_W, state.width)
            .putInt(K_H, state.height)
            .putFloat(K_FS, state.fontSizeSp)
            .apply()
    }
}
