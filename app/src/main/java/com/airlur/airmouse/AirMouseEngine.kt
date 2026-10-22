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
 * 空鼠引擎 v2
 *
 * 相对 v1 的三处关键修复：
 *
 * 1) v1 只用了陀螺仪的 X / Y 两个轴，Z 轴（绕屏幕法线的翻转）被整段丢弃。
 *    而手持手机「摇晃」时，主导的角速度恰恰常出现在 Z 轴上 —— 结果是完全没反应。
 *    v2 三轴全用。
 *
 * 2) 直接把机身坐标系的角速度当成屏幕方向，手机一斜握方向就错。
 *    v2 用旋转矢量把角速度投影到世界坐标系：
 *      - 绕世界竖直轴的分量（yaw）  -> 水平移动
 *      - 绕世界水平横轴的分量（pitch）-> 垂直移动
 *    这样手机怎么拿都能用。
 *
 * 3) v1 低速增益过低：0.2 rad/s 只有 0.88 像素/帧，取整后恒为 0。
 *    v2 把基础增益提到 1.0，灵敏度上限提到 3000，默认 1380。
 *
 * 依然是「速率控制」而不是位置积分 —— 手机一停，指针立刻停，永不漂移。
 */
class AirMouseEngine(context: Context) : SensorEventListener {

    var onDelta: ((Float, Float) -> Unit)? = null

    @Volatile var enabled: Boolean = true
    @Volatile var invertX: Boolean = false
    @Volatile var invertY: Boolean = false

    /** 1 rad/s 对应多少像素/秒 */
    @Volatile var sensitivity: Double = 1380.0

    /** 死区（rad/s），低于此视为手抖 */
    @Volatile var deadzone: Double = 0.02

    /** 增益曲线的参考角速度 */
    @Volatile var maxRate: Double = 3.0

    // ---------------- 遥测（供界面诊断面板读取） ----------------
    @Volatile var telRawX = 0f
    @Volatile var telRawY = 0f
    @Volatile var telRawZ = 0f
    @Volatile var telYaw = 0f
    @Volatile var telPitch = 0f
    @Volatile var telDx = 0f
    @Volatile var telDy = 0f
    @Volatile var telEvents = 0L

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** 游戏旋转矢量优先（无磁力计干扰，更稳） */
    private val rotVec: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val hasGyro: Boolean get() = gyro != null
    val hasRotation: Boolean get() = rotVec != null

    /** 从设置同步参数（灵敏度、反向改完立即生效，不用重启） */
    fun bindFrom(s: Settings) {
        sensitivity = s.airSensitivity()
        invertX = s.invertX
        invertY = s.invertY
    }

    // 旋转矩阵：R 把「机身坐标」映射到「世界坐标」（Android 世界系 X 东 Y 北 Z 天）
    private val rotMatrix = FloatArray(9)
    @Volatile private var hasRotationMatrix = false

    private val filterYaw = OneEuroFilter(minCutoff = 1.0, beta = 0.03)
    private val filterPitch = OneEuroFilter(minCutoff = 1.0, beta = 0.03)

    // 三轴零偏
    private val bias = DoubleArray(3)
    private var biasReady = false
    private var stillSinceNs = 0L

    private var lastTsNs = 0L
    private var started = false

    @Volatile private var suppressUntilNs = 0L

