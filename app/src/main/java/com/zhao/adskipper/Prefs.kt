package com.zhao.adskipper

import android.content.Context

/**
 * 本地配置：白名单 + 调试开关 + 最近一次看到的包名。
 * 只存 SharedPreferences，不写外部文件、不联网。
 */
object Prefs {

    private const val NAME = "adskip"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_DEBUG = "debug_all"
    private const val KEY_LAST_PKG = "last_pkg"
    private const val KEY_LEARNING = "learning"
    private const val KEY_KEEPALIVE = "keepalive"
    private const val KEY_STABLE = "stable"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun whitelist(ctx: Context): Set<String> =
        sp(ctx).getStringSet(KEY_WHITELIST, emptySet())?.toSet() ?: emptySet()

    fun addToWhitelist(ctx: Context, pkg: String) {
        val set = whitelist(ctx).toMutableSet()
        set.add(pkg)
        sp(ctx).edit().putStringSet(KEY_WHITELIST, set).apply()
    }

    fun removeFromWhitelist(ctx: Context, pkg: String) {
        val set = whitelist(ctx).toMutableSet()
        set.remove(pkg)
        sp(ctx).edit().putStringSet(KEY_WHITELIST, set).apply()
    }

    fun clearWhitelist(ctx: Context) {
        sp(ctx).edit().remove(KEY_WHITELIST).apply()
    }

    fun isDebugAll(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_DEBUG, true)

    fun setDebugAll(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_DEBUG, value).apply()
    }

    fun lastPackage(ctx: Context): String = sp(ctx).getString(KEY_LAST_PKG, "") ?: ""

    fun setLastPackage(ctx: Context, pkg: String) {
        sp(ctx).edit().putString(KEY_LAST_PKG, pkg).apply()
    }

    fun isLearning(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_LEARNING, false)

    fun setLearning(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_LEARNING, value).apply()
    }

    /** 前台保活服务是否开启，默认开 */
    fun isKeepAlive(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_KEEPALIVE, true)

    fun setKeepAlive(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_KEEPALIVE, value).apply()
    }

    /** 是否应当处理这个包：命白白名单，或调试模式全开 */
    fun shouldHandle(ctx: Context, pkg: String): Boolean =
        isDebugAll(ctx) || whitelist(ctx).contains(pkg)

    /** 稳定模式：无障碍常开 + 保活常驻 + 忽略电池优化，用一点电换稳定 */
    fun isStable(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_STABLE, false)

    fun setStable(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_STABLE, value).apply()
    }
}
