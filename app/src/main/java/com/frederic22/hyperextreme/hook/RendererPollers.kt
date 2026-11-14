package com.frederic22.hyperextreme.hook

import com.frederic22.hyperextreme.data.AliveFlag
import com.frederic22.hyperextreme.data.AodState
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.RasterFlag
import com.frederic22.hyperextreme.data.SpatialFlag

/**
 * 三条「渲染器开关」轮询，**合并到一条线程**上（2026-11-14）。
 *
 * ## 为什么合并
 *
 * 空间（spatial）、光栅（raster）、Alive 三条链各自起一个 `while(true) { sleep(500) }`
 * 线程：三个常驻线程、每秒三次唤醒，各自读 3~6 个系统属性。对耗电是小事，
 * 但三条线程都长期存活、都要在 SystemUI 里争调度，属于纯浪费。
 *
 * 合并后：一个线程、一轮读完三条链、一次 `sleep`。代价是三条链串行执行 ——
 * 每次 tick 的成本是几次属性读取（微秒级），串行没有可感知影响。
 *
 * ## 每条链的状态
 *
 * 原先都是线程内的局部变量，现在提成字段。**不要**把它们挪回方法内：
 * 它们记录"上次生效的 key/scope/style"，丢了就会反复重启渲染器。
 */
internal object RendererPollers {

    private const val INTERVAL_MS = 500L

    private var started = false

    // ───────────────────────── spatial ─────────────────────────

    private var spLastKey: String? = null
    private var spLastScope = Int.MIN_VALUE

    /** 封面让位中（图层隐藏、纹理保留）；用它区分"因为让位而停"与"用户关掉了"。 */
    private var spYielded = false
    private var spCtxWarn = 0
    private var spTick = 0

    // ───────────────────────── raster ─────────────────────────

    private var raLastKey: String? = null
    private var raLastScope = Int.MIN_VALUE
    private var raTick = 0

    // 2026-11-07：「过渡效果 / 停顿归位」改成运行中实时。首轮只记基线，不触发归位。
    private var raLastStyle = 0
    private var raLastSettle = true
    private var raParamsInit = false

    // ───────────────────────── alive ─────────────────────────

    private var alLastKey: String? = null
    private var alLastScope = Int.MIN_VALUE

    /** 息屏样式（0 启航 / 1 留光）：渲染器只读一次快照，样式变了必须重启。 */
    private var alLastStyle = Int.MIN_VALUE

    /** 图片版本：换图不改 id，只靠 key/style 判不出变化。 */
    private var alLastVer: String? = null

    /** 「息屏显示」总开关；跨进程读 Settings 有成本 ⇒ 每 8 个 tick 读一次。 */
    private var alAodOn = true
    private var alAodOff = false
    private var alTick = 0

