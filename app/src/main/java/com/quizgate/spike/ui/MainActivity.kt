package com.quizgate.spike.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.quizgate.spike.R
import com.quizgate.spike.config.SpikeSettings
import com.quizgate.spike.config.TriggerMode
import com.quizgate.spike.databinding.ActivityMainBinding
import com.quizgate.spike.service.GateAccessibilityService
import com.quizgate.spike.service.GateWatchdogService
import com.quizgate.spike.monitor.GateEventStore
import com.quizgate.spike.monitor.GateProbe
import com.quizgate.spike.net.DeviceIdentity
import com.quizgate.spike.net.ReportChannel
import com.quizgate.spike.net.ReportState
import com.quizgate.spike.util.SpikeLog
import com.quizgate.spike.util.TriggerLedger
import com.quizgate.spike.diagnostics.AppDiagnostics
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SpikeSettings
    private val handler = Handler(Looper.getMainLooper())
    private var progressWeight = -1f
    private lateinit var events: GateEventStore
    private lateinit var identity: DeviceIdentity
    private lateinit var reportState: ReportState
    private lateinit var reporter: ReportChannel

    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SpikeLog.attach(applicationContext)
        settings = SpikeSettings(applicationContext)
        events = GateEventStore(File(filesDir, GateEventStore.FILE_NAME))
        identity = DeviceIdentity(applicationContext)
        reportState = ReportState(applicationContext)
        reporter = ReportChannel(applicationContext, events, settings, identity, reportState)
        binding.appInventoryButton.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("应用清单与家长规则")
                .setMessage("同意后读取这台设备当前个人资料下可见的应用名称、包名和游戏分类，同步给已绑定的家长，用于选择受管控游戏；同时启用家长规则。不会读取游戏聊天或画面。应用清单约每 5 分钟刷新，前台状态随心跳上报。")
                .setNegativeButton("暂不授权", null)
                .setPositiveButton("同意并同步") { _, _ ->
                    settings.inventoryConsent = true
                    settings.followServerRule = true
                    getSharedPreferences("app_inventory", MODE_PRIVATE).edit().remove("synced_at").apply()
                    identity.requestRuleRefresh()
                    reporter.tick(force = true)
                }.show()
        }
        // 家长哪怕一次都没进过无障碍设置，只要打开过一次首页，记账就得开始。
        GateWatchdogService.ensure(this)
        askNotificationPermission()

        // 会改变闸门行为的开关一律收进长按才出现的实验台，界面上不留两个键就能放行的洞。
        binding.titleBlock.setOnLongClickListener {
            toggleConsole()
            true
        }
        binding.openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.openOverlayButton.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }
        binding.openNotificationButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
        binding.openNotificationButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
        binding.toggleThresholdButton.setOnClickListener {
            settings.toggleThreshold()
            GateAccessibilityService.instance?.rebuildTracker()
            SpikeLog.record("阈值切到 ${describe(settings.thresholdMs)}，计时重新开始")
            render()
        }
        binding.selfTestButton.setOnClickListener {
            val service = GateAccessibilityService.instance
            if (service == null) {
                SpikeLog.record("无障碍服务没开，无法自检")
            } else {
                service.startSelfTest(SELF_TEST_ROUNDS)
            }
        }
        binding.refreshButton.setOnClickListener { render() }
        binding.diagnosticsButton.setOnClickListener { showDiagnostics() }
        binding.modeButton.setOnClickListener {
            val next = settings.cycleTriggerMode()
            SpikeLog.record("抢屏方式切到 $next")
            render()
        }
        binding.controlButton.setOnClickListener {
            SpikeLog.record("对照组：从本应用前台直接打开答题页")
            startActivity(Intent(this, QuizActivity::class.java).apply {
                putExtra(QuizActivity.EXTRA_PACKAGE, "control")
                putExtra(QuizActivity.EXTRA_ROUND, -1)
            })
        }
        binding.saveServerButton.setOnClickListener {
            settings.serverBaseUrl = binding.serverUrlInput.text.toString()
            // 改地址之后旧令牌属于旧服务端，留着只会一直 401。作废它，下一轮重新登记。
            identity.forgetToken()
            SpikeLog.record("服务端地址设为 ${settings.serverBaseUrl}，重新登记")
            reporter.tick(force = true)
            render()
        }
        binding.reportNowButton.setOnClickListener {
            SpikeLog.record("手动触发一次上报")
            reporter.tick(force = true)
            // 结果在另一条线程上，读不到就先照实说；下一次 render()（1 秒一次）会补上真实状态。
            handler.postDelayed({ render() }, REPORT_PREVIEW_DELAY_MS)
        }
        binding.toggleFollowButton.setOnClickListener {
            settings.followServerRule = !settings.followServerRule
            if (settings.followServerRule) {
                // Earlier heartbeats may have received this version without applying it.
                // Advertising that version again would leave the local threshold unchanged forever.
                identity.requestRuleRefresh()
                reporter.tick(force = true)
            }
            SpikeLog.record(
                if (settings.followServerRule) {
                    "改为跟随服务端阈值，本机阈值将由家长设定覆盖"
                } else {
                    "不再跟随服务端阈值，本机阈值只受上面那个切换键影响"
                }
            )
            render()
        }
        binding.toggleReportingButton.setOnClickListener {
            settings.reportingEnabled = !settings.reportingEnabled
            SpikeLog.record(
                if (settings.reportingEnabled) "联网上报已打开" else "联网上报已关闭：事件照样落盘，只是不发"
            )
            render()
        }
        handleCommand(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleCommand(intent)
    }

    private fun showDiagnostics() {
        val text = TextView(this).apply {
            text = AppDiagnostics.summary() + "\n\n日志仅保存在本机；手动导出可能包含排障用应用包名。"
            textSize = 13f
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("本机日志与性能")
            .setView(android.widget.ScrollView(this).apply { addView(text) })
            .setNegativeButton("关闭", null)
            .setNeutralButton("刷新") { _, _ -> showDiagnostics() }
            .setPositiveButton("导出诊断文件") { _, _ ->
                runCatching {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "quizgate-diagnostics-${System.currentTimeMillis()}.txt")
                    }, REQUEST_DIAGNOSTICS_EXPORT)
                }.onFailure {
                    android.widget.Toast.makeText(this, "当前设备没有可用的文件保存器", android.widget.Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    @Deprecated("Uses the platform document picker without an additional runtime dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DIAGNOSTICS_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        AppDiagnostics.export(applicationContext, uri) { success ->
            if (!isDestroyed) android.widget.Toast.makeText(this,
                if (success) "诊断文件已保存" else "导出未完成，请检查保存位置后重试",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** adb 触发自检用：发完命令再从 adb 打开目标应用，自检就发生在目标应用前台期间。 */
    private fun handleCommand(intent: Intent?) {
        val command = intent?.getStringExtra(EXTRA_COMMAND)
        if (command == null) {
            return
        }
        SpikeLog.record("收到命令 $command")
        when (command) {
            COMMAND_SET_MODE -> {
                val name = intent.getStringExtra(EXTRA_MODE) ?: return
                val mode = runCatching { TriggerMode.valueOf(name) }
                if (mode.isFailure) {
                    SpikeLog.record("抢屏方式无效：$name")
                } else {
                    settings.triggerMode = mode.getOrThrow()
                    SpikeLog.record("抢屏方式设为 ${mode.getOrThrow()}")
                }
                render()
            }
            COMMAND_SELF_TEST -> {
                val rounds = intent.getIntExtra(EXTRA_ROUNDS, SELF_TEST_ROUNDS)
                binding.root.postDelayed({
                    val service = GateAccessibilityService.instance
                    if (service == null) {
                        SpikeLog.record("无障碍服务没开，无法自检")
                    } else {
                        service.startSelfTest(rounds)
                    }
                }, SELF_TEST_START_DELAY_MS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        // 离开就收起，下次进来是一个干净的界面。
        showConsole(false)
        super.onPause()
    }

    private fun toggleConsole() {
        val open = binding.consolePanel.visibility != View.VISIBLE
        showConsole(open)
        SpikeLog.record(if (open) "实验台已展开" else "实验台已收起")
    }

    private fun showConsole(open: Boolean) {
        binding.consolePanel.visibility = if (open) View.VISIBLE else View.GONE
        binding.consoleChevron.setText(
            if (open) R.string.console_chevron_open else R.string.console_chevron_closed
        )
    }

    private fun render() {
        binding.appInventoryButton.text = if (settings.inventoryConsent) "应用清单已授权 · 点击刷新" else "授权应用清单与家长规则"
        val service = GateAccessibilityService.instance
        renderHero(service)
        renderPermissions()
        renderEvents()
        renderLink()
        renderStats()
        renderConsole()
        binding.logText.text = SpikeLog.snapshot().joinToString("\n")
    }

    private fun renderHero(service: GateAccessibilityService?) {
        val thresholdMs = settings.thresholdMs
        val sessionMs = service?.currentSessionMs() ?: 0L
        val foreground = service?.currentForeground().orEmpty()
        val tracking = service != null && foreground.isNotEmpty() && foreground != "无"
        val waitingForParent = service?.waitingForParent() == true

        when {
            service == null -> {
                binding.heroKicker.setText(R.string.home_service_off_state)
                binding.statusText.setText(R.string.home_service_off_title)
                binding.heroBody.setText(R.string.home_service_off_body)
            }
            waitingForParent -> {
                binding.heroKicker.text = "额外延期申请已提交"
                binding.statusText.text = "等家长回复"
                binding.heroBody.text = "等待审批期间可以继续玩，收到家长决定后按本轮规则执行。"
            }
            tracking -> {
                val remainMs = service?.remainingMs() ?: (thresholdMs - sessionMs)
                binding.heroKicker.text = "正在玩「${appLabel(foreground)}」"
                binding.statusText.text = if (remainMs > 0) "还能玩 ${countdown(remainMs)}" else "该答题了"
                binding.heroBody.text = if (remainMs > 0) {
                    "本轮已经玩了 ${describe(sessionMs)}，剩余时间用完后答题。"
                } else {
                    "已经玩了 ${describe(sessionMs)}，这道题答完才能继续。"
                }
            }
            else -> {
                binding.heroKicker.setText(R.string.home_idle_state)
                binding.statusText.setText(R.string.home_idle_title)
                binding.heroBody.text =
                    "只有玩这些应用才计时：${settings.targetPackages.joinToString("、") { appLabel(it) }}。"
            }
        }

        val fraction = if (tracking) (sessionMs.toFloat() / thresholdMs).coerceIn(0f, 1f) else 0f
        // 两段都用权重、加起来正好是 1；用 weightSum 会让总宽超过屏幕。
        if (progressWeight != fraction) {
            progressWeight = fraction
            binding.progressFill.layoutParams = weighted(binding.progressFill.layoutParams, fraction)
            binding.progressGap.layoutParams = weighted(binding.progressGap.layoutParams, 1f - fraction)
        }
        binding.progressLabel.text = if(waitingForParent) {
            "自主延期已用 ${service?.extensionsUsed() ?: 0} 次，等待家长审批"
        } else if (tracking) {
            "本轮已玩 ${describe(sessionMs)} / 初始时长 ${describe(thresholdMs)}"
        } else {
            "阈值 ${describe(thresholdMs)}，本轮还没开始"
        }
    }

    private fun renderPermissions() {
        bindPermission(binding.accessibilityChip, binding.openAccessibilityButton, isAccessibilityEnabled())
        bindPermission(binding.overlayChip, binding.openOverlayButton, GateProbe.overlayGranted(this))
        bindPermission(binding.notificationChip, binding.openNotificationButton, GateProbe.notificationsGranted(this))
    }

    /**
     * 只摊最近四条：家长要的是"动过没有"，不是完整审计日志。
     * 全量台账留在文件里，等上报通道接上再整份发走。
     */
    private fun renderEvents() {
        val recent = events.recent(RECENT_EVENTS)
        if (recent.isEmpty()) {
            binding.gateEventsText.setText(R.string.gate_events_empty)
            binding.gateEventsText.setTextColor(getColor(R.color.ink_soft))
            return
        }
        binding.gateEventsText.text = recent.joinToString("\n") {
            "${eventTime.format(Date(it.atMs))}  ${it.detail}"
        }
        binding.gateEventsText.setTextColor(getColor(R.color.danger))
    }

    /**
     * 绑定码 + 通道的最后一句话，三种状态：还没登记、等着家长抄码、已经对上号。
     *
     * 空绑定码不能说"稍后自动生成"这类含糊话：家长站在这台手机前的那一刻就是要抄这六个字符，
     * 说不清等于让他白等。这里直接给失败原因，因为最常见的原因就是地址填错或服务没起。
     * <p>
     * "已经对上号"这一档是给认领之后准备的：那六个字符是一次性的，服务端认过一次就不再认，
     * 继续把它摆在最显眼的位置，家长会一次次抄一个用掉的码，以为后台出了毛病。
     */
    private fun renderLink() {
        if (identity.bound) {
            binding.bindCodeText.setText(R.string.link_code_bound)
            binding.bindCodeText.setTextColor(getColor(R.color.ok))
            binding.linkBodyText.setText(R.string.link_body_bound)
        } else {
            val code = identity.bindCode
            if (code.isBlank()) {
                binding.bindCodeText.setText(R.string.link_code_placeholder)
                binding.bindCodeText.setTextColor(getColor(R.color.ink_faint))
            } else {
                binding.bindCodeText.text = code
                binding.bindCodeText.setTextColor(getColor(R.color.brand_600))
            }
            binding.linkBodyText.setText(R.string.link_body)
        }
        binding.linkStatusText.text = reportState.describe()
        binding.linkStatusText.setTextColor(
            getColor(if (reportState.lastAttemptFailed()) R.color.warn else R.color.ink_faint)
        )
    }

    /**
     * 33 起通知是运行时权限，不弹这一次，看门狗那条常驻提示就永远发不出去——
     * 而"孩子能看见闸门还在盯着"正是这条通知的唯一作用。
     * 33 以下不用问：那时候装完就能发，被屏蔽是 ROM 的默认设置，交给权限清单那一行去提示。
     */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun bindPermission(chip: TextView, action: View, granted: Boolean) {
        chip.setText(if (granted) R.string.chip_ok else R.string.chip_bad)
        chip.setTextColor(
            getColor(if (granted) R.color.ok else R.color.danger)
        )
        chip.setBackgroundResource(if (granted) R.drawable.bg_chip_ok else R.drawable.bg_chip_bad)
        action.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun renderStats() {
        val results = TriggerLedger.results
        if (results.isEmpty()) {
            binding.statSuccess.setText(R.string.stat_placeholder)
            binding.statAverage.setText(R.string.stat_placeholder)
            binding.statWorst.setText(R.string.stat_placeholder)
            binding.statsNote.setText(R.string.home_stats_empty)
            return
        }
        val success = results.count { it.resumed }
        val latencies = results.filter { it.resumed }.map { it.latencyMs }
        binding.statSuccess.text = "$success/${results.size}"
        binding.statAverage.text = if (latencies.isEmpty()) "—" else "${latencies.average().toLong()} ms"
        binding.statWorst.text = if (latencies.isEmpty()) "—" else "${(latencies.maxOrNull() ?: 0)} ms"
        binding.statsNote.text = "一共试过 ${results.size} 轮，" +
            if (success == results.size) "每次都抢到了屏幕。" else "有 ${results.size - success} 轮没抢到，去实验台看日志。"
    }

    private fun renderConsole() {
        binding.toggleThresholdButton.text = "阈值切换（当前 ${describe(settings.thresholdMs)}）"
        binding.modeButton.text = "抢屏方式（当前 ${settings.triggerMode}）"
        binding.toggleFollowButton.text =
            "跟随服务端阈值（当前 ${if (settings.followServerRule) "跟" else "不跟"}）"
        binding.toggleReportingButton.text =
            "联网上报（当前 ${if (settings.reportingEnabled) "开" else "关"}）"
        // 只在家长没在打字时回填：这一句每秒都执行，无条件 setText 会把刚敲进去的半个地址擦掉。
        if (!binding.serverUrlInput.isFocused) {
            val current = settings.serverBaseUrl
            if (binding.serverUrlInput.text.toString() != current) {
                binding.serverUrlInput.setText(current)
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean = GateProbe.accessibilityEnabled(this)

    private fun weighted(params: ViewGroup.LayoutParams, weight: Float): LinearLayout.LayoutParams {
        val lp = params as LinearLayout.LayoutParams
        lp.weight = weight
        return lp
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    /** 大数读法：30 分钟以内说"几分几秒"，整分钟就省掉秒。 */
    private fun describe(ms: Long): String {
        val seconds = ms / 1000
        if (seconds < 60) {
            return "$seconds 秒"
        }
        val minutes = seconds / 60
        val rest = seconds % 60
        return if (rest == 0L) "$minutes 分钟" else "$minutes 分 $rest 秒"
    }

    /** 倒计时用分:秒，孩子一眼能读完。 */
    private fun countdown(ms: Long): String {
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        const val COMMAND_SELF_TEST = "selftest"
        const val EXTRA_ROUNDS = "rounds"
        const val COMMAND_SET_MODE = "set_mode"
        const val EXTRA_MODE = "mode"

        private const val REFRESH_MS = 1_000L
        private const val REQUEST_NOTIFICATIONS = 41
        private const val REQUEST_DIAGNOSTICS_EXPORT = 42
        private const val SELF_TEST_ROUNDS = 10
        private const val SELF_TEST_START_DELAY_MS = 4_000L
        private const val RECENT_EVENTS = 4

        /** 手动上报之后补一次刷新的间隔。真结果由 1 秒一次的定时刷新兜住，这里只是别让人觉得按了没反应。 */
        private const val REPORT_PREVIEW_DELAY_MS = 1_500L

        /** 掉线记录要跨天看，所以带月日；只带时分的话家长分不清是昨晚还是今早。 */
        private val eventTime = SimpleDateFormat("MM-dd HH:mm", Locale.US)
    }
}