    fun start() {
        val g = gyro ?: return
        if (started) return
        started = true
        lastTsNs = 0L
        suppressUntilNs = 0L
        filterYaw.reset()
        filterPitch.reset()
        sm.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME, 0)
        rotVec?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, 0) }
    }

    fun stop() {
        if (!started) return
        started = false
        sm.unregisterListener(this)
    }

    /** 按键瞬间冻结运动输入，避免手指压屏幕带动机身导致指针乱窜 */
    fun suppress(ms: Long) {
        suppressUntilNs = SystemClock.elapsedRealtimeNanos() + ms * 1_000_000L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                hasRotationMatrix = true
                return
            }
            Sensor.TYPE_GYROSCOPE -> Unit
            else -> return
        }

        val nowNs = event.timestamp
        if (lastTsNs == 0L) lastTsNs = nowNs
        var dt = (nowNs - lastTsNs) / 1_000_000_000.0
        lastTsNs = nowNs
        if (dt <= 0.0 || dt > 0.2) dt = 0.02

        var wx = event.values[0].toDouble()
        var wy = event.values[1].toDouble()
        var wz = event.values[2].toDouble()

        telRawX = wx.toFloat()
        telRawY = wy.toFloat()
        telRawZ = wz.toFloat()
        telEvents += 1

        // ---------- 1. 三轴零偏自校准 ----------
        val mag = sqrt(wx * wx + wy * wy + wz * wz)
        if (mag < 0.06) {
            if (stillSinceNs == 0L) stillSinceNs = nowNs
            if (nowNs - stillSinceNs > 250_000_000L) {
                if (!biasReady) {
                    bias[0] = wx; bias[1] = wy; bias[2] = wz
                    biasReady = true
                } else {
                    bias[0] += (wx - bias[0]) * 0.05
                    bias[1] += (wy - bias[1]) * 0.05
                    bias[2] += (wz - bias[2]) * 0.05
                }
            }
        } else {
            stillSinceNs = 0L
        }
        if (biasReady) {
            wx -= bias[0]; wy -= bias[1]; wz -= bias[2]
        }

        // ---------- 2. 投影到世界坐标 ----------
        val yawRate: Double
        val pitchRate: Double

        if (hasRotationMatrix) {
            val m = rotMatrix
            // world = R * device（R 为行主序机身->世界）
            val wWorldX = m[0] * wx + m[1] * wy + m[2] * wz   // 东
            val wWorldY = m[3] * wx + m[4] * wy + m[5] * wz   // 北
            val wWorldZ = m[6] * wx + m[7] * wy + m[8] * wz   // 天（世界竖直轴）

            // yaw：绕世界竖直轴的旋转 -> 水平移动
            yawRate = wWorldZ

            // 屏幕法线在世界的分量 = R 的第三列
            val nx = m[2]
            val ny = m[5]
            // pitch 轴 = up × n（up=(0,0,1)），归一化后为水平轴
            val ax = -ny
            val ay = nx
            val an = sqrt(ax * ax + ay * ay)
            // 手机屏幕接近水平（法线几乎竖直）时该轴退化，此时 pitch 无意义
            pitchRate = if (an > 0.15) {
                (wWorldX * (ax / an) + wWorldY * (ay / an))
            } else {
                0.0
            }
        } else {
            // 没有旋转矢量时退回机身坐标（兼容老设备）
            yawRate = wy
            pitchRate = wx
        }

        telYaw = yawRate.toFloat()
        telPitch = pitchRate.toFloat()

        // ---------- 3. 死区 ----------
        val yawDz = applyDeadzone(yawRate, deadzone)
        val pitchDz = applyDeadzone(pitchRate, deadzone)

        if (!enabled) return
        if (nowNs < suppressUntilNs) return

        // ---------- 4. One Euro 滤波 ----------
        val t = nowNs / 1_000_000_000.0
        val sYaw = filterYaw.filter(yawDz, t)
        val sPitch = filterPitch.filter(pitchDz, t)

        // ---------- 5. 非线性增益 ----------
        val gx = shapedGain(sYaw) * sensitivity * (if (invertX) 1.0 else -1.0)
        val gy = shapedGain(sPitch) * sensitivity * (if (invertY) -1.0 else 1.0)

        val dx = (gx * dt).toFloat()
        val dy = (gy * dt).toFloat()
        telDx = dx
        telDy = dy
        if (dx == 0f && dy == 0f) return

        onDelta?.invoke(dx, dy)
    }

    private fun applyDeadzone(v: Double, d: Double): Double {
        val a = abs(v)
        return if (a < d) 0.0 else v - d * sign(v)
    }

    /** 低速 1.0 倍（能点小图标），高速最高约 4 倍（一甩跨屏） */
    private fun shapedGain(v: Double): Double {
        val a = abs(v)
        if (a < 1e-6) return 0.0
        val r = a / maxRate
        return v * (1.0 + 3.0 * r * r)
    }
}
