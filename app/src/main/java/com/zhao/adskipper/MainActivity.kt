package com.zhao.adskipper

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLast: TextView
    private lateinit var llWhitelist: LinearLayout
    private lateinit var llRules: LinearLayout
    private lateinit var tvWarn: TextView
    private lateinit var swDebug: Switch
    private lateinit var swLearn: Switch
    private lateinit var swKeep: Switch
    private lateinit var swStable: Switch
    private var ignoreSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        tvLast = findViewById(R.id.tv_last)
        llWhitelist = findViewById(R.id.ll_whitelist)
        llRules = findViewById(R.id.ll_rules)
        tvWarn = findViewById(R.id.tv_warn)
        swDebug = findViewById(R.id.sw_debug)
        swLearn = findViewById(R.id.sw_learn)
        swKeep = findViewById(R.id.sw_keep)
        swStable = findViewById(R.id.sw_stable)

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        findViewById<Button>(R.id.btn_guide).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }

        findViewById<Button>(R.id.btn_add_last).setOnClickListener {
            val pkg = Prefs.lastPackage(this)
            if (pkg.isBlank()) {
                toast("还没有检测到第三方 App，先去打开一个目标 App")
            } else {
                Prefs.addToWhitelist(this, pkg)
                toast("已加入白名单：$pkg")
                refresh()
            }
        }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            Prefs.clearWhitelist(this)
            refresh()
        }

        findViewById<Button>(R.id.btn_clear_rules).setOnClickListener {
            RuleStore.clear(this)
            refresh()
        }

        swDebug.setOnCheckedChangeListener { _, checked ->
            if (ignoreSwitch) return@setOnCheckedChangeListener
            Prefs.setDebugAll(this, checked)
            refresh()
        }

        swLearn.setOnCheckedChangeListener { _, checked ->
            if (ignoreSwitch) return@setOnCheckedChangeListener
            Prefs.setLearning(this, checked)
            if (checked) toast("学习模式已开：去目标 App 里手动点一次要关的按钮")
        }

        swKeep.setOnCheckedChangeListener { _, checked ->
            if (ignoreSwitch) return@setOnCheckedChangeListener
            Prefs.setKeepAlive(this, checked)
            if (checked) startKeepAlive() else stopKeepAlive()
        }

        swStable.setOnCheckedChangeListener { _, checked ->
            if (ignoreSwitch) return@setOnCheckedChangeListener
            if (checked) askStableMode() else applyStableMode(false)
        }
    }

    /** 开启稳定模式前的确认弹窗 */
    private fun askStableMode() {
        AlertDialog.Builder(this)
            .setTitle(R.string.stable_title)
            .setMessage(R.string.stable_message)
            .setPositiveButton(R.string.stable_ok) { _, _ -> applyStableMode(true) }
            .setNegativeButton(R.string.stable_cancel) { _, _ ->
                ignoreSwitch = true
                swStable.isChecked = false
                ignoreSwitch = false
            }
            .setCancelable(true)
            .show()
    }

    private fun applyStableMode(on: Boolean) {
        Prefs.setStable(this, on)

        if (!on) {
            Prefs.setKeepAlive(this, false)
            stopKeepAlive()
            toast("已关闭稳定模式，按普通模式运行")
            refresh()
            return
        }

        // 1. 申请忽略电池优化：这是夜间不被冻死的关键
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        // 2. 无障碍没开就尽量写回去
        if (A11ySelfHeal.canSelfHeal(this) && !A11ySelfHeal.isEnabled(this)) {
            A11ySelfHeal.ensureEnabled(this)
        }

        // 3. 保活常驻
        Prefs.setKeepAlive(this, true)
        startKeepAlive()

        toast("稳定模式已开启")
        refresh()
    }

    private fun startKeepAlive() {
        // 已经在跑就不要再 start 一次：重复启动会让进程状态变化，
        // 某些 ROM 会借机重新评估无障碍绑定，反而把服务弄掉
        if (KeepAliveService.isRunning) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        runCatching {
            val i = Intent(this, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        }
    }

    private fun stopKeepAlive() {
        runCatching { stopService(Intent(this, KeepAliveService::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        if (A11ySelfHeal.canSelfHeal(this) && !A11ySelfHeal.isEnabled(this)) {
            val ok = A11ySelfHeal.ensureEnabled(this)
            android.util.Log.i("AdSkip", "界面触发自愈：服务不在启用列表，写回结果=$ok")
        }
        if (Prefs.isKeepAlive(this)) startKeepAlive()
        refresh()
    }

    private fun refresh() {
        val connectedRecently = AdAccessibilityService.connectedAt > 0 &&
            System.currentTimeMillis() - AdAccessibilityService.connectedAt < 15000
        val enabled = A11ySelfHeal.isEnabled(this) || connectedRecently
        if (enabled) {
            tvStatus.text = getString(R.string.status_on)
            tvStatus.setTextColor(0xFF2E7D32.toInt())
        } else {
            tvStatus.text = getString(R.string.status_off)
            tvStatus.setTextColor(0xFFC62828.toInt())
        }

        val last = Prefs.lastPackage(this)
        tvLast.text = if (last.isBlank()) "最近检测到：暂无" else "最近检测到：$last"

        llWhitelist.removeAllViews()
        val wl = Prefs.whitelist(this).sorted()

        // 白名单空 + 调试关 = 什么都干不了，自动兜底开调试，避免"看起来没反应"
        var autoDebug = false
        if (wl.isEmpty() && !Prefs.isDebugAll(this)) {
            Prefs.setDebugAll(this, true)
            autoDebug = true
        }
        if (wl.isEmpty()) {
            llWhitelist.addView(makeRow("（空）", null))
        } else {
            wl.forEach { pkg -> llWhitelist.addView(makeRow("· $pkg    ✕", pkg)) }
        }

        // 学习记录
        llRules.removeAllViews()
        val rules = RuleStore.load(this)
        if (rules.isEmpty()) {
            llRules.addView(makeRow("（还没有学习记录）", null))
        } else {
            rules.forEach { r ->
                llRules.addView(makeRow("· ${r.pkg.substringAfterLast('.')}  ${r.label()}    ✕") {
                    RuleStore.remove(this, r)
                    toast("已删除这条记录")
                    refresh()
                })
            }
        }

        val debug = Prefs.isDebugAll(this)
        val learn = Prefs.isLearning(this)
        val keep = Prefs.isKeepAlive(this)
        val stable = Prefs.isStable(this)
        ignoreSwitch = true
        swDebug.isChecked = debug
        swLearn.isChecked = learn
        swKeep.isChecked = keep
        swStable.isChecked = stable
        ignoreSwitch = false

        if (autoDebug) {
            tvWarn.visibility = TextView.VISIBLE
            tvWarn.setTextColor(0xFFEF6C00.toInt())
            tvWarn.text = "白名单为空，已自动开启调试模式（处理所有 App）。加好白名单后可以关掉它。"
        } else {
            tvWarn.visibility = TextView.GONE
        }
    }

    private fun makeRow(
        text: String,
        pkg: String? = null,
        onClick: (() -> Unit)? = null
    ): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setPadding(0, 10, 0, 10)
        tv.gravity = Gravity.START
        when {
            onClick != null -> tv.setOnClickListener { onClick() }
            pkg != null -> tv.setOnClickListener {
                Prefs.removeFromWhitelist(this, pkg)
                toast("已移除：$pkg")
                refresh()
            }
        }
        return tv
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
