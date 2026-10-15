package scroll

import androidx.annotation.FloatRange
import androidx.compose.animation.core.FloatDecayAnimationSpec
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** v(t) = v₀·(m·e^{-kt} - e^{-mkt})/(m-1)（m > 1 时 a(0) = 0, 起步不顿挫）, 总位移 v₀·(m+1)/(m·k)。 */
class BiExponentialDecay(
    @FloatRange(from = 0.0, fromInclusive = false) maxDeceleration: Float = 5f,
    @FloatRange(from = 1.0, fromInclusive = false) m: Float = 2f,
    @FloatRange(from = 0.0, fromInclusive = false) absVelocityThreshold: Float = 1f,
) : FloatDecayAnimationSpec {
    override val absVelocityThreshold: Float = max(0.0000001f, abs(absVelocityThreshold))
    private val k: Double = max(0.0001f, maxDeceleration).toDouble()
    private val m: Double = max(1.0001f, m).toDouble()
    private val mMinus1: Double = m - 1.0

    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        initialVelocity: Float,
    ): Float {
        val durationNanos = getDurationNanos(initialValue, initialVelocity)
        if (durationNanos == 0L || playTimeNanos >= durationNanos) {
            return getTargetValue(initialValue, initialVelocity)
        }
        val tSec = playTimeNanos / 1e9
        val eSlow = exp(-k * tSec)
        val eFast = exp(-m * k * tSec)
        val displacement = initialVelocity / mMinus1 * (
            m / k * (1.0 - eSlow) - 1.0 / (m * k) * (1.0 - eFast)
        )
        return initialValue + displacement.toFloat()
    }

    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        initialVelocity: Float,
    ): Float {
        val durationNanos = getDurationNanos(initialValue, initialVelocity)
        if (durationNanos == 0L || playTimeNanos >= durationNanos) {
            return 0f
        }
        val tSec = playTimeNanos / 1e9
        val eSlow = exp(-k * tSec)
        val eFast = exp(-m * k * tSec)
        return (initialVelocity / mMinus1 * (m * eSlow - eFast)).toFloat()
    }

    @Suppress("MethodNameUnits")
    override fun getDurationNanos(initialValue: Float, initialVelocity: Float): Long {
        val absV0 = abs(initialVelocity)
        if (absV0 <= absVelocityThreshold) return 0L
        val target = absVelocityThreshold / absV0
        val stopTimeSec = solveForStopTime(target)
        return (stopTimeSec * 1e9).toLong()
    }

    override fun getTargetValue(initialValue: Float, initialVelocity: Float): Float {
        val absV0 = abs(initialVelocity)
        if (absV0 <= absVelocityThreshold) return initialValue
        val target = absVelocityThreshold / absV0
        val stopTimeSec = solveForStopTime(target)
        val eSlow = exp(-k * stopTimeSec)
        val eFast = exp(-m * k * stopTimeSec)
        val displacement = initialVelocity / mMinus1 * (
            m / k * (1.0 - eSlow) - 1.0 / (m * k) * (1.0 - eFast)
        )
        return initialValue + displacement.toFloat()
    }

    // 牛顿法 3 轮
    private fun solveForStopTime(target: Float): Double {
        var t = ln(m / (target * mMinus1)) / k
        if (t < 0.0) t = 0.0
        repeat(3) {
            val eSlow = exp(-k * t)
            val eFast = exp(-m * k * t)
            val f = (m * eSlow - eFast) / mMinus1 - target
            val fPrime = m * k / mMinus1 * (eFast - eSlow)
            t -= f / fPrime
        }
        return max(t, 0.0)
    }
}