    fun start() {
        if (started) return
        started = true
        Thread {
            HookRuntime.log("渲染器轮询已启动（spatial/raster/alive 合并为一条线程，${INTERVAL_MS}ms）")
            while (true) {
                // 每条链单独兜异常：一条出错不许把另外两条带走（原先是三个独立线程，天然隔离）。
                runCatching { tickSpatial() }
                    .onFailure { HookRuntime.log("SPATIAL 轮询异常：${it.javaClass.simpleName}: ${it.message}") }
                runCatching { tickRaster() }
                    .onFailure { HookRuntime.log("RASTER 轮询异常：${it.javaClass.simpleName}: ${it.message}") }
                runCatching { tickAlive() }
                    .onFailure { HookRuntime.log("ALIVE 轮询异常：${it.javaClass.simpleName}: ${it.message}") }
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (t: Throwable) {
                    // 线程不许死
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 空间壁纸开关 + 封面让位。返回 true 表示本轮已处理完（对应原来的 `continue`）。 */
    private fun tickSpatial() {
        spTick++
        // 调参（位移幅度 / 边缘融合 / iOS 开关）变了 ⇒ 强制重绘一帧。
        // 渲染器只在姿态变化时重绘，手机静止时拖滑块本来是看不到变化的（2026-11-14）。
        val revBefore = SpatialTuning.revision
        SpatialTuning.refresh()
        if (SpatialTuning.revision != revBefore) SpatialRenderer.redrawForTuning()
        // **主通道 = 系统属性**（2026-10-23）：SystemUI 里读模块 prefs 不可靠 ——
        // `getRemotePreferences` 是陈旧快照，`openRemoteFile` 读不到文件。
        val propActive = HookRuntime.systemProperty(SpatialFlag.PROP_ACTIVE)
        val propKey = HookRuntime.systemProperty(SpatialFlag.PROP_KEY)
        val propScope = HookRuntime.systemProperty(SpatialFlag.PROP_SCOPE)
        val scope = propScope.trim().toIntOrNull()
            ?.takeIf { it in 0..2 } ?: MaskPrefs.SPATIAL_SCOPE_LOCK
        val active = if (propActive.isNotEmpty()) propActive == "1"
        else (HookRuntime.remoteBoolean(MaskPrefs.KEY_SPATIAL_ACTIVE) ?: false)
        val key = if (propKey.isNotEmpty()) propKey
        else HookRuntime.remoteString(MaskPrefs.KEY_SPATIAL_KEY).orEmpty()

        YieldGate.ensureStarted()
        if (YieldGate.coverOn) {
            if (spLastKey != null && !spYielded) {
                HookRuntime.log("SPATIAL 让位：封面模式中 → 隐藏图层（保留 $spLastKey/$spLastScope）")
                SpatialRenderer.suspendForYield()
                spYielded = true
            }
            return
        }
        if (spYielded) {
            HookRuntime.log("SPATIAL 让位结束 → 显示图层并补帧")
            SpatialRenderer.resumeFromYield()
            spYielded = false
        }
        if (spTick <= 3 || spTick % 120 == 0) {
            HookRuntime.log(
                "SPATIAL 轮询 tick=${spTick} prop=[$propActive/$propKey/$propScope] ⇒ " +
                    "active=$active key=$key scope=$scope last=$spLastKey/$spLastScope",
            )
        }
        if (active && key.length == 16) {
            if (key != spLastKey || scope != spLastScope) {
                val ctx = HookRuntime.appContext()
                if (ctx != null) {
                    HookRuntime.log("SPATIAL 开关=开 key=$key scope=$scope → 启动渲染器")
                    SpatialRenderer.start(ctx, key, scope)
                    spLastKey = key
                    spLastScope = scope
                } else if (spCtxWarn++ % 30 == 0) {
                    HookRuntime.log("SPATIAL 开关=开 key=$key，但拿不到 Context（第 ${spCtxWarn} 次）")
                }
            }
        } else if (spLastKey != null) {
            HookRuntime.log("SPATIAL 开关=关 → 停止渲染器")
            SpatialRenderer.stop()
            spLastKey = null
            spLastScope = Int.MIN_VALUE
        }
    }

    /** 光栅壁纸开关。 */
    private fun tickRaster() {
        raTick++
        val active = HookRuntime.systemProperty(RasterFlag.PROP_ACTIVE).trim() == "1"
        val key = HookRuntime.systemProperty(RasterFlag.PROP_KEY).trim()
        val scope = HookRuntime.systemProperty(RasterFlag.PROP_SCOPE).trim()
            .toIntOrNull()?.takeIf { it in 0..2 } ?: MaskPrefs.SPATIAL_SCOPE_LOCK
        // ⚠️ 只盯这两项；`PROP_PAN_DEG` 不进实时通道（改角度会跳图，维持下一次生效）。
        val style = HookRuntime.systemProperty(RasterFlag.PROP_TRANSITION).trim()
            .toIntOrNull()?.coerceIn(0, 3) ?: 0
        val settle = HookRuntime.systemProperty(RasterFlag.PROP_SETTLE).trim() != "0"
        if (!raParamsInit) {
            raLastStyle = style
            raLastSettle = settle
            raParamsInit = true
        } else if (style != raLastStyle || settle != raLastSettle) {
            raLastStyle = style
            raLastSettle = settle
            if (active && key.isNotEmpty() && raLastKey != null) {
                RasterRenderer.applyRealtime(style, settle)
            }
        }
        if (YieldGate.coverOn) {
            if (raLastKey != null) {
                HookRuntime.log("RASTER 让位：封面模式中 → 停止渲染器（属性保留 $raLastKey/$raLastScope）")
                RasterRenderer.stop()
                raLastKey = null
                raLastScope = Int.MIN_VALUE
            }
            return
        }
        if (raTick <= 3 || raTick % 120 == 0) {
            HookRuntime.log(
                "RASTER 轮询 tick=${raTick} active=$active key=$key scope=$scope " +
                    "last=$raLastKey/$raLastScope",
            )
        }
        if (active && key.isNotEmpty()) {
            if (key != raLastKey || scope != raLastScope) {
                val ctx = HookRuntime.appContext()
                if (ctx != null) {
                    HookRuntime.log("RASTER 开关=开 key=$key scope=$scope → 启动渲染器")
                    RasterRenderer.start(ctx, key, scope)
                    raLastKey = key
                    raLastScope = scope
                }
            }
        } else if (raLastKey != null) {
            HookRuntime.log("RASTER 开关=关 → 停止渲染器")
            RasterRenderer.stop()
            raLastKey = null
            raLastScope = Int.MIN_VALUE
        }
    }

    /** Alive 壁纸开关（含息屏显示总开关联动）。 */
    private fun tickAlive() {
        alTick++
        if (alTick % 8 == 1) {
            val c = HookRuntime.appContext()
            if (c != null) {
                alAodOn = runCatching {
                    android.provider.Settings.Secure.getInt(
                        c.contentResolver, AodState.KEY_ENABLED, 0,
                    ) == 1
                }.getOrDefault(true)
            }
        }
        val active = HookRuntime.systemProperty(AliveFlag.PROP_ACTIVE).trim() == "1"
        val key = HookRuntime.systemProperty(AliveFlag.PROP_KEY).trim()
        val scope = HookRuntime.systemProperty(AliveFlag.PROP_SCOPE).trim()
            .toIntOrNull()?.takeIf { it in 0..2 } ?: MaskPrefs.SPATIAL_SCOPE_GLOBAL
        val style = HookRuntime.systemProperty(AliveFlag.PROP_STYLE).trim().toIntOrNull() ?: 0
        val ver = HookRuntime.systemProperty(AliveFlag.PROP_VER).trim()
        // —— 封面让位：Alive 同样挂在壁纸 leash 上、全屏不透明 ——
        // 与空间/光栅的差别只是要一并清掉 style/ver 基线：Alive 的 start 内部只读一次快照，
        // 恢复时必须让它按当前属性重读，否则会拿旧样式重画。
        if (YieldGate.coverOn) {
            if (alLastKey != null) {
                HookRuntime.log("ALIVE 让位：封面模式中 → 停止渲染器（属性保留 $alLastKey/$alLastScope）")
                AliveRenderer.stop()
                alLastKey = null
                alLastScope = Int.MIN_VALUE
                alLastStyle = Int.MIN_VALUE
                alLastVer = null
            }
            return
        }
        if (alTick <= 3 || alTick % 120 == 0) {
            HookRuntime.log(
                "ALIVE 轮询 tick=${alTick} active=$active key=$key scope=$scope " +
                    "style=$style ver=$ver last=$alLastKey/$alLastScope/$alLastStyle/$alLastVer",
            )
        }
        if (!alAodOn) {
            // 息屏显示被关掉 ⇒ Alive 壁纸没有意义了，直接停渲染（不再占 Surface）。
            if (!alAodOff) {
                alAodOff = true
                if (alLastKey != null) {
                    HookRuntime.log("ALIVE 息屏显示已关闭 → 停止渲染器")
                    AliveRenderer.stop()
                    alLastKey = null
                    alLastScope = Int.MIN_VALUE
                    alLastStyle = Int.MIN_VALUE
                    alLastVer = null
                }
            }
            return
        }
        if (alAodOff) {
            alAodOff = false
            HookRuntime.log("ALIVE 息屏显示已开启 → 恢复（按属性重启渲染器）")
        }
        if (active && key.isNotEmpty()) {
            if (key != alLastKey || scope != alLastScope || style != alLastStyle ||
                ver != alLastVer
            ) {
                val ctx = HookRuntime.appContext()
                if (ctx != null) {
                    HookRuntime.log(
                        "ALIVE 开关=开 key=$key scope=$scope style=$style ver=$ver → 启动渲染器",
                    )
                    AliveRenderer.start(ctx, key, scope)
                    alLastKey = key
                    alLastScope = scope
                    alLastStyle = style
                    alLastVer = ver
                }
            }
        } else if (alLastKey != null) {
            HookRuntime.log("ALIVE 开关=关 → 停止渲染器")
            AliveRenderer.stop()
            alLastKey = null
            alLastScope = Int.MIN_VALUE
            alLastStyle = Int.MIN_VALUE
            alLastVer = null
        }
    }
}
