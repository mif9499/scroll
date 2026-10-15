package scroll

import androidx.compose.animation.core.AnimationConstants

/** 单帧步长上限 33.3ms: 长帧截短, 60Hz 的 16.7ms 要原样放行。 */
private const val MaxFrameStepNanos = 33_333_333L

private const val MaxFrameGapNanos = 100_000_000L

/** 把原始帧时间戳折算成单调、步长可控的动画时间。 */
internal class FrameClock {
    var startTimeNanos: Long = AnimationConstants.UnspecifiedTime
        private set

    var playTimeNanos: Long = 0L
        private set

    var lastFrameTimeNanos: Long = AnimationConstants.UnspecifiedTime
        private set

    fun reset(firstFrameNanos: Long) {
        startTimeNanos = firstFrameNanos
        playTimeNanos = 0L
        lastFrameTimeNanos = AnimationConstants.UnspecifiedTime
    }

    fun advance(frameTimeNanos: Long): Boolean {
        val last = lastFrameTimeNanos
        val gap =
            if (last == AnimationConstants.UnspecifiedTime) {
                frameTimeNanos - startTimeNanos
            } else {
                frameTimeNanos - last
            }

        // 时间戳倒退/重复: 不推进, 帧基准留在原处
        if (gap <= 0L) return false
        // 这中间根本没有帧: 整段丢掉, 从这一帧接着走
        if (gap >= MaxFrameGapNanos) {
            lastFrameTimeNanos = frameTimeNanos
            return true
        }

        playTimeNanos += minOf(gap, MaxFrameStepNanos)
        lastFrameTimeNanos = frameTimeNanos
        return true
    }
}
