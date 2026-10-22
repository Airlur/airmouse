package com.airlur.airmouse

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 空鼠引擎：把手机陀螺仪的角速度换算成鼠标相对位移。
 *
 * 核心是「速率控制」而不是「位置控制」：
 *   指针的**速度**正比于手机的**角速度**，不做积分、不追踪绝对姿态。
 *   手机一停，指针立刻停 —— 所以永远不会漂出屏幕，也不需要「回中」按钮。
 *   （陀螺仪积分成角度再映射坐标的做法，十几秒就会飘到屏幕角落，不可用。）
 *
 * 手机坐标系（竖持、屏幕朝向自己）：
 *   X 轴 = 屏幕水平向右
 *   Y 轴 = 屏幕竖直向上
 *   Z 轴 = 垂直屏幕指向自己
 *
 *   -> 绕 Y 轴转（像转方向盘） = 左右摆头 -> 指针水平移动
 *   -> 绕 X 轴转（上下点头）   = 抬头低头 -> 指针垂直移动
 */
class AirMouseEngine(context: Context) : SensorEventListener {

    /** 传感器每来一帧就回调一次，单位是「像素增量」，可以是小数。 */
    var onDelta: ((Float, Float) -> Unit)? = null

    /** 总开关。按左右键的瞬间会临时关闭，避免手指压屏幕导致指针乱飞。 */
    @Volatile
    var enabled: Boolean = true

    /** 反转水平方向（首次使用时若左右反了，打开这个开关即可） */
    @Volatile
    var invertX: Boolean = false

    /** 反转垂直方向 */
    @Volatile
    var invertY: Boolean = false

    /** 灵敏度：1 rad/s 的角速度对应多少像素/秒 */
    @Volatile
    var sensitivity: Double = 600.0

    /** 死区（rad/s）：低于这个角速度视为手抖，完全忽略 */
    @Volatile
    var deadzone: Double = 0.035

    /** 增益曲线的参考上限（rad/s），快速甩动大约在这个量级 */
    @Volatile
    var maxRate: Double = 3.0

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val filterX = OneEuroFilter(minCutoff = 1.2, beta = 0.02)
    private val filterY = OneEuroFilter(minCutoff = 1.2, beta = 0.02)

    // 零偏（陀螺仪静止时的非零读数），随温度漂移，必须动态估计
    private var biasX = 0.0
    private var biasY = 0.0
    private var stillSinceNs = 0L

    private var lastTsNs = 0L
    private var started = false

    @Volatile
    private var suppressUntilNs = 0L

    val isAvailable: Boolean get() = gyro != null

    fun start() {
        val g = gyro ?: return
        if (started) return
        started = true
        lastTsNs = 0L
        suppressUntilNs = 0L
        filterX.reset()
        filterY.reset()
        sm.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME, 0)
    }

    fun stop() {
        if (!started) return
        started = false
        sm.unregisterListener(this)
    }

    /** 在接下来的 [ms] 毫秒内忽略运动输入（用于按键瞬间的手指压力干扰） */
    fun suppress(ms: Long) {
        suppressUntilNs = SystemClock.elapsedRealtimeNanos() + ms * 1_000_000L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        val nowNs = event.timestamp
        if (lastTsNs == 0L) lastTsNs = nowNs
        var dt = (nowNs - lastTsNs) / 1_000_000_000.0
        lastTsNs = nowNs
        if (dt <= 0.0 || dt > 0.2) dt = 0.02

        var wx = event.values[0].toDouble()
        var wy = event.values[1].toDouble()

        // ---------- 1. 静止零偏自校准 ----------
        // 手机基本不动时，真值应为 0，读数不为 0 的部分就是零偏。
        val mag = sqrt(wx * wx + wy * wy)
        if (mag < 0.05) {
            if (stillSinceNs == 0L) stillSinceNs = nowNs
            if (nowNs - stillSinceNs > 250_000_000L) {
                biasX += (wx - biasX) * 0.05
                biasY += (wy - biasY) * 0.05
            }
        } else {
            stillSinceNs = 0L
        }
        wx -= biasX
        wy -= biasY

        // ---------- 2. 死区 ----------
        wx = applyDeadzone(wx, deadzone)
        wy = applyDeadzone(wy, deadzone)

        if (!enabled) return
        if (nowNs < suppressUntilNs) return

        // ---------- 3. One Euro 滤波 ----------
        val t = nowNs / 1_000_000_000.0
        val rateX = filterX.filter(wy, t)   // 绕 Y 轴（竖直）-> 水平移动
        val rateY = filterY.filter(wx, t)   // 绕 X 轴（水平）-> 垂直移动

        // ---------- 4. 非线性增益：慢速精准，快速跨屏 ----------
        val gx = shapedGain(rateX) * sensitivity * (if (invertX) 1.0 else -1.0)
        val gy = shapedGain(rateY) * sensitivity * (if (invertY) 1.0 else -1.0)

        // ---------- 5. 速度 x dt = 本帧位移 ----------
        val dx = (gx * dt).toFloat()
        val dy = (gy * dt).toFloat()
        if (dx == 0f && dy == 0f) return

        onDelta?.invoke(dx, dy)
    }

    private fun applyDeadzone(v: Double, d: Double): Double {
        val a = abs(v)
        return if (a < d) 0.0 else v - d * sign(v)
    }

    /** 低速 0.55 倍（点小图标时不飘），高速最多约 3.5 倍（一甩就跨屏） */
    private fun shapedGain(v: Double): Double {
        val a = abs(v)
        if (a < 1e-6) return 0.0
        val r = a / maxRate
        return v * (0.55 + 3.0 * r * r)
    }
}
