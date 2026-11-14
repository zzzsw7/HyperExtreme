package com.frederic22.hyperextreme.hook

import android.os.SystemClock
import com.frederic22.hyperextreme.data.SpatialFlag

/**
 * 空间壁纸的三个**运行时可调**参数（2026-11-14）。
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
 *
 * ## 默认值和量程为什么改了（2026-11-14 晚）
 *
 * 老的编译期常量是 `SpatialGlCore.PAN_RAD = 0.02434`（**1.39°**，标定 = 2640px 屏上 100px）。
 * 上午那版滑杆把默认值写成了 10°、下限 4° —— 等于把幅度放大 7 倍，轮廓处的位移带被摊成
 * 一片涂抹（用户反馈「糊」）。现在量程回到 0.5°..4°、默认 1.4°，和老的观感对齐；
 * 4° 封顶是几何硬限（`SPHERE_SAFE` 给的 uv 余量只够到 ~4.37°）。
 */
internal object SpatialTuning {

    /** 满量程位移角（弧度）—— 对应 `uShift = nx · panRad / span`。 */
    @Volatile
    var panRad: Float = 0.0244346f   // 1.4°（老 PAN_RAD 是 1.394°）
        private set

    /** 边缘融合阈值 —— 对应 shader 的 `uDkTh`。 */
    @Volatile
    var dkTh: Float = 0.08f
        private set

    /**
     * 「iOS 立体感」（2026-11-14）：主体层走**刚性**平移（内部不形变）、背景按
     * [IOS_BG_RATIO] 慢跟随、并在背景与主体之间多画一层主体投影。
     *
     * 关掉 = 原来的「两层都逐像素位移」行为，随时可对比。
     */
    @Volatile
    var iosMode: Boolean = false
        private set

    private const val PROP_CACHE_MS = 500L
    private const val PAN_DEG_MIN = 0.5f
    private const val PAN_DEG_MAX = 4f
    private const val DK_MIN = 0.02f
    private const val DK_MAX = 0.30f
    private const val PAN_DEG_DEFAULT = 1.4f
    private const val DK_DEFAULT = 0.08f

    /** iOS 模式：背景层位移 = 主体 × 这个比例（真实视差里远处动得少）。 */
    const val IOS_BG_RATIO = 0.35f

    /** iOS 模式：主体层的刚性系数（`k` 越大 = 越像贴在最前面）。 */
    const val IOS_SUBJECT_K = 0.62f

    /** iOS 模式：投影的不透明度 / 相对主体的 uv 偏移（光从左上来 ⇒ 影子往右下）。 */
    const val IOS_SHADOW_ALPHA = 0.45f
    const val IOS_SHADOW_DX = 0.006f
    const val IOS_SHADOW_DY = 0.010f

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

        iosMode = HookRuntime.systemProperty(SpatialFlag.PROP_IOS).trim() == "1"
    }

    /** 调试用：当前生效值（度 / 阈值 / 模式），日志里打一行就能对照 UI。 */
    fun describe(): String =
        "pan=${"%.1f".format(Math.toDegrees(panRad.toDouble()))}° dk=${"%.2f".format(dkTh)}" +
            " ios=${if (iosMode) 1 else 0}"
}
