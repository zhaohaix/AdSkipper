package com.zhao.adskipper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 应用更新后自动把无障碍服务写回系统设置。
 * 前提是已经通过 adb 授予 WRITE_SECURE_SETTINGS，否则什么都不做。
 *
 * 开发时每次重新安装 APK，系统会把无障碍服务从启用列表里摘掉，
 * 这个广播能在更新后立刻补回来，省得每次手动去设置里点。
 */
class SelfHealReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        if (!A11ySelfHeal.canSelfHeal(context)) return

        val ok = A11ySelfHeal.ensureEnabled(context)
        Log.i("AdSkip", "自愈触发($action) 结果=$ok")
    }
}
