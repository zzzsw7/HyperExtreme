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
    private const val PAN_DEG_MIN = 0.1f
    private const val PAN_DEG_MAX = 10f
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

    /**
     * iOS 模式：主体轮廓硬化宽度（shader 里对 alpha 再走一次 smoothstep）。
     *
     * 分层给的 alpha 是**软过渡**（羽化 4px + 深度带），转动时那圈半透明会被拉开成
     * "果冻边" —— 这就是用户说的「边缘还是会变形」。iOS 的抠像是硬的（1px 反锯齿），
     * 柔和感由投影负责。0.06 ⇒ 过渡落在 alpha 0.44..0.56，约 1px。
     */
    const val IOS_ALPHA_CUT = 0.06f

    /**
     * **抹平深度场**（2026-11-14 晚，实测驱动）。
     *
     * 背景场 k 的区间是 `[0.15, 0.6175]`，但它在主体边缘附近有很陡的跳变（实测
     * `max|∂k/∂u|` 8–21，p99 7–13）。渲染幅度放大 ⇒ `1 − PAN_UV·Δk/Δu` 变负 = 内容回折。
     * 实测三张缓存：2° 回折 0.02%、4° 1–2.5%、6–10° 5–11%。
     *
     * 所以超过 [PAN_FLATTEN_START_DEG] 就把场抹向常数 [FLAT_K]：
     * `k' = mix(k, FLAT_K, flatten)`。内部梯度消失 ⇒ 不再摊开；主体与背景的整体错位保留
     * ⇒ 幅度大依然有立体感，只是不再是逐像素形变。
     */
    const val FLAT_K = 0.25f

    /** 抹平起点/终点（度）：≤2° 完全保持逐像素行为；≥4° 背景成为一块平板。 */
    private const val PAN_FLATTEN_START_DEG = 2.0f
    private const val PAN_FLATTEN_FULL_DEG = 4.0f

    /** 当前幅度的抹平系数（0 = 原样，1 = 平板）。smoothstep，避免拖动时观感突变。 */
    fun flattenFor(panRad: Float): Float {
        val start = Math.toRadians(PAN_FLATTEN_START_DEG.toDouble()).toFloat()
        val full = Math.toRadians(PAN_FLATTEN_FULL_DEG.toDouble()).toFloat()
        if (panRad <= start) return 0f
        if (panRad >= full) return 1f
        val t = (panRad - start) / (full - start)
        return t * t * (3f - 2f * t)
    }

    private var readAt = 0L

    /**
     * 属性值每变一次就 +1。[com.frederic22.hyperextreme.hook.RendererPollers] 的轮询线程
     * 靠它判断「调参变了」→ 强制重绘一帧。
     *
     * **为什么需要**：渲染器只在**姿态变化**时重绘。手机放在桌上不动时，拖滑块 / 拨开关
     * 属性虽然变了，但画面不刷 ⇒ 看起来像"没生效"，直到你转一下手机。有了这个计数，
     * 轮询一发现值变就 requestDraw，观感才是真正的"即时生效"。
     */
    @Volatile
    var revision: Int = 0
        private set

    /** 每帧调用，内部 500ms 缓存。属性缺失/非法 ⇒ 回到默认值。 */
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - readAt < PROP_CACHE_MS) return
        readAt = now

        val deg = HookRuntime.systemProperty(SpatialFlag.PROP_PAN_DEG).trim()
            .toFloatOrNull()?.coerceIn(PAN_DEG_MIN, PAN_DEG_MAX) ?: PAN_DEG_DEFAULT
        val newPan = Math.toRadians(deg.toDouble()).toFloat()
        val newDk = HookRuntime.systemProperty(SpatialFlag.PROP_DK).trim()
            .toFloatOrNull()?.coerceIn(DK_MIN, DK_MAX) ?: DK_DEFAULT
        val newIos = HookRuntime.systemProperty(SpatialFlag.PROP_IOS).trim() == "1"

        if (newPan != panRad || newDk != dkTh || newIos != iosMode) revision++
        panRad = newPan
        dkTh = newDk
        iosMode = newIos
    }

    /** 调试用：当前生效值（度 / 阈值 / 模式），日志里打一行就能对照 UI。 */
    fun describe(): String =
        "pan=${"%.1f".format(Math.toDegrees(panRad.toDouble()))}° dk=${"%.2f".format(dkTh)}" +
            " ios=${if (iosMode) 1 else 0}"
}
