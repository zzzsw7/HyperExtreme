package com.frederic22.hyperextreme.hook

import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **封面让位闸门**（2026-11-13）—— 给 HyperMusicCover 的「全屏专辑封面」让出壁纸层。
 *
 * ## 为什么需要它
 *
 * HyperMusicCover 的全屏封面画在**系统壁纸纹理**上（hook `miwallpaper` 渲染器的 `getBitmap()`），
 * 而本模块的空间 / 光栅 / Alive 壁纸是自建 `SurfaceControl`、`reparent` 到**壁纸 leash** 上、
 * 全屏不透明 ⇒ 子层压父层，封面被完全盖住。两条链都在跑时，封面等于白装。
 *
 * 本闸门只做一件事：**在封面模式期间把渲染器停掉**（拆层、露出系统壁纸），结束后让它按原属性
 * 自动重启。注意它**不碰 `debug.hyperextreme.*` 属性、也不碰 prefs** —— 所以：
 *
 * * App 界面上的开关状态、指纹、换壁纸自动关闭全部不受影响；
 * * 恢复时不需要记 key/scope：属性还在，轮询自然会重新 `start()`；
 * * 即使本进程重启，读到的仍是用户原本的配置。
 *
 * ## 信号源（按优先级）
 *
 * 1. **`Settings.Global.hyperextreme_yield_cover`** —— 由 HyperMusicCover 在
 *    `CoverPush.pushArtToWallpaper(ctx, on, art)` 里写入（`1` = 封面中）。精确、无延迟，
 *    且 SystemUI 本来就有写这条的设置权限（HyperMusicCover 已用同样方式写 `hypermusiccover_force_colon`）。
 * 2. **媒体会话兜底** —— 「正在播放且元数据带封面图」。用于没打 HyperMusicCover 补丁的场合；
 *    缺点：只认"在放音乐 + 有封面"，不认"用户选了保留壁纸模式"，所以**保守起见默认开启但可关**。
 *
 * ## 去抖（必须）
 *
 * 播放/暂停、切歌、通知栏预览、锁屏媒体卡的展开都会让信号抖动。直接跟随会把 GL 上下文
 * 反复重建（每次 ~27MB 纹理上传），功耗和卡顿比不共存还糟。所以：
 *
 * * [DEBOUNCE_MS] = 800ms 滞后确认；
 * * [MIN_HOLD_MS] = 2s 最短让位 —— 切歌瞬间的"暂停→播放"不会把渲染器拆了又建。
 *
 * ## 调试
 *
 * `su -c setprop debug.hyperextreme.yield_disable 1` 可临时停用让位（信号恒 false），
 * 用来对比"有没有闸门"的观感差异。
 */
internal object YieldGate {

    /** HyperMusicCover 进入/退出封面模式时写的 `Settings.Global` 键（见 patch 说明）。 */
    private const val KEY_FLAG = "hyperextreme_yield_cover"

    /** 调试开关：置 1 则闸门恒关（不影响其他逻辑）。 */
    private const val PROP_DISABLE = "debug.hyperextreme.yield_disable"

    /**
     * 没有 HyperMusicCover 信号时，是否用「媒体在播放 + 有封面」兜底。
     *
     * 置 false 就只认 [KEY_FLAG] —— 适合"只想要精确信号、不想被任意播放器触发"的场景。
     */
    private const val USE_MEDIA_FALLBACK = true

    /**
     * 采样周期。250ms：与 HyperExtreme 自己三条轮询的节奏对齐，
     * 让「标志翻转 → 停/启渲染器」的最坏延迟控制在 ~250ms。
     */
    private const val POLL_MS = 1_000L

    /**
     * 观察者已注册时的兜底轮询间隔（2026-11-14）。
     *
     * 真正触发靠 `ContentObserver`，轮询只是"观察者没送来事件"时的保险。
     * 每轮都要跨进程查一次 `SettingsProvider`，1s 一次是白白多付的 binder 开销；
     * 观察者正常时放宽到 5s，注册失败则保持 1s。
     */
    private const val POLL_MS_WITH_OBSERVER = 5_000L

    /**
     * 去抖窗口（2026-11-13 从 800ms 降到 350ms）。
     *
     * 800ms 是"保守"值，实际观感是切歌/退出封面后要等一秒多才看到反应。
     * 350ms 仍能吃掉播放/暂停抖动（那种抖动通常在 100ms 内回来），
     * 短促的切歌也不会把渲染器拆了又建 —— 那由 [MIN_HOLD_MS] 兜底。
     */
        /** 进入方向去抖：防切歌抖动。 */
    private const val DEBOUNCE_ON_MS = 60L

