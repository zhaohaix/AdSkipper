package com.zhao.adskipper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * 无障碍服务自愈。
 *
 * 前提：通过 adb 一次性授予 WRITE_SECURE_SETTINGS（见 README / 对话说明）。
 * 有了这个权限，App 就能在自己被系统关掉后，重新把服务写回系统设置里。
 *
 * 没有该权限时整个类静默不做事，不影响正常运行。
 */
object A11ySelfHeal {

    private const val TAG = "AdSkip"

    fun canSelfHeal(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 服务是否在系统的已启用列表里 */
    fun isEnabled(ctx: Context): Boolean {
        // 服务刚连过就当它是好的：避免误判后去写系统设置，
        // 写设置会让系统重新绑定所有无障碍服务，反而造成短暂失效
        val recent = AdAccessibilityService.connectedAt > 0 &&
            System.currentTimeMillis() - AdAccessibilityService.connectedAt < 60_000
        if (recent) return true

        val expected = "${ctx.packageName}/${AdAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getInt(
            ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        )
        if (enabled != 1) return false
        val services = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return services.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** 尝试把服务重新写回系统设置，返回是否成功 */
    fun ensureEnabled(ctx: Context): Boolean {
        if (!canSelfHeal(ctx)) return false
        if (isEnabled(ctx)) return true

        val expected = "${ctx.packageName}/${AdAccessibilityService::class.java.name}"
        val cr = ctx.contentResolver

        return runCatching {
            val current = Settings.Secure.getString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val set = current.split(':')
                .filter { it.isNotBlank() }
                .toMutableSet()
            set.add(expected)

            Settings.Secure.putString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                set.joinToString(":")
            )
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }
}
