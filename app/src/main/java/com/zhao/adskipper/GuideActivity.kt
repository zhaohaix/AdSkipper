package com.zhao.adskipper

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 各机型开启无障碍 + 防自动关闭 的说明页。
 * 当前机型会排在最前面并标星。
 */
class GuideActivity : AppCompatActivity() {

    private data class Brand(
        val key: String,
        val name: String,
        val a11y: String,
        val keep: List<String>
    )

    private val brands = listOf(
        Brand(
            "vivo", "vivo / iQOO",
            "设置 → 快捷与辅助 → 无障碍 → 已安装的服务 → 跳广告 → 打开",
            listOf(
                "i管家 → 应用管理 → 权限管理 → 自启动 → 允许「跳广告」",
                "设置 → 电池 → 后台耗电管理 → 跳广告 → 允许后台高耗电",
                "打开最近任务，下拉「跳广告」卡片，出现小锁 🔒 表示已锁定"
            )
        ),
        Brand(
            "xiaomi", "小米 / 红米",
            "设置 → 更多设置 → 无障碍 → 已下载的应用 → 跳广告 → 打开",
            listOf(
                "安全中心 → 应用管理 → 权限 → 自启动 → 允许「跳广告」",
                "设置 → 省电与电池 → 应用配置 → 跳广告 → 无限制",
                "最近任务里长按「跳广告」卡片 → 锁定"
            )
        ),
        Brand(
            "huawei", "华为 / 荣耀",
            "设置 → 辅助功能 → 无障碍 → 已安装的应用 → 跳广告 → 打开",
            listOf(
                "设置 → 应用 → 应用启动管理 → 跳广告 → 改为「手动管理」，三个开关全开",
                "设置 → 电池 → 更多电池设置 → 关闭「休眠时保持网络连接」的省电限制",
                "最近任务里下拉锁定"
            )
        ),
        Brand(
            "oppo", "OPPO / 一加 / realme",
            "设置 → 系统设置 → 无障碍 → 已安装的服务 → 跳广告 → 打开",
            listOf(
                "设置 → 应用管理 → 自启动管理 → 允许「跳广告」",
                "设置 → 电池 → 耗电管理 → 跳广告 → 允许后台运行",
                "关闭「应用速冻」中针对跳广告的选项"
            )
        ),
        Brand(
            "samsung", "三星 / 原生 Android",
            "设置 → 无障碍 → 已安装的应用 → 跳广告 → 打开",
            listOf(
                "设置 → 电池 → 后台使用限制 → 从「休眠应用」里移除跳广告",
                "设置 → 应用 → 跳广告 → 电池 → 不受限制"
            )
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        findViewById<Button>(R.id.btn_a11y).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        findViewById<Button>(R.id.btn_appinfo).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
        }

        buildGuide()
    }

    private fun buildGuide() {
        val container = findViewById<LinearLayout>(R.id.ll_guide)
        val maker = Build.MANUFACTURER.lowercase()

        val ordered = brands.sortedBy { if (maker.contains(it.key)) 0 else 1 }

        ordered.forEach { brand ->
            val isCurrent = maker.contains(brand.key)

            container.addView(header(if (isCurrent) "★ ${brand.name}（当前机型）" else brand.name, isCurrent))

            container.addView(body("开启无障碍\n${brand.a11y}"))

            val keep = StringBuilder("防自动关闭\n")
            brand.keep.forEachIndexed { i, s -> keep.append("${i + 1}. $s\n") }
            container.addView(body(keep.toString().trimEnd()))
        }

        // 通用说明
        container.addView(header("所有机型通用", false))
        container.addView(
            body(
                "① Android 13 及以上，侧载安装的 App 默认不允许开无障碍\n" +
                    "解决：设置 → 应用 → 跳广告 → 右上角「⋮」→ 允许受限设置，再回来开无障碍\n\n" +
                    "② 重装 App 后无障碍会被系统摘掉\n" +
                    "解决：装完先打开一次「跳广告」，它会自动写回（需要先执行下面这条 adb 命令）\n" +
                    "adb shell pm grant com.zhao.adskipper android.permission.WRITE_SECURE_SETTINGS\n\n" +
                    "③ 判断服务是不是又被关了\n" +
                    "回到本 App 首页看状态：绿色=正常，红色=被关了，点按钮重开"
            )
        )
    }

    private fun header(text: String, highlight: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 17f
        tv.setPadding(0, 28, 0, 6)
        tv.setTextColor(if (highlight) 0xFF7B1FA2.toInt() else 0xFF222222.toInt())
        tv.setTypeface(null, Typeface.BOLD)
        return tv
    }

    private fun body(text: String): TextView {
        val tv = TextView(this)
        val sp = SpannableString(text)
        tv.text = sp
        tv.textSize = 14f
        tv.setLineSpacing(8f, 1f)
        tv.setTextColor(0xFF555555.toInt())
        tv.gravity = Gravity.START
        return tv
    }
}