    /** 退出方向**不去抖**：晚一帧就会露出一帧系统壁纸（实测 60fps 下正好 1 帧）。 */
    private const val DEBOUNCE_OFF_MS = 0L

    /** 最短让位时长；覆盖「切歌瞬间 pause→play」这种一过性掉封面。 */
    /**
     * 让位最短保持（2026-11-13 定为 0）。
     *
     * 原来是 1500ms，用来防"快速进出封面"的抖动。真机复测发现它是**系统壁纸闪现的直接原因**：
     * 封面只开了 0.5s 就退出时，闸门被压住不让恢复，而 HyperMusicCover 已经把封面撤掉 ⇒
     * 中间这段时间露的就是系统壁纸。用户的原话"等一会再切回就不闪"正是它的指纹。
     * 防抖已经由 [DEBOUNCE_ON_MS] + [DEBOUNCE_OFF_MS] 承担，这里不再需要。
     */
    private const val MIN_HOLD_MS = 0L

    /** 对外唯一读口：true = 现在应当让位（停渲染器）。 */
    @Volatile
    var coverOn: Boolean = false
        private set

    @Volatile
    private var started = false

    /** `ContentObserver` 是否注册成功（决定兜底轮询用 1s 还是 5s）。 */
    @Volatile
    private var observerReady = false

    /**
     * 封面状态**翻转时的直接回调**（2026-11-13）。
     *
     * 设了它就不必等三条轮询去发现 `coverOn` 变了 —— 闸门一翻当场驱动渲染器，
     * 这是把"退出封面时能看到系统壁纸"压掉的关键：回调发生在 HMC 封面淡出（~370ms）走完之前。
     */
    @Volatile
    var onCoverChanged: ((Boolean) -> Unit)? = null

    private fun log(msg: String) = HookRuntime.log("YIELD $msg")

    /**
     * 幂等启动。**拿不到 Context 就直接返回**，下一次调用再试
     * —— SystemUI 里 `ActivityThread.currentApplication()` 在早期是 null（已有先例见 HookEntry 的兜底线程）。
     */
    fun ensureStarted() {
        if (started) return
        val ctx = HookRuntime.appContext() ?: return
        synchronized(this) {
            if (started) return
            started = true
            val app = ctx.applicationContext ?: ctx
            runCatching { startPolling(app) }.onFailure {
                log("启动失败：${it.javaClass.simpleName}: ${it.message}")
                started = false
            }
        }
    }

