package com.zhao.adskipper

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * v7：加入"学习模式"。
 *
 * 学习模式下，你手动点一次要关的按钮，服务会从点击事件里记下它的身份证
 * （包名 + 窗口类名 + 资源 id + 文本），以后遇到同一个按钮就自动点。
 *
 * 匹配优先级：
 * 1. 学习到的规则（最准，用户亲手指过）
 * 2. 文本严格匹配 跳过 / skip
 * 3. 资源 id 含 close / dismiss（面积 < 屏幕 40%）
 */
class AdAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<Runnable>()

    private var lastPackage: String? = null
    private var lastAppPkg: String? = null
    private var scanWindowMs = 8000L
    private var lastClass: String? = null
    private var lastFireAt = 0L
    private var lastClickAt = 0L
    private var lastContentScanAt = 0L
    private var lastLearnAt = 0L
    private var lastScrollAt = 0L
    private var lastTextInputAt = 0L
    private var pausedUntil = 0L
    private val recentClicks = ArrayDeque<Long>()

    private var screenW = 1080
    private var screenH = 2400

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedAt = System.currentTimeMillis()
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        Log.i(TAG, "无障碍服务已连接（v8） 屏幕=${screenW}x${screenH}")

        // 稳定模式：进程重启后顺手把保活服务也拉起来，避免"无障碍在、保活没了"
        if (Prefs.isStable(this) && !KeepAliveService.isRunning) {
            runCatching {
                val i = android.content.Intent(this, KeepAliveService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(i)
                } else {
                    startService(i)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "服务被中断")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // 回到桌面/启动器，说明"离开过 App"，下次进来按冷启动算
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isLauncherLike(pkg)) {
            lastAppPkg = null
        }

        if (isImePackage(pkg)) return
        if (isSystemLike(pkg)) return

        // 便于排查：任何窗口切换都留一行痕，不受白名单影响
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "窗口事件 pkg=$pkg cls=${event.className} 白名单=${Prefs.shouldHandle(this, pkg)}")
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!Prefs.shouldHandle(this, pkg)) return
                onWindowChanged(pkg, event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!Prefs.shouldHandle(this, pkg)) return
                onContentChanged(pkg)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> lastScrollAt = System.currentTimeMillis()
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> lastTextInputAt = System.currentTimeMillis()
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onViewClicked(pkg, event)
        }
    }

    // ---------- 学习模式 ----------

    private fun onViewClicked(pkg: String, event: AccessibilityEvent) {
        if (!Prefs.isLearning(this)) return

        val now = System.currentTimeMillis()
        if (now - lastLearnAt < 400) {
            Log.d(TAG, "学习忽略：两次点击间隔太近")
            return
        }

        val node = event.source ?: return
        val id = node.viewIdResourceName ?: ""
        val text = clean(node.text?.toString())
        val cdesc = clean(node.contentDescription?.toString())
        if (id.isBlank() && text.isBlank() && cdesc.isBlank()) return

        // 底栏标签、屏幕最底部的导航栏，一律不学
        if (cdesc.contains("标签") || text.contains("标签")) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.top > screenH * 0.88f) return

        // 只学"看起来像关闭按钮"的东西：id 像 close，或文字是关闭/跳过类
        // 这样点评论、点赞、商品卡片都不会被学进去
        if (!looksLikeClose(id, text, cdesc)) {
            Log.d(TAG, "学习忽略：不像关闭按钮 -> id=${id.substringAfterLast('/')} text=\"$text\"")
            return
        }
        // 控件必须完整落在屏幕内
        if (rect.left < 0 || rect.top < 0 || rect.right > screenW || rect.bottom > screenH) return

        lastLearnAt = now
        val rule = Rule(pkg, lastClass ?: "", id, text.ifBlank { cdesc })

        // 700ms 后回来看：按钮还在 → 普通点击，丢弃；消失了 → 关闭按钮，记住
        handler.postDelayed({ verifyCandidate(pkg, rule) }, 700)
    }

    private fun looksLikeClose(id: String, text: String, cdesc: String): Boolean {
        val name = id.substringAfterLast('/').lowercase()
        if (name.isNotEmpty() && CLOSE_ID_REGEX.containsMatchIn(name)) return true
        val label = "$text $cdesc"
        return CLOSE_WORD_REGEX.containsMatchIn(label)
    }

    private fun verifyCandidate(pkg: String, rule: Rule) {
        val root = rootInActiveWindow
        val stillThere = root != null && containsNode(root, rule, 0)

        if (stillThere) {
            Log.i(TAG, "学习跳过：点击后节点仍在，判定为普通点击 -> ${rule.label()}")
            return
        }

        val saved = RuleStore.add(this, rule)
        Log.i(
            TAG,
            "学习${if (saved) "成功" else "重复"}: pkg=$pkg win=${rule.win} " +
                "id=${rule.id.substringAfterLast('/')} text=\"${rule.text}\""
        )
        // 不再弹 Toast，只写日志，避免打扰
    }

    /** 在整棵树里找有没有同一个控件（id 优先，其次文本/描述） */
    private fun containsNode(node: AccessibilityNodeInfo?, rule: Rule, depth: Int): Boolean {
        if (node == null || depth > 30) return false

        val nodeId = node.viewIdResourceName ?: ""
        val text = clean(node.text?.toString())
        val cdesc = clean(node.contentDescription?.toString())

        val hit = if (rule.id.isNotBlank()) {
            nodeId == rule.id
        } else {
            rule.text.isNotBlank() && (text == rule.text || cdesc == rule.text)
        }
        if (hit) return true

        for (i in 0 until node.childCount) {
            if (containsNode(node.getChild(i), rule, depth + 1)) return true
        }
        return false
    }

    private fun toastOnMain(msg: String) {
        handler.post { runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
    }

    // ---------- 扫描与点击 ----------

    private fun onWindowChanged(pkg: String, event: AccessibilityEvent) {
        val cls = event.className?.toString() ?: "?"
        val now = System.currentTimeMillis()
        if (pkg == lastPackage && cls == lastClass && now - lastFireAt < 1200) return

        // 从别的 App / 桌面进来算"冷启动"，同一 App 内部跳转算"内部导航"。
        // 广告几乎只出现在冷启动，内部导航只扫很短的时间，省开销也少误判。
        val external = lastAppPkg != pkg
        lastAppPkg = pkg

        lastPackage = pkg
        lastClass = cls
        lastFireAt = now
        scanWindowMs = if (external) 8000L else 3000L
        Prefs.setLastPackage(this, pkg)

        val delays = if (external) EXTERNAL_DELAYS else INTERNAL_DELAYS
        Log.i(TAG, "===== [$pkg] $cls 窗口切换（${if (external) "冷启动" else "内部"}）=====")
        cancelPending()
        delays.forEachIndexed { index, delay ->
            val r = Runnable { scan(pkg, cls, index + 1, dump = index == 0) }
            pending.add(r)
            handler.postDelayed(r, delay)
        }
    }

    private fun onContentChanged(pkg: String) {
        val now = System.currentTimeMillis()
        // 只在本次窗口的"广告观察期"内响应内容变化：
        // 冷启动 8 秒、内部跳转 3 秒，之后一律不扫，刷视频时零开销
        if (now - lastFireAt > scanWindowMs) return
        if (now - lastContentScanAt < 400) return
        lastContentScanAt = now
        scan(pkg, "content", 0, dump = false)
    }

    private fun cancelPending() {
        pending.forEach { handler.removeCallbacks(it) }
        pending.clear()
    }

    private fun scan(pkg: String, cls: String, attempt: Int, dump: Boolean) {
        val now = System.currentTimeMillis()

        if (now - lastClickAt < 1500) return
        if (now < pausedUntil) return
        // 用户正在滑动浏览：让开，别打断
        if (now - lastScrollAt < 700) return
        // 正在打字：键盘弹着，或刚输入过，一律不动
        if (now - lastTextInputAt < 1500) return
        if (isImeVisible()) return
        // 支付/收银台这类窗口，一律不碰
        if (isDangerWindow(cls) || isDangerWindow(lastClass)) return

        val roots = collectRoots(pkg)
        if (roots.isEmpty()) {
            if (attempt > 0) Log.w(TAG, "[$pkg] 第${attempt}次抓取：根节点为空")
            return
        }

        // 焦点在输入框上（正在输入文字），不动
        val focus = runCatching { roots[0].findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focus?.isEditable == true) return

        if (dump) {
            val sb = StringBuilder()
            dumpNode(roots[0], sb, 0, intArrayOf(0))
            logLong("[$pkg] $cls 控件树（${roots.size} 个窗口）", sb.toString())
        }

        val rules = RuleStore.forPackage(this, pkg)
        val ruleHits = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        val skipHits = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        val idHits = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        val adMarker = intArrayOf(0)

        roots.forEach { root ->
            collectTargets(root, rules, ruleHits, skipHits, idHits, adMarker, 0)
        }

        // C：窗口类名像广告，或窗口里出现「广告 / AD / 赞助」角标 → 判定为广告场景
        val adContext = isAdWindow(lastClass) || adMarker[0] == 1
        if (adContext) {
            skipHits.addAll(idHits)
        } else if (idHits.isNotEmpty()) {
            Log.i(TAG, "[$pkg] 第${attempt}次抓取：${idHits.size} 个 id 关闭候选，但不像广告场景，忽略")
        }

        val ordered = ruleHits + skipHits
        if (ordered.isEmpty()) {
            if (attempt > 0) Log.i(TAG, "[$pkg] 第${attempt}次抓取：无目标")
            return
        }

        for ((node, why) in ordered) {
            val clickable = findClickable(node) ?: continue
            val desc = describe(clickable)

            // 熔断：一分钟内点太多次，说明可能陷入循环，停 5 分钟
            if (!allowClick()) {
                pausedUntil = now + 5 * 60_000L
                Log.w(TAG, "[$pkg] 一分钟内点击超过 6 次，熔断 5 分钟（疑似异常循环）")
                return
            }

            val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "[$pkg] 第${attempt}次抓取：[$why] 点击 -> $desc 结果=$ok")
            if (ok) {
                lastClickAt = System.currentTimeMillis()
                recentClicks.addLast(lastClickAt)
                break
            }
        }
    }

    /**
     * D：把当前 App 的所有应用窗口都拿来扫，而不是只看"活动窗口"。
     * 有些广告/弹窗挂在独立窗口上，活动窗口里根本看不到。
     */
    private fun collectRoots(pkg: String): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            windows.forEach { w ->
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
                val r = w.root ?: return@forEach
                if (r.packageName?.toString() != pkg) return@forEach
                list.add(r)
            }
        }
        if (list.isEmpty()) {
            rootInActiveWindow?.let { list.add(it) }
        }
        return list
    }

    private fun allowClick(): Boolean {
        val now = System.currentTimeMillis()
        while (recentClicks.isNotEmpty() && now - recentClicks.first() > 60_000) {
            recentClicks.removeFirst()
        }
        return recentClicks.size < 6
    }

    /** 输入法窗口是否正显示 —— 打字时绝不点击 */
    private fun isImeVisible(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) {
            false
        }
    }

    /** 支付、收银台、订单确认这类窗口，绝对不碰 */
    private fun isDangerWindow(cls: String?): Boolean {
        val name = cls ?: return false
        return DANGER_WINDOW_REGEX.containsMatchIn(name)
    }

    /** 文案里带这些词的节点，一律不当成可点目标 */
    private fun isDangerText(s: String): Boolean {
        if (s.isBlank()) return false
        return DANGER_TEXT_REGEX.containsMatchIn(s)
    }

    private fun collectTargets(
        node: AccessibilityNodeInfo?,
        rules: List<Rule>,
        ruleHits: MutableList<Pair<AccessibilityNodeInfo, String>>,
        skipHits: MutableList<Pair<AccessibilityNodeInfo, String>>,
        idHits: MutableList<Pair<AccessibilityNodeInfo, String>>,
        adMarker: IntArray,
        depth: Int
    ) {
        if (node == null || depth > 30) return

        val text = clean(node.text?.toString())
        val cdesc = clean(node.contentDescription?.toString())
        val nodeId = node.viewIdResourceName ?: ""

        // C：记下这个窗口里有没有「广告」角标
        if (adMarker[0] == 0 && (isAdMarker(text) || isAdMarker(cdesc))) {
            adMarker[0] = 1
        }

        // 支付/确认类文案，不参与任何匹配
        if (isDangerText(text) || isDangerText(cdesc)) {
            for (i in 0 until node.childCount) {
                collectTargets(node.getChild(i), rules, ruleHits, skipHits, idHits, adMarker, depth + 1)
            }
            return
        }

        // 1. 学习到的规则（只认特征明确的：有 id，或短文本）
        val ruleHit = rules.any { r ->
            val hasId = r.id.isNotBlank()
            val hasShortText = r.text.isNotBlank() && r.text.length <= 12
            if (!hasId && !hasShortText) return@any false

            val winOk = r.win.isBlank() || r.win == lastClass
            winOk && ((hasId && r.id == nodeId) ||
                (hasShortText && (r.text == text || r.text == cdesc)))
        }
        if (ruleHit && isReasonableTarget(node)) {
            ruleHits.add(node to "规则")
        }

        // 2/3. 通用规则
        if (!ruleHit) {
            if (matchesSkip(text) || matchesSkip(cdesc)) {
                skipHits.add(node to "文本跳过")
            } else if (matchesCloseId(nodeId) && isReasonableTarget(node)) {
                // 先收着，最后统一由"是不是广告场景"决定要不要点
                idHits.add(node to "id关闭")
            }
        }

        for (i in 0 until node.childCount) {
            collectTargets(node.getChild(i), rules, ruleHits, skipHits, idHits, adMarker, depth + 1)
        }
    }

    /** 「广告」「AD」「赞助」这类角标 */
    private fun isAdMarker(s: String): Boolean {
        if (s.isBlank() || s.length > 6) return false
        return AD_MARKER_REGEX.matches(s)
    }

    private fun matchesSkip(raw: String): Boolean {
        if (raw.isBlank() || raw.length > 12) return false
        return SKIP_REGEX.matches(raw)
    }

    private fun matchesCloseId(id: String?): Boolean {
        val name = id?.substringAfterLast('/')?.lowercase() ?: return false
        if (name.isEmpty()) return false
        // 输入框相关的按钮（清除/搜索/删除）不是弹窗关闭，绝对不能点
        if (name.contains("clear") || name.contains("delete") ||
            name.contains("erase") || name.contains("search")
        ) return false
        if (!CLOSE_ID_REGEX.containsMatchIn(name)) return false
        return !name.contains("container")
    }

    private fun isSmallEnough(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        return r.width() <= screenW * 0.4f && r.height() <= screenH * 0.4f
    }

    /**
     * 规则命中时的额外校验：必须在屏幕内，且不能是视频卡片/大封面那种大块
     * 关闭按钮通常很小，占屏宽不到 30%
     */
    private fun isReasonableTarget(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        if (r.right <= 0 || r.left >= screenW || r.bottom <= 0 || r.top >= screenH) return false
        return r.width() <= screenW * 0.45f && r.height() <= screenH * 0.25f
    }

    private fun findClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        var i = 0
        while (n != null && i < 4) {
            if (n.isClickable) return n
            n = n.parent
            i++
        }
        return null
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val r = Rect()
        node.getBoundsInScreen(r)
        val text = clean(node.text?.toString())
        val desc = clean(node.contentDescription?.toString())
        return "text=\"$text\" desc=\"$desc\" ${node.className?.toString()?.substringAfterLast('.')} " +
            "id=${node.viewIdResourceName?.substringAfterLast('/')} @${r.left},${r.top},${r.right},${r.bottom}"
    }

    /** 只有类名像广告/开屏的窗口，才允许按 id 点关闭 */
    private fun isAdWindow(cls: String?): Boolean {
        val n = cls?.lowercase() ?: return false
        return n.contains("advert") || n.contains("splash") ||
            n.contains("promo") || n.contains("_ad_") || n.endsWith("ad")
    }

    /** 桌面 / 启动器 */
    private fun isLauncherLike(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("launcher") || p.contains("home") || p.endsWith(".homescreen")
    }

    /** 输入法进程：绝不参与任何处理（否则会在键盘窗口里乱点） */
    private fun isImePackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("inputmethod") || p.contains(".ime") || p.contains("keyboard")
    }

    private fun isSystemLike(pkg: String): Boolean {
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (e: Exception) {
            pkg == "android" ||
                pkg.startsWith("com.android.") ||
                pkg.startsWith("com.vivo.") ||
                pkg.startsWith("com.bbk.") ||
                pkg.startsWith("com.mediatek.") ||
                pkg.startsWith("com.qualcomm.")
        }
    }

    private fun clean(raw: String?): String =
        raw?.replace(Regex("\\s+"), " ")?.trim() ?: ""

    private fun dumpNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, count: IntArray) {
        if (node == null || depth > 18 || count[0] > 100) return
        count[0]++

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString()?.replace("\n", " ")?.take(30)
        val desc = node.contentDescription?.toString()?.replace("\n", " ")?.take(30)

        sb.append("  ".repeat(depth))
            .append(node.className?.toString()?.substringAfterLast('.') ?: "?")
            .append(" | t=").append(text ?: "-")
            .append(" | d=").append(desc ?: "-")
            .append(" | c=").append(if (node.isClickable) 1 else 0)
            .append(" | id=").append(node.viewIdResourceName?.substringAfterLast('/') ?: "-")
            .append(" | @").append(rect.left).append(',').append(rect.top)
            .append(',').append(rect.right).append(',').append(rect.bottom)
            .append('\n')

        for (i in 0 until node.childCount) {
            dumpNode(node.getChild(i), sb, depth + 1, count)
        }
    }

    private fun logLong(header: String, body: String) {
        Log.i(TAG, "--- $header ---")
        val chunk = 3000
        var i = 0
        while (i < body.length) {
            val end = minOf(i + chunk, body.length)
            Log.i(TAG, body.substring(i, end))
            i = end
        }
        Log.i(TAG, "--- END ${body.length} chars ---")
    }

    override fun onDestroy() {
        connectedAt = 0L
        Log.w(TAG, "无障碍服务被销毁（可能是系统解绑或进程被杀）")
        cancelPending()
        super.onDestroy()
    }

    companion object {
        const val TAG = "AdSkip"

        /** 最近一次连接成功的时间，用来在界面上判断服务是不是真的活着 */
        @Volatile
        var connectedAt: Long = 0L
        /** 冷启动（从桌面/别的 App 进来）：广告主要出现在这里，扫得密一点 */
        private val EXTERNAL_DELAYS = longArrayOf(
            0L, 120L, 260L, 450L, 700L, 1000L, 1400L, 1900L, 2600L, 3500L, 4500L
        )

        /** 同一 App 内部跳转：只象征性看一眼，别打扰正常使用 */
        private val INTERNAL_DELAYS = longArrayOf(
            0L, 200L, 500L, 900L, 1500L
        )
        private val SKIP_REGEX = Regex(
            "^(跳过|skip)(\\s*\\d{1,2}\\s*[s秒]?)?(\\s*广告)?$",
            RegexOption.IGNORE_CASE
        )
        private val CLOSE_ID_REGEX = Regex("(^|_)(close|dismiss|cancel)($|_)")

        /** 广告角标：法规要求标注，是判断"这是广告"最硬的信号 */
        private val AD_MARKER_REGEX = Regex(
            "^(广告|ad|赞助|广告推广|sponsored)$",
            RegexOption.IGNORE_CASE
        )

        /** 学习模式下，只有文案像"关闭"的才考虑记 */
        private val CLOSE_WORD_REGEX = Regex(
            "关闭|跳过|skip|不再提示|关闭广告|跳过广告",
            RegexOption.IGNORE_CASE
        )

        /** 这些窗口类名出现时，整个窗口都不动 */
        private val DANGER_WINDOW_REGEX = Regex(
            "pay|cashier|wallet|orderconfirm|checkout|password",
            RegexOption.IGNORE_CASE
        )

        /** 这些词出现时，该节点不参与匹配 */
        private val DANGER_TEXT_REGEX = Regex(
            "支付|付款|收银|确认支付|立即支付|输入密码|验证码|转账|余额|免密|指纹支付|面容支付|开通会员|自动续费" +
                "|清除|清空|删除|查询|搜索"
        )
    }
}
