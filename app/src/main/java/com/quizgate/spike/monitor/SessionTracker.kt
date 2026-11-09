package com.quizgate.spike.monitor

/**
 * 娱乐会话的连续时长累计与触发判定，纯 JVM 逻辑。
 *
 * 口径：目标应用之间可以互相切换（抖音切游戏算同一段），离开全部目标应用不足宽限期
 * 仍算连续；游戏周期在熄屏或离开时暂停且保留次数，答对后才开始下一轮。
 *
 * 两个时钟分工：单调时钟只做**增量**，绝对时间只用来判断快照有多旧、以及识别重启。
 * 状态走 [SessionStore] 落盘，因此进程被杀或服务重绑之后累计时长不会归零——
 * 否则"重启手机"就成了比卸载便宜得多的绕过路径。
 */
class SessionTracker(
    private val targetPackages: Set<String>,
    private val thresholdMs: Long,
    private val graceMs: Long = DEFAULT_GRACE_MS,
    private val maxStepMs: Long = DEFAULT_MAX_STEP_MS,
    private val monotonicMs: () -> Long,
    private val wallMs: () -> Long = monotonicMs,
    private val store: SessionStore? = null,
    private val report: (String) -> Unit = {},
    private val recordPlayed: (String, Long, Long) -> Unit = { _, _, _ -> },
    private val ruleProvider: (() -> CycleRule)? = null
) {

    data class Trigger(val packageUnderQuota: String, val accumulatedMs: Long)

    private var foregroundPackage: String? = null
    private var awaySinceMs: Long? = null
    private var accumulatedMs = 0L
    private var activeRule: CycleRule? = null
    private var extendedMs = 0L
    var extensionsUsed: Int = 0
        private set
    private var gameCycle = false
    var cycleId: String = ""
        private set
    var parentExtension: ParentExtension? = null
        private set
    val awaitingQuiz: Boolean get() = awaitingSinceMs != null
    val hasGameCycle: Boolean get() = gameCycle
    val waitingForParent: Boolean get() = parentExtension?.stage == ParentExtension.Stage.PENDING
    val canRequestParent: Boolean get() = warningVisible && extensionsUsed >= maxExtensions &&
        (parentExtension == null || parentExtension?.stage == ParentExtension.Stage.FAILED)
    private fun defaultRule() = CycleRule(thresholdMs, targetPackages, graceMs)
    private fun currentRule() = activeRule ?: ruleProvider?.invoke() ?: defaultRule()
    val appliedRuleVersion: Long get() = activeRule?.version ?: 0L
    val remainingMs: Long get() = (currentRule().thresholdMs + extendedMs - accumulatedMs).coerceAtLeast(0)
    val extensionMinutes: Int get() = (currentRule().extensionMs / 60_000).toInt()
    val maxExtensions: Int get() = currentRule().maxExtensions
    val isGameForeground: Boolean get() = currentRule().isGame(foregroundPackage)
    val warningVisible: Boolean get() = isGameForeground && awaitingSinceMs == null &&
        (waitingForParent || (remainingMs > 0 && remainingMs <= currentRule().warningMs))
    val gameState: String get() = when {
        awaitingSinceMs != null -> "答题中"
        waitingForParent -> "等待家长审批，可继续玩"
        warningVisible -> "倒计时"
        isGameForeground -> "游戏中"
        gameCycle -> "已暂停，返回后继续"
        else -> "未开始"
    }

    /** Deduct and persist before updating the UI; repeat taps cannot create extra time. */
    fun extend(): Boolean {
        advanceTo(monotonicMs())
        if (!warningVisible || extensionsUsed >= currentRule().maxExtensions) return false
        extensionsUsed++
        extendedMs += currentRule().extensionMs
        if (!persist()) {
            extensionsUsed--
            extendedMs -= currentRule().extensionMs
            return false
        }
        return true
    }

    fun beginParentRequest(): Boolean {
        advanceTo(monotonicMs())
        if (!canRequestParent) return false
        val before = parentExtension
        parentExtension = ParentExtension(before?.requestId ?: "", ParentExtension.Stage.SENDING,
            before?.packageName ?: foregroundPackage.orEmpty())
        if (!persist()) { parentExtension = before; return false }
        return true
    }

    fun parentRequestFailed(cycle: String) {
        if (cycle != cycleId || parentExtension?.stage != ParentExtension.Stage.SENDING) return
        val before = parentExtension
        parentExtension = before?.copy(stage = ParentExtension.Stage.FAILED)
        if (!persist()) parentExtension = before
    }

    /** Only a confirmed server PENDING permits waiting. Persist before releasing a quiz or adding time. */
    fun receiveParentDecision(cycle: String, requestId: String, stage: ParentExtension.Stage, minutes: Int): Boolean {
        val before = parentExtension ?: return false
        if (cycle != cycleId || requestId.isBlank() || minutes != extensionMinutes || minutes !in 1..15) return false
        if (before.requestId.isNotEmpty() && before.requestId != requestId) return false
        if (before.stage in setOf(ParentExtension.Stage.GRANTED, ParentExtension.Stage.REJECTED, ParentExtension.Stage.CLOSED)) return false
        if (before.stage == stage && before.requestId == requestId) return false
        if (stage !in setOf(ParentExtension.Stage.PENDING, ParentExtension.Stage.GRANTED, ParentExtension.Stage.REJECTED, ParentExtension.Stage.CLOSED)) return false
        advanceTo(monotonicMs())
        val oldExtended = extendedMs
        val oldAwaiting = awaitingSinceMs
        parentExtension = ParentExtension(requestId, stage, before.packageName)
        if (stage == ParentExtension.Stage.GRANTED) {
            // Preserve unused time; after the old deadline, give the full approved duration from now.
            extendedMs += (accumulatedMs - currentRule().thresholdMs - extendedMs).coerceAtLeast(0) + currentRule().extensionMs
        }
        if (awaitingGame && stage in setOf(ParentExtension.Stage.PENDING, ParentExtension.Stage.GRANTED)) awaitingSinceMs = null
        if (!persist()) {
            parentExtension = before; extendedMs = oldExtended; awaitingSinceMs = oldAwaiting
            return false
        }
        return true
    }

    fun quizNow(): Trigger? {
        val pkg = foregroundPackage ?: return null
        if (awaitingSinceMs != null) return null
        awaitingSinceMs = monotonicMs()
        awaitingGame = currentRule().isGame(pkg)
        parentExtension?.let {
            if (it.stage in setOf(ParentExtension.Stage.SENDING, ParentExtension.Stage.PENDING, ParentExtension.Stage.FAILED))
                parentExtension = it.copy(stage = ParentExtension.Stage.CLOSED)
        }
        persist()
        return Trigger(pkg, accumulatedMs)
    }
    private var lastAdvanceMs = monotonicMs()
    private var lastPersistedMs = 0L

    /** 落盘失败只说第一次：tick 一秒一次，写不进去的话会一直写不进去。 */
    private var persistFailing = false

    /** 非空表示"已触发、等答题解决"；用它取代旧的布尔位，好让超时能被观测到。 */
    private var awaitingSinceMs: Long? = null
    private var awaitingGame = false

    /** 抢屏失败后的退避时刻，避免每 tick 重试形成风暴。 */
    private var retryAllowedAfterMs = 0L

    private val bootId: Long
        get() = wallMs() - monotonicMs()

    init {
        restore()
    }

    val sessionMs: Long
        get() = accumulatedMs

    val foreground: String?
        get() = foregroundPackage

    fun onWindowPackage(packageName: String?): Trigger? {
        val now = monotonicMs()
        advanceTo(now)
        if (packageName == null) {
            return evaluate(now)
        }
        val rule = currentRule()
        if (rule.watches(packageName)) {
            if (activeRule == null) activeRule = rule
            if (cycleId.isEmpty()) cycleId = java.util.UUID.randomUUID().toString()
            if (rule.isGame(packageName)) gameCycle = true
            foregroundPackage = packageName
            awaySinceMs = null
        } else {
            leaveTarget(now)
        }
        persist()
        return evaluate(now)
    }

    fun onTick(): Trigger? {
        val now = monotonicMs()
        advanceTo(now)
        return evaluate(now)
    }

    /**
     * 抢屏没成功时必须调用：否则"等答题"状态永远无人解除，闸门会静默卡死。
     * 时长不清零——目的不是惩罚孩子，而是冷却一段时间后重新尝试拦截。
     */
    fun onFireFailed() {
        if (awaitingSinceMs == null) {
            return
        }
        awaitingSinceMs = null
        retryAllowedAfterMs = monotonicMs() + FIRE_RETRY_BACKOFF_MS
        report("抢屏失败，${FIRE_RETRY_BACKOFF_MS / 1000}s 后重试")
    }

    fun onScreenOff(recordFinalStep: Boolean = true) {
        if (recordFinalStep) advanceTo(monotonicMs())
        if (gameCycle) {
            foregroundPackage = null
            awaySinceMs = monotonicMs()
            awaitingSinceMs = null
            persist()
        } else reset()
    }

    /** 答对放行：本轮作废，重新累计下一个阈值周期。前台应用保持不变，等下一个事件续上计时。 */
    fun onQuizCleared() {
        accumulatedMs = 0L
        activeRule = null
        extendedMs = 0L
        extensionsUsed = 0
        gameCycle = false
        cycleId = ""
        parentExtension = null
        awaySinceMs = null
        awaitingSinceMs = null
        lastAdvanceMs = monotonicMs()
        persist()
    }

    private fun leaveTarget(now: Long) {
        if (foregroundPackage != null) {
            foregroundPackage = null
            awaySinceMs = now
        }
    }

    private fun advanceTo(now: Long) {
        val delta = now - lastAdvanceMs
        lastAdvanceMs = now
        if (foregroundPackage == null || delta <= 0L || awaitingSinceMs != null) {
            return
        }
        if (delta > maxStepMs) {
            // 单调时钟含深睡时间，tick 停摆期间的空白不能算成游戏时长。
            report("计时断档 ${delta}ms 超过单步上限 ${maxStepMs}ms，丢弃不累计")
            return
        }
        accumulatedMs += delta
        recordPlayed(requireNotNull(foregroundPackage), delta, wallMs())
        if (accumulatedMs - lastPersistedMs >= PERSIST_INTERVAL_MS) {
            persist()
        }
    }

    private fun evaluate(now: Long): Trigger? {
        val away = awaySinceMs
        if (!gameCycle && foregroundPackage == null && away != null && now - away >= currentRule().graceMs) {
            reset()
            return null
        }
        val pkg = foregroundPackage ?: return null
        if (waitingForParent && currentRule().isGame(pkg)) return null
        val awaiting = awaitingSinceMs
        if (awaiting != null) {
            if (now - awaiting < AWAITING_TIMEOUT_MS) {
                return null
            }
            report("待答题已挂起 ${now - awaiting}ms 未解决，重新允许触发")
            awaitingSinceMs = null
        }
        val limit = currentRule().thresholdMs + if (currentRule().isGame(pkg)) extendedMs else 0L
        if (accumulatedMs < limit) {
            return null
        }
        if (now < retryAllowedAfterMs) {
            return null
        }
        awaitingSinceMs = now
        awaitingGame = currentRule().isGame(pkg)
        persist()
        return Trigger(pkg, accumulatedMs)
    }

    private fun reset() {
        cycleId = ""
        parentExtension = null
        activeRule = null
        extendedMs = 0L
        extensionsUsed = 0
        gameCycle = false
        foregroundPackage = null
        awaySinceMs = null
        accumulatedMs = 0L
        awaitingSinceMs = null
        lastAdvanceMs = monotonicMs()
        lastPersistedMs = 0L
        store?.clear()
    }

    /**
     * 落盘一次会话快照，**不把异常抛给调用方**。
     *
     * 计时和触发判定全跑在主线程上（无障碍回调和 tick 都是），落盘的地方一共有九处。
     * 而 SharedPreferences 的 commit() 写不进去时会直接抛。让它冒出去等于让进程跟着崩：
     * 累计时长只在内存里，崩一次就归零一次，孩子只要想办法让磁盘写失败
     * （最省事的是把分区写满）就能拿到一条比卸载手机便宜得多的绕过路径。所以这里咽掉异常，只把事实说出来。
     *
     * @return 这份快照真的落盘了。"没落盘就不能算数"的那几处（延期、发起申请、接受审批）
     *         要用返回值回滚，而不是靠 catch——不返回的话它们会以为已经存好了。
     */
    private fun persist(): Boolean {
        val snapshot = SessionStore.Snapshot(
            foregroundPackage = foregroundPackage,
            accumulatedMs = accumulatedMs,
            wallMs = wallMs(),
            monotonicMs = lastAdvanceMs,
            bootId = bootId,
            cycleRule = activeRule,
            extensionsUsed = extensionsUsed,
            extendedMs = extendedMs,
            gameCycle = gameCycle,
            cycleId = cycleId,
            parentExtension = parentExtension
        )
        return try {
            store?.save(snapshot)
            // 只有真写进去才挪这个水位：先挪后写的话，一次失败会让后面
            // 一整个 PERSIST_INTERVAL 都不再重试，磁盘恢复了也补不回来。
            lastPersistedMs = accumulatedMs
            if (persistFailing) {
                persistFailing = false
                report("会话状态重新落盘成功")
            }
            true
        } catch (error: Exception) {
            if (!persistFailing) {
                persistFailing = true
                report(
                    "会话状态落盘失败（${error.javaClass.simpleName}），本轮计时只在内存里，" +
                        "进程一被杀就从头累计"
                )
            }
            false
        }
    }

    private fun restore() {
        val snapshot = store?.load() ?: return
        if (snapshot.gameCycle && snapshot.cycleRule != null) {
            activeRule = snapshot.cycleRule
            cycleId = snapshot.cycleId.ifEmpty { java.util.UUID.randomUUID().toString() }
            parentExtension = snapshot.parentExtension
            accumulatedMs = snapshot.accumulatedMs.coerceAtLeast(0)
            extensionsUsed = snapshot.extensionsUsed.coerceIn(0, 2)
            val baseLimit = snapshot.cycleRule.thresholdMs + snapshot.cycleRule.extensionMs * extensionsUsed
            val maximum = if (parentExtension?.stage == ParentExtension.Stage.GRANTED)
                snapshot.cycleRule.extensionMs * (extensionsUsed + 1) + (accumulatedMs - baseLimit).coerceAtLeast(0)
                else snapshot.cycleRule.extensionMs * extensionsUsed
            extendedMs = snapshot.extendedMs.coerceIn(0, maximum.coerceAtLeast(0))
            gameCycle = true
            // Never count an unobserved interval, even across boot or wall-clock changes.
            foregroundPackage = null
            lastAdvanceMs = monotonicMs()
            return
        }
        val pkg = snapshot.foregroundPackage
        val restoredRule = snapshot.cycleRule ?: defaultRule()
        if (pkg == null || !restoredRule.watches(pkg)) {
            store.clear()
            return
        }
        val nowWall = wallMs()
        val ageMs = nowWall - snapshot.wallMs
        if (ageMs < 0L) {
            report("系统时间比上次写盘更早 ${-ageMs}ms，丢弃快照")
            store.clear()
            return
        }
        if (ageMs > RESUME_WINDOW_MS) {
            report("快照距今 ${ageMs}ms，超过恢复窗口 ${RESUME_WINDOW_MS}ms，丢弃")
            store.clear()
            return
        }
        activeRule = snapshot.cycleRule
        foregroundPackage = pkg
        accumulatedMs = snapshot.accumulatedMs
        if (snapshot.bootId == bootId) {
            // 同一次开机：单调时钟可比，断档交给 maxStepMs 判定，不虚增也不白送。
            // 快照之前的用量已经写入独立队列。恢复时不能把同一段时间再上报一次，
            // 进程不在的那段也不是观测到的娱乐时长。
            lastAdvanceMs = monotonicMs()
        } else {
            // 重启过或改过时间：离线期间不计入，只接回已累计的量。
            lastAdvanceMs = monotonicMs()
            report("检测到开机/时间变更，离线时段不计入，已累计 ${accumulatedMs}ms")
        }
    }

    companion object {
        const val DEFAULT_GRACE_MS = 120_000L
        const val DEFAULT_MAX_STEP_MS = 6_000L
        const val AWAITING_TIMEOUT_MS = 120_000L
        const val RESUME_WINDOW_MS = 10 * 60 * 1000L

        /** 抢屏失败后的冷却：每 2s 无脑重试会和系统抢窗口，反而更容易被 ROM 掐掉。 */
        const val FIRE_RETRY_BACKOFF_MS = 15_000L

        /** 写盘节流：累计每前进这么多才落一次盘。 */
        const val PERSIST_INTERVAL_MS = 10_000L
    }
}