    private fun startPolling(ctx: Context) {
        val sessions = runCatching {
            ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        }.getOrNull()
        val controllers = CopyOnWriteArrayList<MediaController>()
        if (sessions != null && USE_MEDIA_FALLBACK) {
            // 会话列表变化才刷新缓存；状态轮询只读缓存的 controller，避免每 500ms 走一次 binder 列表查询。
            runCatching {
                sessions.addOnActiveSessionsChangedListener({ list ->
                    controllers.clear()
                    if (list != null) controllers.addAll(list)
                }, null)
                controllers.addAll(sessions.getActiveSessions(null))
            }.onFailure {
                log("媒体会话不可用（${it.javaClass.simpleName}），只认 $KEY_FLAG")
            }
        }

        val thread = HandlerThread("he-yield").apply { start() }
        val handler = Handler(thread.looper)
        val ticker = object : Runnable {
            private var pending: Boolean? = null
            private var pendingAt = 0L
            private var onAt = 0L

            override fun run() {
                runCatching {
                    val now = SystemClock.elapsedRealtime()
                    settle(raw(ctx, controllers), now)
                }.onFailure { log("轮询异常：${it.javaClass.simpleName}: ${it.message}") }
                handler.removeCallbacks(this)
                // 有待确认的翻转 ⇒ 去抖一到期就再跑一次，不必等下一个轮询周期。
                // 只有"进入"的 pending 需要按期重跑；"退出"不 pending（debounce=0），把它压到轮询兜底里，避免忙等。
                handler.postDelayed(
                    this,
                    if (pending == true) DEBOUNCE_ON_MS
                    else if (observerReady) POLL_MS_WITH_OBSERVER else POLL_MS,
                )
            }

            /** 去抖 + 最短让位，见类注释。 */
            private fun settle(raw: Boolean, now: Long) {
                if (raw == coverOn) {
                    pending = null
                    return
                }
                val debounce = if (raw) DEBOUNCE_ON_MS else DEBOUNCE_OFF_MS
                if (pending != raw) {
                    pending = raw
                    pendingAt = now
                }
                // 退出方向 debounce = 0 ⇒ 本次就提交，不再等下一轮（少一帧系统壁纸）。
                if (now - pendingAt < debounce) return
                if (!raw && now - onAt < MIN_HOLD_MS) return
                coverOn = raw
                if (raw) onAt = now
                pending = null
                log(if (raw) "封面模式开启 → 让位" else "封面模式结束 → 恢复")
                // 当场驱动渲染器（不等轮询）。异常只记日志，轮询仍会兜底。
                runCatching { onCoverChanged?.invoke(raw) }
                    .onFailure { log("直接驱动失败：${it.javaClass.simpleName}: ${it.message}") }
                // 恢复这一侧要主动把景深前景层再收一次口：让位期间它是放开的，
                // 而"让位结束"本身不会触发系统的 visibility 回调（详见 SpatialForegroundHider.enforce）。
                if (!raw) {
                    runCatching { SpatialForegroundHider.enforce("封面让位结束") }
                        .onFailure { log("强制收口调用失败：${it.javaClass.simpleName}") }
                }
            }
        }
        // **即时信号**（2026-11-13）：观察那个设置键，一变就立刻跑一次 —— 不再等轮询。
        // 这是把切换延迟从 ~540ms 压到 ~250ms 的关键（轮询只剩兜底作用）。
        runCatching {
            ctx.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(KEY_FLAG),
                false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        handler.removeCallbacks(ticker)
                        handler.post(ticker)
                    }
                },
            )
        }.onSuccess { observerReady = true }
            .onFailure { log("注册观察者失败（${it.javaClass.simpleName}），退回纯轮询") }

        handler.post(ticker)
        log("已启动（flag=$KEY_FLAG 观察者=已注册 媒体兜底=$USE_MEDIA_FALLBACK）")
    }

    /** 当前原始信号（未去抖）。 */
    private fun raw(ctx: Context, controllers: List<MediaController>): Boolean {
        if (HookRuntime.systemProperty(PROP_DISABLE).trim() == "1") return false
        // 1) HyperMusicCover 的精确信号
        if (readFlag(ctx)) return true
        // 2) 媒体会话兜底
        if (!USE_MEDIA_FALLBACK) return false
        for (c in controllers) {
            val state = runCatching { c.playbackState }.getOrNull() ?: continue
            if (state.state != PlaybackState.STATE_PLAYING) continue
            val meta = runCatching { c.metadata }.getOrNull() ?: continue
            if (meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null ||
                meta.getBitmap(MediaMetadata.METADATA_KEY_ART) != null
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 读 HyperMusicCover 写的标志。
     *
     * ⚠️ **必须直查 provider，不能用 `Settings.Global.getInt`**（2026-11-13 真机实测）：
     * `Settings.Global` 在进程内有一份 `NameValueCache`，长时间运行的 SystemUI 里出现过
     * 「HyperMusicCover 明明写了 1，这边读回来还是 0」—— 闸门因此整个漏掉这次让位
     * （日志证据：16:32:53 封面推送成功、标志已是 1，但 40 秒内没有 `YIELD 封面模式开启`；
     * 重启 SystemUI 后同一个值立刻被读到）。
     * `ContentResolver.query` 直接走 SettingsProvider，不经过那层缓存。
     */
    private fun readFlag(ctx: Context): Boolean = runCatching {
        // ⚠️ **必须显式投影 `value`**：直查这个 URI 时第 0 列是 `_id`（实测 1539391），
        // 用 `getInt(0)` 会一直读到 `_id != 1` ⇒ 闸门永远不触发。
        ctx.contentResolver.query(
            Settings.Global.getUriFor(KEY_FLAG),
            arrayOf("value"), null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use false
            val idx = c.getColumnIndex("value")
            idx >= 0 && c.getString(idx)?.trim() == "1"
        } ?: false
    }.getOrElse {
        // provider 查询异常时退回旧路径（至少不比没有强）
        runCatching { Settings.Global.getInt(ctx.contentResolver, KEY_FLAG, 0) == 1 }
            .getOrDefault(false)
    }
}
