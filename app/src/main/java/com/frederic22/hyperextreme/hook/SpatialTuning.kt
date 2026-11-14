package com.frederic22.hyperextreme.hook

import android.os.SystemClock
import com.frederic22.hyperextreme.data.SpatialFlag

/**
 * 空间壁纸的两个**运行时可调**参数（2026-11-14）。
 *
 * 两者的语义都写在 shader 里（见 `SpatialGlCore.FRAG`），这里只负责把 App 通过系统属性
 * 推过来的值读出来、夹到合法范围、缓存 500ms —— 每帧读属性太贵，而人手调滑块也不
 * 需要比 2Hz 更快。
 *
 * ## 为什么不用 prefs
 *
 * 渲染器跑在 SystemUI 进程，那里的模块 prefs 不可靠（见 [SpatialFlag] 的类注释：
 * `getRemotePreferences` 是陈旧快照、`openRemoteFile` 读不到文件）。系统属性是唯一
 * 各进程都能读的通道，`active/key/scope/lockdepth/crop` 走的也是它。
 */
internal object SpatialTuning {

    /** 满量程位移角（弧度）—— 对应 `uShift = nx · panRad / span`。 */
    @Volatile
    var panRad: Float = 0.17453292f   // 10°
        private set

    /** 边缘融合阈值 —— 对应 shader 的 `uDkTh`。 */
    @Volatile
    var dkTh: Float = 0.08f
        private set

    private const val PROP_CACHE_MS = 500L
    private const val PAN_DEG_MIN = 4f
    private const val PAN_DEG_MAX = 20f
    private const val DK_MIN = 0.02f
    private const val DK_MAX = 0.30f
    private const val PAN_DEG_DEFAULT = 10f
    private const val DK_DEFAULT = 0.08f

    private var readAt = 0L

    /** 每帧调用，内部 500ms 缓存。属性缺失/非法 ⇒ 回到默认值。 */
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - readAt < PROP_CACHE_MS) return
        readAt = now

        val deg = HookRuntime.systemProperty(SpatialFlag.PROP_PAN_DEG).trim()
            .toFloatOrNull()?.coerceIn(PAN_DEG_MIN, PAN_DEG_MAX) ?: PAN_DEG_DEFAULT
        panRad = Math.toRadians(deg.toDouble()).toFloat()

        dkTh = HookRuntime.systemProperty(SpatialFlag.PROP_DK).trim()
            .toFloatOrNull()?.coerceIn(DK_MIN, DK_MAX) ?: DK_DEFAULT
    }

    /** 调试用：当前生效值（度和阈值），日志里打一行就能对照 UI。 */
    fun describe(): String =
        "pan=${"%.1f".format(Math.toDegrees(panRad.toDouble()))}° dk=${"%.2f".format(dkTh)}"
}
