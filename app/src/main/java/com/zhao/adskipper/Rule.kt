package com.zhao.adskipper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条学习到的规则：某个 App 的某个窗口里，哪个控件是要点的 */
data class Rule(
    val pkg: String,
    val win: String,   // 窗口类名，可为空
    val id: String,    // 资源 id，可为空
    val text: String   // 文本，可为空
) {
    fun label(): String {
        val name = if (text.isNotBlank()) "\"$text\"" else (id.substringAfterLast('/').ifBlank { "?" })
        val winShort = win.substringAfterLast('.').take(18)
        return "$name  @$winShort"
    }
}

object RuleStore {

    private const val SP = "adskip"
    private const val KEY = "rules"

    /** 这些 id 是内容/导航控件，不是关闭按钮，learning 里误学的一律丢弃 */
    private val BAD_ID_REGEX = Regex(
        "comment|like|share|follow|avatar|praise|collect|download|tab|banner|cover|play|clear|delete|search"
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Rule> {
        val raw = sp(ctx).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Rule(
                    o.optString("pkg"),
                    o.optString("win"),
                    o.optString("id"),
                    o.optString("text")
                )
            }
                // 清理历史脏数据：没有 id、文本又很长的，多半是误学的正文/卡片
                .filter { it.id.isNotBlank() || (it.text.isNotBlank() && it.text.length <= 12) }
                // 内容/导航类控件不是关闭按钮，直接丢掉（例如 comment_layout）
                .filter { !BAD_ID_REGEX.containsMatchIn(it.id.substringAfterLast('/').lowercase()) }
                .toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun save(ctx: Context, list: List<Rule>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("pkg", it.pkg)
                    .put("win", it.win)
                    .put("id", it.id)
                    .put("text", it.text)
            )
        }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** 同一个按钮只存一条 */
    fun add(ctx: Context, r: Rule): Boolean {
        val list = load(ctx)
        if (list.any { it.pkg == r.pkg && it.win == r.win && it.id == r.id && it.text == r.text }) {
            return false
        }
        list.add(r)
        save(ctx, list)
        return true
    }

    fun remove(ctx: Context, r: Rule) {
        val list = load(ctx)
        list.removeAll { it.pkg == r.pkg && it.id == r.id && it.text == r.text && it.win == r.win }
        save(ctx, list)
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(KEY).apply()
    }

    fun forPackage(ctx: Context, pkg: String): List<Rule> = load(ctx).filter { it.pkg == pkg }
}
